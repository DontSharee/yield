package me.dontshare.yieldleaderboards;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldleaderboards.data.LeaderboardContentLoader;
import me.dontshare.yieldleaderboards.data.LeaderboardDefinition;
import me.dontshare.yieldleaderboards.data.StatDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Checks once a minute which configured leaderboards are due (per their own
 * {@code update-delay-minutes}, defaulting all of them to the same 10
 * minutes so they stay in sync unless overridden) and refreshes each due
 * one: an async Mongo projection query (never a native sort - see {@link
 * StatDefinition}'s type, currency fields are string-encoded and would sort
 * lexicographically), ranked in Java, then pushed to the matching
 * FancyHolograms hologram back on the main thread.
 */
public final class LeaderboardService {

    private static final long CHECK_INTERVAL_TICKS = 20L * 60; // check once a minute
    private static final int TOP_N = 10;
    private static final Duration FADE_IN = Duration.ofMillis(300);
    private static final Duration STAY = Duration.ofSeconds(2, 500_000_000);
    private static final Duration FADE_OUT = Duration.ofMillis(500);

    private final JavaPlugin plugin;
    private final Supplier<LeaderboardContentLoader.Content> content;
    private final DatabaseManager databaseManager;
    private final Map<String, Long> lastUpdatedAt = new ConcurrentHashMap<>();
    /** The ordered top-10 UUID list from each leaderboard's own PREVIOUS refresh - diffed against the new one every refresh to drive rank-change notifications (see {@link #announceChanges}). Never persisted; a fresh server boot just skips notifications on the very first refresh (nothing to diff against yet) rather than falsely announcing every current top-10 member as a "new entrant". */
    private final Map<String, List<UUID>> previousTopByLeaderboard = new ConcurrentHashMap<>();

    public LeaderboardService(JavaPlugin plugin, Supplier<LeaderboardContentLoader.Content> content, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.content = content;
        this.databaseManager = databaseManager;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    /** Forces an immediate refresh of every configured leaderboard, ignoring each one's own update delay. */
    public void refreshAll() {
        long now = System.currentTimeMillis();
        for (LeaderboardDefinition leaderboard : content.get().leaderboards().values()) {
            lastUpdatedAt.put(leaderboard.id(), now);
            refresh(leaderboard);
        }
    }

    /** Forces an immediate refresh of one configured leaderboard by id, ignoring its own update delay. Returns false if no such leaderboard is configured. */
    public boolean refreshOne(String leaderboardId) {
        LeaderboardDefinition leaderboard = content.get().leaderboards().get(leaderboardId);
        if (leaderboard == null) {
            return false;
        }
        lastUpdatedAt.put(leaderboard.id(), System.currentTimeMillis());
        refresh(leaderboard);
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (LeaderboardDefinition leaderboard : content.get().leaderboards().values()) {
            long last = lastUpdatedAt.getOrDefault(leaderboard.id(), 0L);
            if (now - last < leaderboard.updateDelayMillis()) {
                continue;
            }
            lastUpdatedAt.put(leaderboard.id(), now);
            refresh(leaderboard);
        }
    }

    private void refresh(LeaderboardDefinition leaderboard) {
        StatDefinition stat = content.get().stats().get(leaderboard.statId());
        if (stat == null) {
            return;
        }
        databaseManager.supplyAsync(() -> rank(stat))
                .thenAccept(ranked -> Bukkit.getScheduler().runTask(plugin,
                        () -> updateHologram(leaderboard, stat, ranked.entries(), ranked.names())))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to refresh leaderboard '" + leaderboard.id() + "': " + ex.getMessage());
                    return null;
                });
    }

    /** The top entries plus the display name for each, both resolved off the main thread. */
    private record Ranking(List<Map.Entry<UUID, Comparable<?>>> entries, Map<UUID, String> names) {
    }

    /** Runs off the main thread - see DatabaseManager's own Javadoc on why this must never run inline on a tick. */
    private Ranking rank(StatDefinition stat) {
        MongoCollection<Document> collection = databaseManager.getCollection("playerData");
        // Usernames come from the same projection as the stat. Resolving them
        // later through Bukkit's OfflinePlayer instead would mean a lookup
        // that can miss the local usercache and block on Mojang's API - and
        // it was being done on the main thread.
        List<Document> docs = collection.find()
                .projection(Projections.include("_id", stat.field(), "core.username"))
                .into(new ArrayList<>());

        List<Map.Entry<UUID, Comparable<?>>> entries = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        for (Document doc : docs) {
            UUID id = doc.get("_id", UUID.class);
            if (id == null) {
                continue;
            }
            Object raw = resolvePath(doc, stat.field());
            entries.add(Map.entry(id, stat.type().parse(raw)));
            if (resolvePath(doc, "core.username") instanceof String username) {
                names.put(id, username);
            }
        }
        entries.sort((a, b) -> compareDescending(a.getValue(), b.getValue()));
        return new Ranking(entries.size() > TOP_N ? entries.subList(0, TOP_N) : entries, names);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareDescending(Comparable a, Comparable b) {
        return b.compareTo(a);
    }

    /** A raw {@link Document} doesn't resolve a dotted key on its own (that's a query-side convention) - walk the nesting manually. */
    private Object resolvePath(Document doc, String dottedField) {
        Object current = doc;
        for (String part : dottedField.split("\\.")) {
            if (!(current instanceof Document nested)) {
                return null;
            }
            current = nested.get(part);
        }
        return current;
    }

    /**
     * Diffs this refresh's top-10 order against the last one and personally
     * notifies whoever's online for three kinds of movement: a fresh entry
     * into the top 10, climbing to a better rank within it, and getting
     * bumped out entirely - previously a player had to walk up to the
     * physical hologram and happen to notice their own name moved, with
     * nothing telling them it happened. A brand new #1 also gets a
     * server-wide broadcast, same weight as a world boss kill.
     */
    private void announceChanges(LeaderboardDefinition leaderboard, List<Map.Entry<UUID, Comparable<?>>> ranked) {
        List<UUID> newOrder = ranked.stream().map(Map.Entry::getKey).toList();
        List<UUID> previousOrder = previousTopByLeaderboard.put(leaderboard.id(), newOrder);
        if (previousOrder == null) {
            return;
        }

        for (int i = 0; i < newOrder.size(); i++) {
            UUID id = newOrder.get(i);
            int newRank = i + 1;
            int previousIndex = previousOrder.indexOf(id);
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            if (previousIndex < 0) {
                announceNewEntrant(player, leaderboard, newRank);
            } else if (previousIndex > i) {
                announceClimb(player, leaderboard, newRank);
            }
        }
        for (UUID id : previousOrder) {
            if (newOrder.contains(id)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                announceDropped(player, leaderboard);
            }
        }
        if (!newOrder.isEmpty() && !newOrder.get(0).equals(previousOrder.isEmpty() ? null : previousOrder.get(0))) {
            Player newLeader = Bukkit.getPlayer(newOrder.get(0));
            if (newLeader != null) {
                announceNewLeader(newLeader, leaderboard);
            }
        }
    }

    private void announceNewEntrant(Player player, LeaderboardDefinition leaderboard, int rank) {
        Component main = Text.parse("<" + leaderboard.colorHex() + "><bold>TOP 10!</bold></" + leaderboard.colorHex() + ">");
        Component sub = Text.parse("<gray>You're now #<rank> in <title></gray>",
                Placeholder.unparsed("rank", String.valueOf(rank)), Placeholder.unparsed("title", leaderboard.title()));
        player.showTitle(Title.title(main, sub, Title.Times.times(FADE_IN, STAY, FADE_OUT)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
    }

    private void announceClimb(Player player, LeaderboardDefinition leaderboard, int rank) {
        player.sendMessage(Text.parse("<" + leaderboard.colorHex() + ">▲ Climbed to #<rank> in <title>!</" + leaderboard.colorHex() + ">",
                Placeholder.unparsed("rank", String.valueOf(rank)), Placeholder.unparsed("title", leaderboard.title())));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
    }

    private void announceDropped(Player player, LeaderboardDefinition leaderboard) {
        player.sendMessage(Text.parse("<gray>▼ You've been bumped from the Top 10 in <title> - go reclaim your spot!</gray>",
                Placeholder.unparsed("title", leaderboard.title())));
    }

    private void announceNewLeader(Player player, LeaderboardDefinition leaderboard) {
        Bukkit.broadcast(Text.parse(
                "<" + leaderboard.colorHex() + "><bold>♔ NEW #1!</bold> <white><player></white> <gray>just took the top of</gray> <title></" + leaderboard.colorHex() + ">",
                Placeholder.unparsed("player", player.getName()), Placeholder.unparsed("title", leaderboard.title())));
        Component main = Text.parse("<" + leaderboard.colorHex() + "><bold>#1!</bold></" + leaderboard.colorHex() + ">");
        Component sub = Text.parse("<gray>You lead <title></gray>", Placeholder.unparsed("title", leaderboard.title()));
        player.showTitle(Title.title(main, sub, Title.Times.times(FADE_IN, STAY, FADE_OUT)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
    }

    private void updateHologram(LeaderboardDefinition leaderboard, StatDefinition stat,
                                 List<Map.Entry<UUID, Comparable<?>>> ranked, Map<UUID, String> names) {
        announceChanges(leaderboard, ranked);
        // Asked of the plugin manager first: FancyHolograms is a softdepend,
        // and touching FancyHologramsPlugin at all without it installed
        // throws NoClassDefFoundError on every refresh.
        if (!Bukkit.getPluginManager().isPluginEnabled("FancyHolograms") || !FancyHologramsPlugin.isEnabled()) {
            return;
        }
        Optional<Hologram> hologramOpt = FancyHologramsPlugin.get().getHologramManager().getHologram(leaderboard.hologramName());
        if (hologramOpt.isEmpty()) {
            plugin.getLogger().warning("Leaderboard '" + leaderboard.id() + "' - no hologram named '"
                    + leaderboard.hologramName() + "' found. Create it in-game with /hologram create " + leaderboard.hologramName());
            return;
        }
        Hologram hologram = hologramOpt.get();
        if (!(hologram.getData() instanceof TextHologramData textData)) {
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add("<" + leaderboard.colorHex() + "><bold>" + leaderboard.title() + "</bold>");
        lines.add("");
        int rank = 1;
        for (Map.Entry<UUID, Comparable<?>> entry : ranked) {
            String name = names.getOrDefault(entry.getKey(), "Unknown");
            lines.add("<gold>#" + rank + "</gold> <white>" + name + "</white> <gray>-</gray> <yellow>" + stat.type().format(entry.getValue()) + "</yellow>");
            rank++;
        }
        if (ranked.isEmpty()) {
            lines.add("<gray>No data yet.</gray>");
        }
        textData.setText(lines);
        hologram.forceUpdate();
    }
}
