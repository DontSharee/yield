package me.dontshare.yieldzones.cube;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.packet.ItemDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Coins and diamonds you can see: every payout from a cube pops out of it
 * as glowing sunflowers (coins) and diamonds, lands on the floor around it,
 * and homes in on the player once they are inside their magnet range. The
 * money is credited when a drop reaches them.
 * <p>
 * Nothing is ever lost. A drop left on the floor for {@link
 * #AUTO_COLLECT_TICKS} flies to its owner on its own, and leaving the zone,
 * logging off or a shutdown credits every drop still out instantly - the
 * magnet makes collecting snappier, it never decides whether you get paid.
 * <p>
 * Every drop is a packet-only item display seen only by its owner, like the
 * cubes themselves.
 */
public final class LootDropService implements Listener {

    /** COSMETIC carries nothing: it's the visible stand-in for something already handed over (a book, a candy). */
    public enum Kind { COIN, DIAMOND, COSMETIC }

    /** Magnet range with no upgrades, in blocks. */
    public static final double BASE_MAGNET_RANGE = 6.0;
    /** A drop resting this long homes in regardless of range - 20 seconds. */
    private static final int AUTO_COLLECT_TICKS = 400;
    /** More drops than this at once and the rest are credited directly, without a body. */
    private static final int MAX_DROPS_PER_PLAYER = 90;
    private static final int RISE_TICKS = 5;
    private static final int FALL_TICKS = 7;
    /** Close enough to count as collected. */
    private static final double PICKUP_DISTANCE = 0.8;
    /** A homing drop that somehow never arrives is collected anyway after this long. */
    private static final int MAX_HOMING_TICKS = 60;
    private static final int SCOREBOARD_REFRESH_TICKS = 10;
    private static final long PICKUP_STREAK_MILLIS = 700L;

    private static final ItemStack COIN_ITEM = new ItemStack(Material.SUNFLOWER);
    private static final ItemStack DIAMOND_ITEM = new ItemStack(Material.DIAMOND);
    private static final int COIN_GLOW = 0xFFC83D;
    private static final int DIAMOND_GLOW = 0x55FFFF;

    private enum State { RISING, FALLING, RESTING, HOMING }

    private static final class Drop {
        final int entityId;
        final Kind kind;
        final long amount;
        final Location apex;
        final Location landing;
        Location position;
        Vector velocity = new Vector();
        State state = State.RISING;
        int stateTicks;
        int restingTicks;
        /** Rests this long at most before flying in, whatever the range. */
        int autoHomeTicks = AUTO_COLLECT_TICKS;

        Drop(int entityId, Kind kind, long amount, Location start, Location apex, Location landing) {
            this.entityId = entityId;
            this.kind = kind;
            this.amount = amount;
            this.position = start;
            this.apex = apex;
            this.landing = landing;
        }
    }

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final Map<UUID, List<Drop>> dropsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Function<PackPlayerProfile, Double>> magnetRangeProviders = new ConcurrentHashMap<>();
    private final Map<UUID, Long> scoreboardDirtySince = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPickupAt = new ConcurrentHashMap<>();
    private final Map<UUID, Float> pickupPitch = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastPickupSoundTick = new ConcurrentHashMap<>();
    /** Each player's magnet range and the tick it was worked out - every provider walk, once a second rather than every tick. */
    private record CachedRange(double range, int tick) {
    }
    private final Map<UUID, CachedRange> rangeCache = new ConcurrentHashMap<>();
    private static final int RANGE_REFRESH_TICKS = 20;
    /** Homing drops move every tick but tell the client every other tick, gliding over this many - half the packets, same curve. */
    private static final int HOMING_SEND_EVERY = 2;
    private static final int HOMING_GLIDE_TICKS = 3;

    /** What a run of pickups has added, shown as "+12.5K" beside the sidebar's wallet lines - see {@link #decorateWallet}. */
    private static final class RecentGain {
        long coins;
        long diamonds;
        int lastTick;
    }
    private final Map<UUID, RecentGain> recentGains = new ConcurrentHashMap<>();
    /** How long after the last pickup the "+" figure stays up. */
    private static final int GAIN_WINDOW_TICKS = 40;
    private static final String COINS_LABEL = me.dontshare.yieldcore.text.Formatting.fancyFont("coins: ");
    private static final String DIAMONDS_LABEL = me.dontshare.yieldcore.text.Formatting.fancyFont("diamonds: ");

    public LootDropService(JavaPlugin plugin, YieldPacks packs) {
        this.plugin = plugin;
        this.packs = packs;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        JavaPlugin.getPlugin(YieldCore.class).getScoreboardDisplay().addLineTransformer(this::decorateWallet);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Extra magnet range in blocks, summed on top of {@link #BASE_MAGNET_RANGE} - e.g. yield-upgrades' Magnet station. */
    public void registerMagnetRangeProvider(String key, Function<PackPlayerProfile, Double> provider) {
        magnetRangeProviders.put(key, provider);
    }

    public void unregisterMagnetRangeProvider(String key) {
        magnetRangeProviders.remove(key);
    }

    public double magnetRange(PackPlayerProfile profile) {
        double range = BASE_MAGNET_RANGE;
        for (Function<PackPlayerProfile, Double> provider : magnetRangeProviders.values()) {
            Double value = provider.apply(profile);
            if (value != null) {
                range += Math.max(0.0, value);
            }
        }
        return range;
    }

    /**
     * Pops {@code amount} of {@code kind} out of a cube as {@code pieces}
     * separate drops, each carrying an even share. They burst from {@code
     * center} and land in a ring around the cube's footprint on {@code
     * floorY}; {@code spread} is roughly the cube's half-width so a giant's
     * loot clears its sides.
     */
    public void spawn(Player player, Location center, double floorY, double spread, Kind kind, long amount, int pieces) {
        if (amount <= 0) {
            return;
        }
        List<Drop> drops = dropsByPlayer.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>());
        int count = (int) Math.max(1, Math.min(pieces, amount));
        int room = MAX_DROPS_PER_PLAYER - drops.size();
        if (room <= 0) {
            // Too much on the floor already - straight into the balance.
            credit(player, kind, amount);
            return;
        }
        // Fewer, bigger pieces rather than dropping any of the amount.
        count = Math.min(count, room);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            long piece = share(amount, count, i);
            double angle = random.nextDouble(Math.PI * 2);
            double radius = spread + random.nextDouble(0.8, 1.9);
            Location landing = new Location(center.getWorld(),
                    center.getX() + Math.cos(angle) * radius, floorY + 0.3, center.getZ() + Math.sin(angle) * radius);
            Location apex = center.clone().add(Math.cos(angle) * radius * 0.45, 1.0 + random.nextDouble(0.6), Math.sin(angle) * radius * 0.45);
            Drop drop = new Drop(PacketEntityManager.nextEntityId(), kind, piece, center.clone(), apex, landing);
            drops.add(drop);
            spawnBody(player, drop);
        }
    }

    /**
     * A one-off showpiece: {@code item} pops out of the cube higher than
     * loot does, glowing {@code glowRgb}, hangs a moment where it lands,
     * then flies in regardless of range. Credits nothing - it's the visible
     * half of a drop that was already handed over.
     */
    public void spawnCosmetic(Player player, Location center, double floorY, double spread, ItemStack item, int glowRgb, float scale) {
        List<Drop> drops = dropsByPlayer.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>());
        if (drops.size() >= MAX_DROPS_PER_PLAYER) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(Math.PI * 2);
        double radius = spread + 1.0;
        Location landing = new Location(center.getWorld(),
                center.getX() + Math.cos(angle) * radius, floorY + 0.45, center.getZ() + Math.sin(angle) * radius);
        Location apex = center.clone().add(Math.cos(angle) * radius * 0.4, 2.2, Math.sin(angle) * radius * 0.4);
        Drop drop = new Drop(PacketEntityManager.nextEntityId(), Kind.COSMETIC, 0L, center.clone(), apex, landing);
        drop.autoHomeTicks = 25;
        drops.add(drop);
        PacketEntityManager.beginBundle(player);
        ItemDisplayManager.spawn(player, drop.entityId, drop.position);
        ItemDisplayManager.setItem(player, drop.entityId, item);
        ItemDisplayManager.setBillboardCenter(player, drop.entityId);
        ItemDisplayManager.setScale(player, drop.entityId, scale, scale, scale);
        ItemDisplayManager.setGlowing(player, drop.entityId, true);
        ItemDisplayManager.setGlowColor(player, drop.entityId, glowRgb);
        ItemDisplayManager.setPositionInterpolation(player, drop.entityId, RISE_TICKS);
        PacketEntityManager.endBundle(player);
    }

    /** {@code amount} split into {@code count} near-equal parts; part 0 takes the remainder. */
    private static long share(long amount, int count, int index) {
        long base = amount / count;
        return index == 0 ? base + amount % count : base;
    }

    private void spawnBody(Player player, Drop drop) {
        PacketEntityManager.beginBundle(player);
        ItemDisplayManager.spawn(player, drop.entityId, drop.position);
        ItemDisplayManager.setItem(player, drop.entityId, drop.kind == Kind.COIN ? COIN_ITEM : DIAMOND_ITEM);
        ItemDisplayManager.setBillboardCenter(player, drop.entityId);
        float scale = drop.kind == Kind.COIN ? 0.55f : 0.5f;
        ItemDisplayManager.setScale(player, drop.entityId, scale, scale, scale);
        ItemDisplayManager.setGlowing(player, drop.entityId, true);
        ItemDisplayManager.setGlowColor(player, drop.entityId, drop.kind == Kind.COIN ? COIN_GLOW : DIAMOND_GLOW);
        ItemDisplayManager.setPositionInterpolation(player, drop.entityId, RISE_TICKS);
        PacketEntityManager.endBundle(player);
    }

    private void tick() {
        int now = Bukkit.getCurrentTick();
        for (Map.Entry<UUID, List<Drop>> entry : dropsByPlayer.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            List<Drop> drops = entry.getValue();
            if (player == null || drops.isEmpty()) {
                continue;
            }
            double range = cachedRange(player, now);
            Location target = player.getLocation().add(0, 0.9, 0);
            Iterator<Drop> it = drops.iterator();
            while (it.hasNext()) {
                Drop drop = it.next();
                // Changed worlds with loot still out: pay it, no chase.
                boolean elsewhere = !drop.position.getWorld().equals(target.getWorld());
                if (elsewhere || step(player, drop, target, range)) {
                    it.remove();
                    collect(player, drop, elsewhere);
                }
            }
        }
        for (Map.Entry<UUID, RecentGain> entry : recentGains.entrySet()) {
            if (now - entry.getValue().lastTick >= GAIN_WINDOW_TICKS) {
                recentGains.remove(entry.getKey());
                // Redraw once more so the "+" figure goes away.
                scoreboardDirtySince.putIfAbsent(entry.getKey(), (long) now - SCOREBOARD_REFRESH_TICKS);
            }
        }
        for (Map.Entry<UUID, Long> dirty : scoreboardDirtySince.entrySet()) {
            if (now - dirty.getValue() >= SCOREBOARD_REFRESH_TICKS) {
                Player player = Bukkit.getPlayer(dirty.getKey());
                scoreboardDirtySince.remove(dirty.getKey());
                if (player != null) {
                    JavaPlugin.getPlugin(YieldCore.class).getScoreboardDisplay().refresh(player);
                }
            }
        }
    }

    private double cachedRange(Player player, int now) {
        CachedRange cached = rangeCache.get(player.getUniqueId());
        if (cached != null && now - cached.tick() < RANGE_REFRESH_TICKS) {
            return cached.range();
        }
        PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
        double range = profile != null ? magnetRange(profile) : BASE_MAGNET_RANGE;
        rangeCache.put(player.getUniqueId(), new CachedRange(range, now));
        return range;
    }

    /** Advances one drop a tick. True once it has reached the player. */
    private boolean step(Player player, Drop drop, Location target, double range) {
        drop.stateTicks++;
        switch (drop.state) {
            case RISING -> {
                if (drop.stateTicks == 1) {
                    drop.position = drop.apex.clone();
                    PacketEntityManager.teleportEntity(player, drop.entityId, drop.position);
                } else if (drop.stateTicks >= RISE_TICKS) {
                    drop.state = State.FALLING;
                    drop.stateTicks = 0;
                    ItemDisplayManager.setPositionInterpolation(player, drop.entityId, FALL_TICKS);
                    drop.position = drop.landing.clone();
                    PacketEntityManager.teleportEntity(player, drop.entityId, drop.position);
                }
            }
            case FALLING -> {
                if (drop.stateTicks >= FALL_TICKS) {
                    drop.state = State.RESTING;
                    drop.stateTicks = 0;
                }
            }
            case RESTING -> {
                drop.restingTicks++;
                if (drop.position.distanceSquared(target) <= range * range || drop.restingTicks >= drop.autoHomeTicks) {
                    drop.state = State.HOMING;
                    drop.stateTicks = 0;
                    // A little hop up and out first, so the pull reads as
                    // the drop being picked up rather than sliding along.
                    drop.velocity = new Vector(0, 0.28, 0);
                    ItemDisplayManager.setPositionInterpolation(player, drop.entityId, HOMING_GLIDE_TICKS);
                }
            }
            case HOMING -> {
                Vector toPlayer = target.toVector().subtract(drop.position.toVector());
                double distance = toPlayer.length();
                if (distance <= PICKUP_DISTANCE || drop.stateTicks >= MAX_HOMING_TICKS) {
                    return true;
                }
                // Steer, don't snap: ease the velocity toward a speed that
                // climbs the longer it has been flying, so drops start
                // lazily, then zip in - and curve in from wherever they were
                // rather than moving in a dead straight line.
                double speed = Math.min(1.8, 0.25 + drop.stateTicks * 0.07);
                Vector desired = toPlayer.multiply(Math.min(speed, distance) / distance);
                drop.velocity = drop.velocity.multiply(0.6).add(desired.multiply(0.4));
                if (drop.velocity.length() > distance) {
                    return true;
                }
                drop.position.add(drop.velocity);
                if (drop.stateTicks % HOMING_SEND_EVERY == 0) {
                    PacketEntityManager.teleportEntity(player, drop.entityId, drop.position);
                }
            }
        }
        return false;
    }

    private void collect(Player player, Drop drop, boolean silent) {
        PacketEntityManager.destroyEntity(player, drop.entityId);
        credit(player, drop.kind, drop.amount);
        if (!silent) {
            playPickupSound(player, drop.kind);
        }
    }

    private void credit(Player player, Kind kind, long amount) {
        if (amount <= 0 || kind == Kind.COSMETIC) {
            return;
        }
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        if (kind == Kind.COIN) {
            profile.setCoins(profile.getCoins().add(BigInteger.valueOf(amount)));
        } else {
            profile.setDiamonds(profile.getDiamonds().add(BigInteger.valueOf(amount)));
        }
        RecentGain gain = recentGains.computeIfAbsent(player.getUniqueId(), id -> new RecentGain());
        if (kind == Kind.COIN) {
            gain.coins += amount;
        } else {
            gain.diamonds += amount;
        }
        gain.lastTick = Bukkit.getCurrentTick();
        scoreboardDirtySince.putIfAbsent(player.getUniqueId(), (long) Bukkit.getCurrentTick());
    }

    /**
     * Puts the running total of a pickup streak next to the wallet lines -
     * "coins: 1.2M +12.5K" - growing as a burst of drops sweeps in, gone a
     * couple of seconds after the last one lands. Keyed on the sidebar's
     * own small-caps labels.
     */
    private List<String> decorateWallet(Player player, List<String> lines) {
        RecentGain gain = recentGains.get(player.getUniqueId());
        if (gain == null) {
            return lines;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (gain.coins > 0 && line.contains(COINS_LABEL)) {
                lines.set(i, line + " <#55FF7F>+" + me.dontshare.yieldcore.text.Formatting.format(gain.coins));
            } else if (gain.diamonds > 0 && line.contains(DIAMONDS_LABEL)) {
                lines.set(i, line + " <#55FF7F>+" + me.dontshare.yieldcore.text.Formatting.format(gain.diamonds));
            }
        }
        return lines;
    }

    /**
     * A soft pickup blip whose pitch climbs while pickups keep coming, so a
     * big burst sweeping in sounds like a run of coins rather than one
     * sound repeated. At most one per two ticks, quiet, at the player.
     */
    private void playPickupSound(Player player, Kind kind) {
        UUID id = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        Integer last = lastPickupSoundTick.get(id);
        if (last != null && tick - last < 2) {
            return;
        }
        lastPickupSoundTick.put(id, tick);
        long nowMillis = System.currentTimeMillis();
        Long lastAt = lastPickupAt.put(id, nowMillis);
        float pitch = lastAt != null && nowMillis - lastAt < PICKUP_STREAK_MILLIS
                ? Math.min(2.0f, pickupPitch.getOrDefault(id, 1.0f) + 0.05f) : 1.0f;
        pickupPitch.put(id, pitch);
        if (kind == Kind.COSMETIC) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
        } else if (kind == Kind.COIN) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.25f, pitch);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, Math.min(2.0f, pitch + 0.3f));
        }
    }

    /** Credits every drop this player still has out, removing their bodies - on leaving a zone, logging off, or shutdown. */
    public void collectAll(Player player) {
        List<Drop> drops = dropsByPlayer.remove(player.getUniqueId());
        if (drops == null) {
            return;
        }
        for (Drop drop : drops) {
            collect(player, drop, true);
        }
    }

    /**
     * LOWEST, so it runs before the player stores' own quit save - crediting
     * after that save would change a record that is about to be unloaded,
     * and the money would never reach the database.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        collectAll(event.getPlayer());
        scoreboardDirtySince.remove(id);
        lastPickupAt.remove(id);
        pickupPitch.remove(id);
        lastPickupSoundTick.remove(id);
        recentGains.remove(id);
        rangeCache.remove(id);
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            collectAll(player);
            packs.getPlayerStore().saveSync(player.getUniqueId());
        }
    }
}
