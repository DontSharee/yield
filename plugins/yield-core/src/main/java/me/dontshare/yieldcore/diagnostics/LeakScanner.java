package me.dontshare.yieldcore.diagnostics;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Looks for the three ways a Minecraft plugin usually leaks memory, in every
 * Yield plugin at once and without any of them having to opt in:
 * <ol>
 *   <li><b>Per-player data that outlives the player</b> - a map or set keyed
 *       by player whose entries for players who left are never removed;</li>
 *   <li><b>Players who left, still referenced</b> - holding a {@link Player}
 *       after they quit keeps their whole entity, inventory and world data
 *       alive;</li>
 *   <li><b>Things that only ever grow</b> - collections, scheduled tasks and
 *       world entities whose numbers climb scan after scan while the player
 *       count doesn't.</li>
 * </ol>
 * Every few minutes it walks each Yield plugin's objects through their
 * fields. It follows only Yield classes, and only a sample of each large
 * collection. The walk runs on the main thread, where that state is safe to
 * read, a few milliseconds per tick until it's done. Findings count only once
 * they repeat across scans, so a burst of joins or one busy minute isn't
 * reported as a leak.
 */
public final class LeakScanner implements Listener {

    // Both adjustable with -D flags, mainly to see a leak test through quickly.
    private static final long SCAN_EVERY_MS = Math.max(10, Long.getLong("yield.diagnostics.scanSeconds", 300)) * 1000L;
    private static final long FIRST_SCAN_AFTER_MS = Math.min(2 * 60_000L, SCAN_EVERY_MS);
    /** Entries for a player who left less than this long ago are still being cleaned up, not leaked. */
    private static final long GRACE_MS = Math.max(10, Long.getLong("yield.diagnostics.leftGraceSeconds", 300)) * 1000L;
    private static final String GRACE_TEXT = GRACE_MS % 60_000L == 0 ? GRACE_MS / 60_000L + " minutes" : GRACE_MS / 1000L + " seconds";
    private static final long STEP_BUDGET_NANOS = 3_000_000L;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_OBJECTS = 80_000;
    /** Elements of a collection that get looked inside (all of them are counted). */
    private static final int SAMPLE = 48;
    private static final int HISTORY = 13;

    /** Everything found at one field, summed over every object that has it. */
    public record Holder(String path, String plugin, String kind, long size, long stale, long leftPlayers, int instances,
                         long unknownIds, int identity) {
    }

    /** One finished walk. {@code gauges}: scheduled tasks per plugin, entities per type, loaded chunks per world. */
    public record Scan(long at, long durationMs, int objects, boolean complete, int online,
                       List<Holder> holders, Map<String, Long> gauges) {
    }

    /** Something the scans agree looks wrong. */
    public record Suspect(String key, String path, String plugin, String problem, String detail) {
    }

    private static final Set<Class<?>> STATIC_ROOTS = new CopyOnWriteArraySet<>();

    private final Map<UUID, Long> quitAt = new ConcurrentHashMap<>();
    private final Deque<Scan> history = new ArrayDeque<>();
    private final long startedAt = System.currentTimeMillis();
    private long nextScanAt = startedAt + FIRST_SCAN_AFTER_MS;
    private Walk walk;

    /** "5 minutes" - how long after leaving a player's leftovers count as leaked. */
    public static String graceText() {
        return GRACE_TEXT;
    }

    /** Classes whose static fields hold state no plugin object points at - utility registries and the like. */
    public static void watchStatics(Class<?>... classes) {
        STATIC_ROOTS.addAll(Arrays.asList(classes));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        quitAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /** Main thread, every tick. */
    void tick() {
        if (walk == null) {
            if (System.currentTimeMillis() < nextScanAt) {
                return;
            }
            walk = new Walk();
        }
        if (walk.step(STEP_BUDGET_NANOS)) {
            finish();
        }
    }

    /** Main thread: a complete scan right now, for the command. */
    public Scan scanNow() {
        Walk now = new Walk();
        now.step(Long.MAX_VALUE);
        walk = null;
        return finish(now);
    }

    private void finish() {
        Walk done = walk;
        walk = null;
        finish(done);
    }

    private Scan finish(Walk done) {
        nextScanAt = System.currentTimeMillis() + SCAN_EVERY_MS;
        Scan scan = done.result();
        synchronized (history) {
            history.addLast(scan);
            while (history.size() > HISTORY) {
                history.removeFirst();
            }
        }
        long cutoff = System.currentTimeMillis() - 2 * GRACE_MS - SCAN_EVERY_MS * HISTORY;
        quitAt.values().removeIf(at -> at < cutoff);
        return scan;
    }

    public Scan latest() {
        synchronized (history) {
            return history.peekLast();
        }
    }

    public List<Scan> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /** What the scans so far agree on - worked out once per new scan. */
    public List<Suspect> suspects() {
        List<Scan> scans = history();
        if (scans.isEmpty()) {
            return List.of();
        }
        synchronized (history) {
            if (suspectsFor == scans.getLast()) {
                return suspects;
            }
        }
        List<Suspect> found = judge(scans);
        synchronized (history) {
            suspectsFor = scans.getLast();
            suspects = found;
        }
        return found;
    }

    private Scan suspectsFor;
    private List<Suspect> suspects = List.of();

    private static List<Suspect> judge(List<Scan> scans) {
        List<Map<String, Holder>> byPath = new ArrayList<>();
        for (Scan scan : scans) {
            Map<String, Holder> index = new HashMap<>();
            for (Holder holder : scan.holders()) {
                index.put(holder.path(), holder);
            }
            byPath.add(index);
        }
        List<Suspect> found = new ArrayList<>();
        Scan last = scans.getLast();
        for (Holder holder : last.holders()) {
            List<Holder> series = new ArrayList<>();
            for (Map<String, Holder> index : byPath) {
                Holder earlier = index.get(holder.path());
                if (earlier != null) {
                    series.add(earlier);
                }
            }
            if (holder.leftPlayers() > 0 && series.size() >= 2 && series.get(series.size() - 2).leftPlayers() > 0) {
                found.add(new Suspect("leak.players:" + holder.path(), holder.path(), holder.plugin(),
                        "Keeps players who left",
                        holder.leftPlayers() + " Player object(s) of players who left over " + GRACE_TEXT + " ago are still referenced"
                                + " - each keeps that player's entity, inventory and world data in memory"));
                continue;
            }
            // Only for data about players seen this boot. A collection that
            // also holds players who haven't been on since (a leaderboard,
            // a cache loaded from the database) keeps offline players on
            // purpose.
            boolean sessionData = holder.unknownIds() * 20 <= holder.stale() + holder.unknownIds();
            if (sessionData && holder.stale() >= 10 && rising(series.stream().mapToLong(Holder::stale).toArray(), 3)) {
                long before = series.size() >= 4 ? series.get(series.size() - 4).stale() : series.getFirst().stale();
                found.add(new Suspect("leak.stale:" + holder.path(), holder.path(), holder.plugin(),
                        "Not cleaned up when players leave",
                        holder.stale() + " of its " + holder.size() + " entries belong to players who left over " + GRACE_TEXT + " ago"
                                + (before < holder.stale() ? " (up from " + before + ")" : "")));
                continue;
            }
            // The same collections, grown - a snapshot rebuilt each time
            // (a new object on every scan) can't be leaking.
            if (series.size() == scans.size() && sameObjects(series)) {
                long[] sizes = series.stream().mapToLong(Holder::size).toArray();
                if (growing(sizes, 500) && playersSteady(scans)) {
                    found.add(new Suspect("leak.growth:" + holder.path(), holder.path(), holder.plugin(), "Keeps growing",
                            growth(sizes, scans) + " entries while the player count held steady"));
                }
            }
        }
        for (Map.Entry<String, Long> gauge : last.gauges().entrySet()) {
            String name = gauge.getKey();
            long[] values = scans.stream().mapToLong(scan -> scan.gauges().getOrDefault(name, 0L)).toArray();
            long floor = name.startsWith("tasks:") ? 50 : name.startsWith("entities:") ? 300 : Long.MAX_VALUE;
            if (growing(values, floor) && playersSteady(scans)) {
                String what = name.startsWith("tasks:") ? "Scheduled tasks keep piling up"
                        : "World entities keep piling up";
                found.add(new Suspect("leak.gauge:" + name, name, name.startsWith("tasks:") ? name.substring(6) : "server",
                        what, growth(values, scans) + " while the player count held steady"));
            }
        }
        return found;
    }

    private static boolean sameObjects(List<Holder> series) {
        int identity = series.getLast().identity();
        for (int i = Math.max(0, series.size() - 5); i < series.size(); i++) {
            if (series.get(i).identity() != identity) {
                return false;
            }
        }
        return true;
    }

    /** Went up in each of the last {@code steps} scans. */
    private static boolean rising(long[] values, int steps) {
        if (values.length < steps + 1) {
            return false;
        }
        for (int i = values.length - steps; i < values.length; i++) {
            if (values[i] <= values[i - 1]) {
                return false;
            }
        }
        return true;
    }

    /** Never fell over the last five scans, and ended well above where it started. */
    private static boolean growing(long[] values, long floor) {
        if (values.length < 5) {
            return false;
        }
        long[] last = Arrays.copyOfRange(values, values.length - 5, values.length);
        for (int i = 1; i < last.length; i++) {
            if (last[i] < last[i - 1]) {
                return false;
            }
        }
        return last[4] >= floor && last[4] >= last[0] * 3 / 2 && last[4] - last[0] >= floor / 2;
    }

    private static boolean playersSteady(List<Scan> scans) {
        if (scans.size() < 5) {
            return false;
        }
        int before = scans.get(scans.size() - 5).online();
        int now = scans.getLast().online();
        return now <= Math.max(before + 5, before * 13 / 10);
    }

    private static String growth(long[] values, List<Scan> scans) {
        long first = values[values.length - 5];
        long last = values[values.length - 1];
        long minutes = (scans.getLast().at() - scans.get(scans.size() - 5).at()) / 60_000L;
        return "Went from " + first + " to " + last + " over " + minutes + " minutes";
    }

    // ------------------------------------------------------------------ the walk

    private final class Walk {
        private final long started = System.currentTimeMillis();
        private long spentNanos;
        private final ArrayDeque<Object[]> queue = new ArrayDeque<>();
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        private final Set<Class<?>> staticsDone = new HashSet<>();
        private final Map<String, Acc> holders = new HashMap<>();
        private final Set<UUID> online = new HashSet<>();
        private final long now = System.currentTimeMillis();
        private int objects;
        private boolean truncated;
        private boolean rootsAdded;

        Walk() {
            for (Player player : Bukkit.getOnlinePlayers()) {
                online.add(player.getUniqueId());
            }
        }

        boolean step(long budgetNanos) {
            long start = System.nanoTime();
            if (!rootsAdded) {
                rootsAdded = true;
                for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    if (plugin.isEnabled() && yieldClass(plugin.getClass())) {
                        enqueue(plugin, 0);
                    }
                }
                for (Class<?> root : STATIC_ROOTS) {
                    statics(root);
                }
            }
            while (!queue.isEmpty()) {
                if (objects >= MAX_OBJECTS) {
                    truncated = true;
                    queue.clear();
                    break;
                }
                Object[] next = queue.poll();
                visit(next[0], (Integer) next[1]);
                objects++;
                if ((objects & 63) == 0 && System.nanoTime() - start >= budgetNanos) {
                    spentNanos += System.nanoTime() - start;
                    return false;
                }
            }
            spentNanos += System.nanoTime() - start;
            return true;
        }

        private void enqueue(Object object, int depth) {
            if (depth <= MAX_DEPTH && seen.put(object, Boolean.TRUE) == null) {
                queue.add(new Object[]{object, depth});
            }
        }

        private void visit(Object object, int depth) {
            Class<?> type = object.getClass();
            statics(type);
            for (Field field : FIELDS.get(type).instance()) {
                Object value;
                try {
                    value = field.get(object);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    continue;
                }
                handle(value, path(field), depth + 1);
            }
        }

        private void statics(Class<?> type) {
            for (Class<?> c = type; c != null && yieldClass(c); c = c.getSuperclass()) {
                if (!staticsDone.add(c)) {
                    continue;
                }
                for (Field field : FIELDS.get(c).statics()) {
                    Object value;
                    try {
                        value = field.get(null);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        continue;
                    }
                    handle(value, path(field), 1);
                }
            }
        }

        private void handle(Object value, String[] path, int depth) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Enum<?>
                    || value instanceof Boolean || value instanceof UUID) {
                return;
            }
            if (value instanceof Player player) {
                Acc acc = acc(path, "player reference");
                acc.add(player);
                acc.size++;
                if (left(player)) {
                    acc.leftPlayers++;
                }
                return;
            }
            if (value instanceof Map<?, ?> map) {
                if (seen.put(map, Boolean.TRUE) == null) {
                    map(map, path, depth);
                }
                return;
            }
            if (value instanceof Collection<?> collection) {
                if (seen.put(collection, Boolean.TRUE) == null) {
                    elements(collection, collection.size(), path, depth);
                }
                return;
            }
            if (value instanceof Object[] array) {
                if (array.length > 0 && seen.put(array, Boolean.TRUE) == null) {
                    elements(Arrays.asList(array), array.length, path, depth);
                }
                return;
            }
            if (yieldClass(value.getClass())) {
                enqueue(value, depth);
            }
        }

        private void map(Map<?, ?> map, String[] path, int depth) {
            Acc acc = acc(path, "map");
            acc.add(map);
            int size;
            try {
                size = map.size();
            } catch (RuntimeException e) {
                return;
            }
            acc.size += size;
            if (size == 0) {
                return;
            }
            try {
                PlayerKeys keys = new PlayerKeys();
                int looked = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    keys.add(key);
                    if (key instanceof Player player && left(player) || value instanceof Player other && left(other)) {
                        acc.leftPlayers++;
                    }
                    // A sample of the values is looked inside (per-player state
                    // can leak too); a UUID-keyed map's keys are all counted.
                    if (looked < SAMPLE && !(value instanceof Player)) {
                        looked++;
                        handle(value, nested(path), depth + 1);
                    } else if (keys.uuidKeys == 0) {
                        break;
                    }
                }
                keys.apply(acc, "per-player map");
            } catch (ConcurrentModificationException | NoSuchElementException e) {
                // Changed mid-look - counted from what was seen.
            }
        }

        private void elements(Collection<?> collection, int size, String[] path, int depth) {
            Acc acc = acc(path, "collection");
            acc.add(collection);
            acc.size += size;
            if (size == 0) {
                return;
            }
            try {
                PlayerKeys keys = new PlayerKeys();
                int looked = 0;
                for (Object element : collection) {
                    keys.add(element);
                    if (element instanceof Player player && left(player)) {
                        acc.leftPlayers++;
                    }
                    if (!(element instanceof OfflinePlayer) && !(element instanceof UUID)) {
                        if (looked++ >= SAMPLE) {
                            if (keys.uuidKeys == 0) {
                                break;
                            }
                            continue;
                        }
                        handle(element, nested(path), depth + 1);
                    }
                }
                keys.apply(acc, "per-player set");
            } catch (ConcurrentModificationException | NoSuchElementException e) {
                // Changed mid-look - counted from what was seen.
            }
        }

        /**
         * UUIDs among a collection's keys that belong to players seen this
         * boot. A UUID-keyed map is treated as per-player only when some of
         * its keys are players - entity or pet ids are UUIDs too.
         */
        private final class PlayerKeys {
            int uuidKeys;
            int players;
            int stale;
            int unknown;

            void add(Object key) {
                UUID id = key instanceof UUID uuid ? uuid : key instanceof OfflinePlayer player ? player.getUniqueId() : null;
                if (id == null) {
                    return;
                }
                uuidKeys++;
                if (online.contains(id)) {
                    players++;
                    return;
                }
                Long left = quitAt.get(id);
                if (left != null) {
                    players++;
                    // A held Player is counted as a player who left instead.
                    if (now - left >= GRACE_MS && !(key instanceof Player)) {
                        stale++;
                    }
                } else {
                    unknown++;
                }
            }

            void apply(Acc acc, String kind) {
                if (players > 0) {
                    acc.kind = kind;
                    acc.stale += stale;
                    acc.unknownIds += unknown;
                }
            }
        }

        private boolean left(Player player) {
            if (player.isOnline()) {
                return false;
            }
            Long left = quitAt.get(player.getUniqueId());
            return left == null || now - left >= GRACE_MS;
        }

        private Acc acc(String[] path, String kind) {
            return holders.computeIfAbsent(path[0], key -> new Acc(path[0], path[1], kind));
        }

        Scan result() {
            List<Holder> list = new ArrayList<>();
            for (Acc acc : holders.values()) {
                list.add(new Holder(acc.path, acc.plugin, acc.kind, acc.size, acc.stale, acc.leftPlayers, acc.instances,
                        acc.unknownIds, acc.identity));
            }
            list.sort(Comparator.comparingLong(Holder::size).reversed());
            return new Scan(started, spentNanos / 1_000_000L, objects, !truncated, online.size(), list, gauges());
        }
    }

    /** Scheduled tasks by plugin, world entities by type (the common ones), loaded chunks by world. */
    private static Map<String, Long> gauges() {
        Map<String, Long> gauges = new LinkedHashMap<>();
        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            gauges.merge("tasks:" + task.getOwner().getName(), 1L, Long::sum);
        }
        Map<String, Long> entities = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Player)) {
                    entities.merge("entities:" + entity.getType().name(), 1L, Long::sum);
                }
            }
            Chunk[] chunks = world.getLoadedChunks();
            gauges.put("chunks:" + world.getName(), (long) chunks.length);
        }
        entities.forEach((name, count) -> {
            if (count >= 25) {
                gauges.put(name, count);
            }
        });
        return gauges;
    }

    private static final class Acc {
        final String path;
        final String plugin;
        String kind;
        long size;
        long stale;
        long leftPlayers;
        int instances;
        long unknownIds;
        /** The objects found here, combined - changes when any of them is replaced. */
        int identity;

        void add(Object instance) {
            instances++;
            identity = identity * 31 + System.identityHashCode(instance);
        }

        Acc(String path, String plugin, String kind) {
            this.path = path;
            this.plugin = plugin;
            this.kind = kind;
        }
    }

    private record Fields(Field[] instance, Field[] statics) {
    }

    /** Each Yield class's reference-typed fields (its own and its Yield superclasses'), made readable. */
    private static final ClassValue<Fields> FIELDS = new ClassValue<>() {
        @Override
        protected Fields computeValue(Class<?> type) {
            List<Field> instance = new ArrayList<>();
            List<Field> statics = new ArrayList<>();
            for (Class<?> c = type; c != null && yieldClass(c); c = c.getSuperclass()) {
                Field[] declared;
                try {
                    declared = c.getDeclaredFields();
                } catch (Throwable e) {
                    continue;
                }
                for (Field field : declared) {
                    if (field.getType().isPrimitive() || field.isSynthetic() && !field.getName().startsWith("arg$")) {
                        continue;
                    }
                    boolean isStatic = Modifier.isStatic(field.getModifiers());
                    if (isStatic && c != type) {
                        continue; // a superclass's statics are read when it's visited itself
                    }
                    try {
                        if (!field.trySetAccessible()) {
                            continue;
                        }
                    } catch (RuntimeException e) {
                        continue;
                    }
                    (isStatic ? statics : instance).add(field);
                }
            }
            return new Fields(instance.toArray(Field[]::new), statics.toArray(Field[]::new));
        }
    };

    /** Yield's own classes - but not this package, whose bookkeeping is per-player by design. */
    private static boolean yieldClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("me.dontshare.") && !name.startsWith("me.dontshare.yieldcore.diagnostics.");
    }

    /** {@code {"PetDisplayService.views", "yield-packs"}} */
    private static String[] path(Field field) {
        String owner = field.getDeclaringClass().getName();
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        int lambda = simple.indexOf("$$Lambda");
        if (lambda >= 0) {
            simple = simple.substring(0, lambda) + " (lambda)";
        }
        return new String[]{simple + "." + field.getName(), TickMonitor.pluginOf(owner)};
    }

    private static String[] nested(String[] path) {
        return path[0].endsWith("[]") ? path : new String[]{path[0] + "[]", path[1]};
    }
}
