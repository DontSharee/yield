package me.dontshare.yieldzones.cube;

import com.github.retrooper.packetevents.util.Vector3f;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.fakeblock.FakeBlockClickRegistry;
import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldzones.combo.ComboService;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.leveling.Candy;
import me.dontshare.yieldpacks.leveling.MilestoneEffect;
import me.dontshare.yieldpacks.mastery.MasteryType;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzones.data.CubeBonus;
import me.dontshare.yieldzones.data.CubeTier;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.data.ZoneRegion;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import me.dontshare.yieldzones.event.ZoneEnteredEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The ore-cube lifecycle: spawns falling cubes near players standing in a
 * zone (via {@link me.dontshare.yieldcore.fakeblock.FakeFallingBlock}, so
 * each player only ever sees their own), tracks HP once one lands, and pays
 * out on death. A left-click never deals damage directly - it only selects
 * a target, via whatever's currently registered through
 * {@link #setClickHandler} (mode-aware, wired up in {@code YieldZones}) -
 * all the actual damage comes from {@code PetCombatController} calling
 * {@link #queueDamage} on the equipped pets' own attack timers, combined
 * once per player per tick via {@link #flushDamage} - see those methods'
 * own Javadoc for why a single pet's hit is never applied on its own.
 */
public final class OreCubeService implements Listener {

    // Was 20L (1s) - PetCombatController re-evaluates attack targets every
    // 4 ticks, so a full second of lag before this loop even notices a
    // player left the zone meant pets could keep ringing around a
    // long-vacated cube's stale position for up to ~1s afterward (rubber-
    // banding, occasionally severe enough to look like a stuck pet).
    // Matching that same 4-tick cadence here closes the gap; the actual
    // per-tick work (a couple of map lookups per player) is cheap enough
    // that running it 5x more often is not a real cost.
    private static final long TICK_INTERVAL = 4L;
    private static final long SUMMARY_INTERVAL = 20L * 60; // 1 minute
    private static final long HIGHLIGHT_TICK_INTERVAL = 2L; // 0.1s - see tickHighlights
    // Effectively unbounded, same as FakeBlockClickRegistry's own click
    // range - liveCubes() already only ever contains cubes in the zone the
    // player is currently standing in, so that zone's own size is the real
    // limit on how far a highlight (or a click-to-target) can reach, not
    // this constant.
    private static final double HIGHLIGHT_RANGE = 512.0;
    // A glow-carrier BlockDisplay sits exactly on top of the real (fake)
    // placed block underneath it, using the same material, purely so it can
    // hold the "Glowing" flag a real block can't - two coplanar, identically
    // textured opaque surfaces at the exact same position z-fight/flicker
    // against each other otherwise. Nudging the overlay ~2% larger (and
    // re-centering via the matching negative translation) gives it real
    // depth separation without changing its silhouette or breaking the
    // glow outline. See BlockDisplayManager#setTransformation.
    private static final float GLOW_OVERLAY_SCALE = 1.02f;
    private static final float GLOW_OVERLAY_TRANSLATE = -0.01f;
    /** How high above the zone's own configured floor a cube starts its fall - a short, consistent drop regardless of where in the zone it spawns. */
    private static final int FALL_HEIGHT_BLOCKS = 12;
    private static final int DAMAGE_INDICATOR_RISE_TICKS = 10;
    private static final int DAMAGE_INDICATOR_LIFETIME_TICKS = 12;
    private static final int EARNINGS_INDICATOR_RISE_TICKS = 16;
    private static final int EARNINGS_INDICATOR_LIFETIME_TICKS = 20;

    private final JavaPlugin plugin;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final YieldPacks packs;
    private final LuckService luckService;
    private final ComboService comboService = new ComboService();

    private final Map<UUID, ZoneDefinition> currentZone = new ConcurrentHashMap<>();
    private final Map<UUID, List<OreCube>> cubesByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> pendingByPlayer = new ConcurrentHashMap<>();
    // Every in-flight (not yet landed) fall's id, per player - lets leaveZone/
    // onQuit cancel a fall outright instead of leaving it to finish its full
    // drop and only self-correct (briefly flash then erase) once it actually
    // lands, which is what "cubes don't instantly get killed on leaving the
    // zone" was actually reporting.
    private final Map<UUID, Set<UUID>> pendingFallIdsByPlayer = new ConcurrentHashMap<>();
    // Which (x,z) column each in-flight fall is headed for, per player -
    // consulted by spawnCubeFor alongside this player's already-landed
    // cubes so a new cube never rolls a column another of theirs already
    // occupies (see the collision note on spawnCubeFor itself). Cleared
    // per-fallId once that fall lands (the column is then covered by the
    // live cube itself), or wholesale in cancelPendingFalls.
    private final Map<UUID, Map<UUID, Long>> pendingColumnsByFallId = new ConcurrentHashMap<>();
    // A cube already scheduled to respawn (see killCube) counts as "live" too -
    // otherwise the periodic top-up in tick() sees the freed-up slot before the
    // scheduled respawn fires and spawns an extra cube to fill it, doubling up.
    private final Map<UUID, AtomicInteger> scheduledByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, OreCube> targetByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBarByPlayer = new ConcurrentHashMap<>();
    // Accumulates every pet's hit within one tick before applying anything -
    // see queueDamage/flushDamage. Tracks which pet instances contributed,
    // not just the summed amount, so a kill can grant XP to exactly the
    // pets that fought for it.
    private final Map<UUID, Map<OreCube, PendingDamage>> pendingDamageByPlayer = new ConcurrentHashMap<>();
    // Which players are currently INSIDE a flushDamage() call, for the "play
    // the shared hit sound only once" gate below - flushDamage can genuinely
    // re-enter itself (see its own javadoc: a kill fires OreCubeKilledEvent
    // synchronously, which re-runs the same player's tick and ends in its
    // own nested flushDamage call). Gating the sound on "is queued non-
    // empty" alone plays it once per INVOCATION, not once per real game
    // tick - a nested call during exactly the scenario most likely to
    // involve several pets (a kill) would otherwise double-play it.
    private final Set<UUID> flushingPlayers = ConcurrentHashMap.newKeySet();
    // Every health-bar text_display entity id currently believed spawned for
    // a player - reconciled once per tick() cycle against liveCubes() so a
    // nametag can never outlive its cube for more than one cycle, no matter
    // what caused the desync in the first place. See reconcileHealthBars.
    private final Map<UUID, Set<Integer>> spawnedHealthBarIds = new ConcurrentHashMap<>();
    // Rolling 1-minute "Slaying Summary" chat block - accumulated per kill,
    // flushed and reset once a minute. See queueSummary/flushSummaries.
    private final Map<UUID, WindowStats> pendingSummary = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSummaryAtMillis = new ConcurrentHashMap<>();

    private record WindowStats(long coins, long gems, int kills) {
        WindowStats add(long moreCoins, long moreGems) {
            return new WindowStats(coins + moreCoins, gems + moreGems, kills + 1);
        }
    }

    /** One cube's accumulated damage this tick, and every pet instance that contributed at least one hit - see queueDamage/flushDamage. */
    private record PendingDamage(int amount, Set<UUID> contributingInstanceIds) {
        PendingDamage add(int moreAmount, UUID petInstanceId) {
            Set<UUID> merged = new HashSet<>(contributingInstanceIds);
            merged.add(petInstanceId);
            return new PendingDamage(amount + moreAmount, merged);
        }
    }
    // Defaults to the plain shared-target behavior; YieldZones overrides this
    // once PetCombatController exists, so single-send clicks can send just
    // one pet instead of overwriting the whole squad's target - see setClickHandler.
    private BiConsumer<Player, OreCube> onCubeClicked = this::setTarget;

    /**
     * Keyed, composable payout-modifier registries - same shape/idiom as
     * {@code YieldPacks}' own coin/damage/attack-speed provider maps, just
     * scoped one level deeper (per-block-type, not just per-player) since
     * that's what yield-blocktree's flat/percentage rewards need. Flat
     * providers are summed; the multiplier provider's results are
     * multiplied together, same as every other multiplier in this codebase.
     */
    private final Map<String, BiFunction<PackPlayerProfile, Material, Long>> flatCoinBonusProviders = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<PackPlayerProfile, Material, Long>> flatGemBonusProviders = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<PackPlayerProfile, Material, Double>> blockCoinMultiplierProviders = new ConcurrentHashMap<>();
    /** Global (not block-scoped) - additive on top of the base 5%-times-luck gem-drop roll below. */
    private final Map<String, Function<PackPlayerProfile, Double>> gemChanceBoostProviders = new ConcurrentHashMap<>();
    /** Additive extra concurrent-cube slots on top of a zone's own configured {@code maxConcurrentCubes} - see topUpCubes. */
    private final Map<String, Function<PackPlayerProfile, Integer>> extraCubeCapProviders = new ConcurrentHashMap<>();
    /** Additive boost to every configured {@link CubeBonus}'s own chance (golden/diamond) - see rollBonus. */
    private final Map<String, Function<PackPlayerProfile, Double>> cubeBonusChanceBoostProviders = new ConcurrentHashMap<>();

    public void registerFlatCoinBonusProvider(String key, BiFunction<PackPlayerProfile, Material, Long> provider) {
        flatCoinBonusProviders.put(key, provider);
    }

    public void unregisterFlatCoinBonusProvider(String key) {
        flatCoinBonusProviders.remove(key);
    }

    public void registerFlatGemBonusProvider(String key, BiFunction<PackPlayerProfile, Material, Long> provider) {
        flatGemBonusProviders.put(key, provider);
    }

    public void unregisterFlatGemBonusProvider(String key) {
        flatGemBonusProviders.remove(key);
    }

    public void registerBlockCoinMultiplierProvider(String key, BiFunction<PackPlayerProfile, Material, Double> provider) {
        blockCoinMultiplierProviders.put(key, provider);
    }

    public void unregisterBlockCoinMultiplierProvider(String key) {
        blockCoinMultiplierProviders.remove(key);
    }

    public void registerGemChanceBoostProvider(String key, Function<PackPlayerProfile, Double> provider) {
        gemChanceBoostProviders.put(key, provider);
    }

    public void unregisterGemChanceBoostProvider(String key) {
        gemChanceBoostProviders.remove(key);
    }

    public void registerExtraCubeCapProvider(String key, Function<PackPlayerProfile, Integer> provider) {
        extraCubeCapProviders.put(key, provider);
    }

    public void unregisterExtraCubeCapProvider(String key) {
        extraCubeCapProviders.remove(key);
    }

    public void registerCubeBonusChanceBoostProvider(String key, Function<PackPlayerProfile, Double> provider) {
        cubeBonusChanceBoostProviders.put(key, provider);
    }

    public void unregisterCubeBonusChanceBoostProvider(String key) {
        cubeBonusChanceBoostProviders.remove(key);
    }

    private long flatBonusSum(Map<String, BiFunction<PackPlayerProfile, Material, Long>> providers, PackPlayerProfile profile, Material material) {
        long total = 0;
        for (BiFunction<PackPlayerProfile, Material, Long> provider : providers.values()) {
            total += provider.apply(profile, material);
        }
        return total;
    }

    private double blockCoinMultiplierSum(PackPlayerProfile profile, Material material) {
        double total = 1.0;
        for (BiFunction<PackPlayerProfile, Material, Double> provider : blockCoinMultiplierProviders.values()) {
            total *= provider.apply(profile, material);
        }
        return total;
    }

    private double gemChanceBoostSum(PackPlayerProfile profile) {
        double total = 0.0;
        for (Function<PackPlayerProfile, Double> provider : gemChanceBoostProviders.values()) {
            total += provider.apply(profile);
        }
        return total;
    }

    private int extraCubeCapSum(PackPlayerProfile profile) {
        int total = 0;
        for (Function<PackPlayerProfile, Integer> provider : extraCubeCapProviders.values()) {
            total += provider.apply(profile);
        }
        return total;
    }

    private double cubeBonusChanceBoostSum(PackPlayerProfile profile) {
        double total = 0.0;
        for (Function<PackPlayerProfile, Double> provider : cubeBonusChanceBoostProviders.values()) {
            total += provider.apply(profile);
        }
        return total;
    }

    public OreCubeService(JavaPlugin plugin, Supplier<Map<String, ZoneDefinition>> zones, YieldPacks packs) {
        this.plugin = plugin;
        this.zones = zones;
        this.packs = packs;
        this.luckService = packs.getLuckService();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Overrides what a left-click on a landed cube does - see yield-zones' wiring, which makes this mode-aware. */
    public void setClickHandler(BiConsumer<Player, OreCube> onCubeClicked) {
        this.onCubeClicked = onCubeClicked;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushSummaries, SUMMARY_INTERVAL, SUMMARY_INTERVAL);
        // Its own, much faster loop - the main tick()'s 1-second cadence
        // would make "what am I looking at" feel laggy and behind.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHighlights, HIGHLIGHT_TICK_INTERVAL, HIGHLIGHT_TICK_INTERVAL);
    }

    /** White-outlines whichever live cube a player is currently looking at, clearing it the instant they look away - a bonus cube's own persistent colored glow is left alone rather than fought over. */
    private void tickHighlights() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<OreCube> live = liveCubes(player);
            if (live.isEmpty()) {
                continue;
            }
            OreCube looking = raycastClosest(player, live);
            for (OreCube cube : live) {
                boolean shouldGlow = cube == looking && cube.bonus() == null;
                boolean currentlyGlowing = cube.highlighted();
                if (shouldGlow && !currentlyGlowing) {
                    spawnHighlight(player, cube);
                } else if (!shouldGlow && currentlyGlowing) {
                    despawnHighlight(player, cube);
                }
            }
        }
    }

    /** The closest live cube (by unit-cube AABB) the player's eye direction actually intersects within {@link #HIGHLIGHT_RANGE} - null if none. Same slab-method test {@code FakeBlockClickRegistry} uses for click detection, standalone here since this only ever needs "closest cube", not per-block handler dispatch. */
    private OreCube raycastClosest(Player player, List<OreCube> live) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection();
        OreCube closest = null;
        double closestDistance = HIGHLIGHT_RANGE;
        for (OreCube cube : live) {
            Double distance = intersectDistance(eye, direction, cube.location());
            if (distance != null && distance < closestDistance) {
                closestDistance = distance;
                closest = cube;
            }
        }
        return closest;
    }

    private Double intersectDistance(Location eye, org.bukkit.util.Vector direction, Location blockLoc) {
        double tMin = 0.0;
        double tMax = HIGHLIGHT_RANGE;
        double[] origin = {eye.getX(), eye.getY(), eye.getZ()};
        double[] dir = {direction.getX(), direction.getY(), direction.getZ()};
        double[] boxMin = {blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ()};
        double[] boxMax = {boxMin[0] + 1.0, boxMin[1] + 1.0, boxMin[2] + 1.0};

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1e-9) {
                if (origin[axis] < boxMin[axis] || origin[axis] > boxMax[axis]) {
                    return null;
                }
                continue;
            }
            double t1 = (boxMin[axis] - origin[axis]) / dir[axis];
            double t2 = (boxMax[axis] - origin[axis]) / dir[axis];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return null;
            }
        }
        return tMin;
    }

    private static final String HIGHLIGHT_TEAM_NAME = "cube_highlight_white";

    /**
     * Glows the cube's OWN body entity white, rather than spawning a
     * separate overlay on top of it (the previous approach) - a separate
     * overlay is always full-size and never animated, so it visually
     * covered the body's own hit-reaction shrink animation the entire time
     * a player was looking straight at whatever they were fighting, which
     * is nearly always. Glowing is just a metadata flag any entity can
     * carry, so toggling it directly on the body has no such conflict.
     */
    private void spawnHighlight(Player viewer, OreCube cube) {
        PacketEntityManager.setGlowing(viewer, cube.blockEntityId(), true);

        Scoreboard board = core().getScoreboardManager().scoreboardFor(viewer);
        Team team = board.getTeam(HIGHLIGHT_TEAM_NAME);
        if (team == null) {
            team = board.registerNewTeam(HIGHLIGHT_TEAM_NAME);
            team.color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
        }
        team.addEntry(cube.blockEntityUuid().toString());
        cube.setHighlighted(true);
    }

    private void despawnHighlight(Player viewer, OreCube cube) {
        if (!cube.highlighted()) {
            return;
        }
        PacketEntityManager.setGlowing(viewer, cube.blockEntityId(), false);
        Scoreboard board = core().getScoreboardManager().scoreboardFor(viewer);
        Team team = board.getEntryTeam(cube.blockEntityUuid().toString());
        if (team != null) {
            team.removeEntry(cube.blockEntityUuid().toString());
        }
        cube.setHighlighted(false);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ZoneDefinition zone = findZone(player.getLocation());
            ZoneDefinition previous = currentZone.get(player.getUniqueId());
            if (zone == null) {
                if (previous != null) {
                    leaveZone(player);
                    exitZoneGameMode(player);
                }
                continue;
            }
            if (!zone.equals(previous)) {
                if (previous != null) {
                    leaveZone(player);
                } else {
                    // Only the "no zone -> a zone" transition, not zone-to-
                    // adjacent-zone - already-Adventure players walking
                    // straight from one zone into another shouldn't flicker
                    // back through Survival in between.
                    enterZoneGameMode(player);
                }
                currentZone.put(player.getUniqueId(), zone);
                Bukkit.getPluginManager().callEvent(new ZoneEnteredEvent(player, zone));
            }
            topUpCubes(player, zone);
            reconcileHealthBars(player);
            reconcileCubeBlocks(player);
        }
    }

    // Players this system has personally switched into Adventure mode for
    // standing in a zone - tracked so leaving only ever reverts someone WE
    // put there (a player who's genuinely Creative is never touched either
    // way), and so a non-graceful shutdown mid-session (crash, or a stop
    // that doesn't route through PlayerQuitEvent) can be safely detected
    // and undone on their very next join - see onJoin.
    private final Set<UUID> adventureModeForZone = ConcurrentHashMap.newKeySet();

    private void enterZoneGameMode(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        player.setGameMode(GameMode.ADVENTURE);
        adventureModeForZone.add(player.getUniqueId());
    }

    private void exitZoneGameMode(Player player) {
        if (adventureModeForZone.remove(player.getUniqueId())) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    /**
     * A crash, or any shutdown that doesn't route through {@link
     * #onQuit}, leaves a zone-adventure player's LAST-SAVED gamemode as
     * Adventure - nothing here survives a restart to know they were mid-
     * zone. This system is the only thing that ever puts a player into
     * Adventure mode, so a fresh join already in it is unambiguously a
     * leftover from exactly that scenario, safe to correct unconditionally.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.ADVENTURE) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    /**
     * A live cube's REAL block is only ever supposed to be air - the actual
     * visible body is the packet-only {@code block_display} entity carried
     * on {@link OreCube#blockEntityId()} (see {@code FakeFallingBlock}); the
     * only thing ever painted onto the real block position is an invisible,
     * owner-only fake {@link Material#BARRIER}, purely so vanilla's own
     * client-side block targeting (hover outline, collision) still treats it
     * as something solid. This continuously re-asserts both halves of that
     * invariant - every 4 ticks, for every one of this player's live cubes,
     * force the real block back to air if anything let it become otherwise,
     * and re-send the fake barrier in case that silently dropped. Self-heals
     * within a fraction of a second regardless of cause, the same philosophy
     * {@link #reconcileHealthBars} already uses.
     */
    private void reconcileCubeBlocks(Player player) {
        for (OreCube cube : liveCubes(player)) {
            Location loc = cube.location();
            if (loc.getBlock().getType() != Material.AIR) {
                loc.getBlock().setType(Material.AIR, false);
            }
            player.sendBlockChange(loc, Material.BARRIER.createBlockData());
        }
    }

    /**
     * Force-destroys any health-bar entity this player was sent that no
     * longer corresponds to a live cube - a safety net so a nametag can
     * never visibly outlive its block for more than one tick() cycle
     * (1 second), regardless of how the two drifted apart. {@link
     * #despawnCube} is the normal, immediate cleanup path for every known
     * removal (kill, leaving the zone) - this only ever catches something
     * that path missed.
     */
    private void reconcileHealthBars(Player player) {
        Set<Integer> tracked = spawnedHealthBarIds.get(player.getUniqueId());
        if (tracked == null || tracked.isEmpty()) {
            return;
        }
        Set<Integer> shouldBeAlive = new HashSet<>();
        for (OreCube cube : liveCubes(player)) {
            shouldBeAlive.add(cube.textEntityId());
        }
        tracked.removeIf(id -> {
            if (shouldBeAlive.contains(id)) {
                return false;
            }
            PacketEntityManager.destroyEntity(player, id);
            return true;
        });
    }

    private ZoneDefinition findZone(Location location) {
        for (ZoneDefinition zone : zones.get().values()) {
            if (zone.region().contains(location)) {
                return zone;
            }
        }
        return null;
    }

    private void topUpCubes(Player player, ZoneDefinition zone) {
        UUID id = player.getUniqueId();
        int live = liveCountFor(id)
                + pendingByPlayer.computeIfAbsent(id, k -> new AtomicInteger()).get()
                + scheduledByPlayer.computeIfAbsent(id, k -> new AtomicInteger()).get();
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(id);
        int effectiveCap = zone.maxConcurrentCubes() + extraCubeCapSum(profile);
        if (live < effectiveCap) {
            spawnCubeFor(player, zone);
        }
    }

    private int liveCountFor(UUID playerId) {
        return cubesByPlayer.getOrDefault(playerId, List.of()).size();
    }

    /**
     * Anywhere within the zone's full region, not tied to the triggering
     * player's own position - lets cubes spread across the whole zone
     * instead of clustering around wherever a player happens to be
     * standing. The fall itself always starts exactly {@link
     * #FALL_HEIGHT_BLOCKS} above the zone's OWN configured floor
     * ({@code region.minY()}), and lands exactly at that floor - not
     * derived from real-world terrain at all. An earlier version used
     * {@code getHighestBlockYAt} for both, which happened to work only by
     * accident: the shipped "meadow" zone used to span from y=-64 to y=320
     * with nothing real built inside it, so "the world's highest block in
     * this column" and "this zone's own floor" were coincidentally the same
     * height everywhere. The moment a zone became a real, compact, built
     * area, that stopped holding - a real overhang directly above the
     * zone's footprint (a tree branch, part of a gate structure) reads as
     * "the ground" to {@code getHighestBlockYAt}, so cubes would spawn from
     * (and land at) whatever height that overhang sits at instead of the
     * zone's actual floor - visibly falling from far too high and landing
     * stuck in mid-air. The zone's own configured floor is already known
     * from its config; there's no need to ask the real world at all.
     */
    private static final int SPAWN_COLUMN_RETRY_ATTEMPTS = 30;

    /**
     * Two of this player's own cubes landing on the exact same column used
     * to be a real, frequent occurrence with a small region and a high
     * max-concurrent-cubes (each pick is independently random, so it's a
     * birthday-paradox collision, not a rare fluke) - whichever one died
     * second would send its own AIR revert on top of the position the
     * *other*, still-alive cube was also occupying, wiping its block and
     * (since {@code FakeBlockClickRegistry} keys purely by location) its
     * click registration too, while that other cube's own bookkeeping -
     * health bar included - had no idea any of that happened. The result:
     * an un-clickable cube whose floating HP nametag just floats there
     * forever, since nothing can ever target/kill it again - until the
     * player leaves the zone, which despawns every tracked cube
     * unconditionally regardless of whether it's still clickable. Retrying
     * a fresh random column whenever the roll collides with a column this
     * player already occupies (landed or still falling) fixes this at the
     * source rather than papering over the symptom.
     */
    private void spawnCubeFor(Player player, ZoneDefinition zone) {
        CubeTier tier = rollTier(zone);
        ZoneRegion region = zone.region();
        UUID playerId = player.getUniqueId();
        int x = 0;
        int z = 0;
        for (int attempt = 0; attempt < SPAWN_COLUMN_RETRY_ATTEMPTS; attempt++) {
            x = ThreadLocalRandom.current().nextInt(region.minX(), region.maxX() + 1);
            z = ThreadLocalRandom.current().nextInt(region.minZ(), region.maxZ() + 1);
            if (!isColumnOccupied(playerId, x, z)) {
                break;
            }
        }
        int groundY = region.minY();
        double spawnY = groundY + FALL_HEIGHT_BLOCKS;
        // Block-corner coordinates (no +0.5) - FakeFallingBlock's fall is a
        // client-side block_display now, which (like the health-bar glow
        // overlay's own cube.location() usage) renders its 1x1x1 block model
        // aligned to the grid from its entity position as the corner, not
        // centered the way a real vanilla FallingBlock entity's hitbox is.
        Location spawnAt = new Location(region.world(), x, spawnY, z);

        CubeBonus bonus = rollBonus(zone, packs.getPlayerStore().getOrCreate(playerId));
        pendingByPlayer.computeIfAbsent(playerId, k -> new AtomicInteger()).incrementAndGet();
        long column = packColumn(x, z);
        var blockData = tier.material().createBlockData();
        UUID[] fallId = new UUID[1];
        fallId[0] = core().getFakeFallingBlock().spawn(player, spawnAt, groundY, blockData, blockData,
                (owner, landedAt, blockEntityId, blockEntityUuid) -> {
                    pendingFallIdsByPlayer.getOrDefault(playerId, Set.of()).remove(fallId[0]);
                    Map<UUID, Long> columns = pendingColumnsByFallId.get(playerId);
                    if (columns != null) {
                        columns.remove(fallId[0]);
                    }
                    onLanded(owner, zone, tier, bonus, landedAt, blockEntityId, blockEntityUuid);
                });
        pendingFallIdsByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(fallId[0]);
        pendingColumnsByFallId.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(fallId[0], column);
    }

    private boolean isColumnOccupied(UUID playerId, int x, int z) {
        for (OreCube cube : cubesByPlayer.getOrDefault(playerId, List.of())) {
            if (cube.location().getBlockX() == x && cube.location().getBlockZ() == z) {
                return true;
            }
        }
        Map<UUID, Long> columns = pendingColumnsByFallId.get(playerId);
        return columns != null && columns.containsValue(packColumn(x, z));
    }

    private static long packColumn(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /** Independent of tier - every spawn also rolls each configured bonus's own chance (boosted by any registered CUBE_BONUS_CHANCE upgrades, clamped to 100%); the highest-multiplier one that hits (if any) wins. Null for a plain cube. */
    private CubeBonus rollBonus(ZoneDefinition zone, PackPlayerProfile profile) {
        double boost = cubeBonusChanceBoostSum(profile);
        CubeBonus best = null;
        for (CubeBonus bonus : zone.cubeBonuses()) {
            double chance = Math.min(1.0, bonus.chance() + boost);
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                if (best == null || bonus.multiplier() > best.multiplier()) {
                    best = bonus;
                }
            }
        }
        return best;
    }

    private void onLanded(Player owner, ZoneDefinition zone, CubeTier tier, CubeBonus bonus, Location landedAt, int blockEntityId, UUID blockEntityUuid) {
        pendingByPlayer.computeIfAbsent(owner.getUniqueId(), k -> new AtomicInteger()).decrementAndGet();
        if (!owner.isOnline() || !zone.equals(currentZone.get(owner.getUniqueId()))) {
            owner.sendBlockChange(landedAt, Material.AIR.createBlockData());
            PacketEntityManager.destroyEntity(owner, blockEntityId);
            return;
        }
        int textEntityId = PacketEntityManager.nextEntityId();
        int glowEntityId = -1;
        UUID glowEntityUuid = null;
        if (bonus != null) {
            glowEntityId = PacketEntityManager.nextEntityId();
            glowEntityUuid = UUID.randomUUID();
            spawnGlow(owner, tier, bonus, landedAt, glowEntityId, glowEntityUuid);
        }
        OreCube cube = new OreCube(landedAt, tier, blockEntityId, blockEntityUuid, textEntityId, bonus, glowEntityId, glowEntityUuid);
        // Registration first, health bar last - the block has already
        // physically landed by this point (FakeFallingBlock's own handler
        // already did the sendBlockChange before invoking this callback),
        // so if the health bar display throws for any reason it must not
        // also silently prevent the cube from becoming targetable/
        // attackable - that failure mode is far worse than a missing nametag.
        cubesByPlayer.computeIfAbsent(owner.getUniqueId(), k -> new ArrayList<>()).add(cube);
        FakeBlockClickRegistry.register(owner, landedAt, clicker -> onCubeClicked.accept(clicker, cube));
        spawnHealthBar(owner, cube);
    }

    /**
     * A packet-only, glowing block_display sitting exactly on the bonus
     * cube - the same block model, so it renders as a normal-looking block
     * PLUS vanilla's colored glow outline around it (visible through
     * walls). The outline's color comes from a real scoreboard {@code Team}
     * on the OWNER'S OWN currently-active board (see {@code
     * ScoreboardManager#scoreboardFor} - every player has their own private
     * board for the sidebar, so a team registered on the shared main
     * scoreboard would be invisible to them; only the owner ever needs to
     * see this anyway, since cubes are already a per-viewer illusion).
     */
    private void spawnGlow(Player owner, CubeTier tier, CubeBonus bonus, Location landedAt, int glowEntityId, UUID glowEntityUuid) {
        BlockDisplayManager.spawn(owner, glowEntityId, glowEntityUuid, landedAt);
        BlockDisplayManager.setBlockState(owner, glowEntityId, tier.material());
        BlockDisplayManager.setTransformation(owner, glowEntityId, GLOW_OVERLAY_TRANSLATE, GLOW_OVERLAY_SCALE);
        PacketEntityManager.setGlowing(owner, glowEntityId, true);

        Scoreboard board = core().getScoreboardManager().scoreboardFor(owner);
        String teamName = "cube_glow_" + bonus.color().toString().toLowerCase(Locale.ROOT);
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
            team.color(bonus.color());
        }
        team.addEntry(glowEntityUuid.toString());
    }

    /** Every cube currently live for this player - the pool {@code PetCombatController} picks a target from. */
    public List<OreCube> liveCubes(Player player) {
        return cubesByPlayer.getOrDefault(player.getUniqueId(), List.of());
    }

    public OreCube currentTarget(Player player) {
        return targetByPlayer.get(player.getUniqueId());
    }

    /**
     * Selects {@code cube} as this player's current target - from a
     * left-click, or from auto-targeting. Only manages target state and the
     * boss bar; which pets visually ring around it is a function of the
     * player's attack mode, which only {@code PetCombatController} knows -
     * see its own call to {@code PetDisplayService.setAttackTarget}.
     */
    public void setTarget(Player player, OreCube cube) {
        targetByPlayer.put(player.getUniqueId(), cube);
        updateBossBar(player, cube);
    }

    /** Clears this player's current target and sends their pets back to formation - see the sneak-to-recall gesture. */
    public void clearTarget(Player player) {
        targetByPlayer.remove(player.getUniqueId());
        hideBossBar(player);
        packs.getPetDisplayService().clearAttackTarget(player);
    }

    /** A floating, transparent-background text label above the cube showing its HP - same styling as pet nametags. */
    private void spawnHealthBar(Player viewer, OreCube cube) {
        Location labelPos = cube.location().clone().add(0.5, 1.4, 0.5);
        PacketEntityManager.beginBundle(viewer);
        TextDisplayManager.spawn(viewer, cube.textEntityId(), labelPos);
        TextDisplayManager.setBillboard(viewer, cube.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, cube.textEntityId(), 0x00000000);
        TextDisplayManager.setStyle(viewer, cube.textEntityId(), true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setText(viewer, cube.textEntityId(), healthBarText(cube));
        PacketEntityManager.endBundle(viewer);
        spawnedHealthBarIds.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>()).add(cube.textEntityId());
    }

    /**
     * The single place a cube's block, click registration, and floating
     * health bar are ever torn down - called from every path that removes a
     * cube (killed, or the player leaving the zone) so those three can
     * never drift out of sync with each other.
     */
    private void despawnCube(Player player, OreCube cube) {
        // Clears the highlight team entry (if any) before the body entity
        // itself is destroyed below - despawnHighlight's own setGlowing
        // packet becomes a harmless no-op once that happens, but the team
        // membership cleanup still matters either way.
        despawnHighlight(player, cube);
        player.sendBlockChange(cube.location(), Material.AIR.createBlockData());
        PacketEntityManager.destroyEntity(player, cube.blockEntityId());
        FakeBlockClickRegistry.unregister(player, cube.location());
        PacketEntityManager.destroyEntity(player, cube.textEntityId());
        Set<Integer> tracked = spawnedHealthBarIds.get(player.getUniqueId());
        if (tracked != null) {
            tracked.remove(cube.textEntityId());
        }
        if (cube.bonus() != null) {
            PacketEntityManager.destroyEntity(player, cube.glowEntityId());
            Scoreboard board = core().getScoreboardManager().scoreboardFor(player);
            Team team = board.getEntryTeam(cube.glowEntityUuid().toString());
            if (team != null) {
                team.removeEntry(cube.glowEntityUuid().toString());
            }
        }
    }

    /**
     * Called once per pet's individual hit, on that pet's own attack timer
     * (see {@code PetCombatController}) - accumulates into a per-(player,
     * cube) running total for this tick rather than applying anything
     * immediately. Several equipped pets landing a hit on the same cube in
     * the same tick is the common case (they mostly share an attack-speed
     * cadence), and applying each hit separately meant each one that
     * crossed the cube's death threshold independently re-ran the kill/
     * payout logic - one cube kill paying out N times over, once per
     * contributing pet. Call {@link #flushDamage} once per player per tick,
     * after every pet's hit for that tick has been queued, to apply the
     * combined total exactly once.
     */
    public void queueDamage(Player player, OreCube cube, int amount, UUID petInstanceId) {
        if (amount <= 0) {
            return;
        }
        pendingDamageByPlayer.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(cube, new PendingDamage(amount, Set.of(petInstanceId)),
                        (existing, fresh) -> existing.add(fresh.amount(), petInstanceId));
    }

    /** Applies every hit {@link #queueDamage} accumulated this tick, one combined damage indicator/HP update (and at most one kill/payout) per targeted cube. A no-op if the player has since left the zone entirely. */
    public void flushDamage(Player player) {
        Map<OreCube, PendingDamage> queued = pendingDamageByPlayer.remove(player.getUniqueId());
        if (queued == null || queued.isEmpty()) {
            return;
        }
        ZoneDefinition zone = currentZone.get(player.getUniqueId());
        if (zone == null) {
            return;
        }
        // Both hit sounds are per-PLAYER-tick, not per cube - single-send
        // can have this player's pets spread across several cubes at once
        // (roughly one cube per pet), and playing a sound once per cube in
        // that case is audibly indistinguishable from "once per pet." One
        // shared pair anchored on the player's own location (not any one
        // cube's, so it doesn't favor whichever cube happens to iterate
        // first) covers every cube this flush touches; the particle sparks
        // stay per-cube since those are purely visual, at each cube's own
        // location. Gated on flushingPlayers (not just "queued non-empty")
        // so the OUTERMOST call in a reentrant chain is the only one that
        // plays it - see that field's own javadoc on why a plain per-
        // invocation gate isn't enough.
        boolean isOutermostFlush = flushingPlayers.add(player.getUniqueId());
        if (isOutermostFlush) {
            playHitImpactSound(player, player.getLocation());
            playAttackSound(player, player.getLocation());
        }
        try {
            for (Map.Entry<OreCube, PendingDamage> entry : queued.entrySet()) {
                OreCube cube = entry.getKey();
                int amount = entry.getValue().amount();
                Location center = cube.location().clone().add(0.5, 0.5, 0.5);
                showDamageIndicator(player, center, amount);
                showHitImpact(player, center);
                boolean dead = cube.damage(amount);
                if (dead) {
                    killCube(player, zone, cube, entry.getValue().contributingInstanceIds());
                } else {
                    updateBossBar(player, cube);
                    TextDisplayManager.setText(player, cube.textEntityId(), healthBarText(cube));
                    playHitSquish(player, cube);
                }
            }
        } finally {
            if (isOutermostFlush) {
                flushingPlayers.remove(player.getUniqueId());
            }
        }
    }

    // A hit reaction on the cube's own block_display body (see OreCube#blockEntityId) -
    // shrinks evenly on all 3 axes toward its own center, then glides back
    // to full size. Uniform (not a squash) per the requested "shrink then
    // grow" feel; translate = 0.5 - scale/2 on every axis keeps it centered
    // rather than floor-anchored, so it reads as the whole cube contracting
    // inward and popping back, not flattening into the ground. Timings are
    // short but not instant, so both legs read as a genuine glide rather
    // than a snap even though several hits a second (multiple pets sharing
    // an attack-speed cadence) can retrigger this before the previous one
    // fully finishes - a fresh shrink command simply restarts the client's
    // interpolation from wherever it currently is, so overlapping hits never
    // need any cancellation bookkeeping here.
    private static final int HIT_SHRINK_TICKS = 3;
    private static final int HIT_GROW_TICKS = 6;
    private static final float HIT_SHRINK_SCALE = 0.65f;

    private void playHitSquish(Player viewer, OreCube cube) {
        int entityId = cube.blockEntityId();
        float t = 0.5f - HIT_SHRINK_SCALE / 2f;
        BlockDisplayManager.setInterpolation(viewer, entityId, 0, HIT_SHRINK_TICKS, HIT_SHRINK_TICKS);
        BlockDisplayManager.setTransformation(viewer, entityId,
                new Vector3f(t, t, t), new Vector3f(HIT_SHRINK_SCALE, HIT_SHRINK_SCALE, HIT_SHRINK_SCALE));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            BlockDisplayManager.setInterpolation(viewer, entityId, 0, HIT_GROW_TICKS, HIT_GROW_TICKS);
            BlockDisplayManager.setTransformation(viewer, entityId, 0f, 1f);
        }, HIT_SHRINK_TICKS);
    }

    /** A small spark at the moment of impact - purely visual, per cube (each cube hit this tick gets its own spark at its own location). The "thwack" sound is a separate, once-per-flush call - see {@link #flushDamage}. */
    private void showHitImpact(Player viewer, Location center) {
        viewer.spawnParticle(Particle.CRIT, center, 6, 0.2, 0.2, 0.2, 0.05);
    }

    /** The impact "thwack" - deliberately separate from {@link #showHitImpact}'s particle, and from {@link #playAttackSound}'s own squish, so {@link #flushDamage} can gate each of this method's own single call per player-tick without touching the per-cube visuals. */
    private void playHitImpactSound(Player viewer, Location at) {
        viewer.playSound(at, Sound.BLOCK_STONE_HIT, 0.5f, 1.2f);
    }

    /** Once per targeted cube per tick - not once per contributing pet, even though several pets landing a hit on the same cube in the same tick is the common case. */
    private void playAttackSound(Player viewer, Location center) {
        float pitch = 1.2f + ThreadLocalRandom.current().nextFloat() * 0.2f;
        viewer.playSound(center, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.35f, pitch);
    }

    /**
     * A distinct, immediate flourish for a single crit roll (see {@code
     * PetCombatController#applyDamage}) - separate from the per-tick
     * aggregated damage number (which just shows a bigger total when a
     * crit contributed to it, with nothing marking it as one), so a crit
     * always gets its own unmistakable "CRIT!" moment rather than blending
     * into a slightly-larger regular number.
     */
    public void playCritFlourish(Player player, OreCube target) {
        Location center = target.location().clone().add(0.5, 0.5, 0.5);
        Component text = Text.parse("<gradient:#FF5555:#FFAA00><bold>CRIT!</bold></gradient>");
        spawnFloatingText(player, center, text, 10, 16);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.5f, 1f);
        player.spawnParticle(Particle.CRIT, center, 12, 0.25, 0.25, 0.25, 0.3);
    }

    /** "-<amount>" in red, floating up from a randomized spot near the cube so simultaneous hits from several pets don't overlap. */
    private void showDamageIndicator(Player viewer, Location center, int amount) {
        Component text = Text.parse("<#FF3B3B>-<amount></#FF3B3B>", Placeholder.unparsed("amount", Formatting.format(amount)));
        spawnFloatingText(viewer, center, text, DAMAGE_INDICATOR_RISE_TICKS, DAMAGE_INDICATOR_LIFETIME_TICKS);
    }

    /** "+<coins> coins" (and "+<gems> gems" only if any were earned), floating up from the cube on a kill - a combo of 2+ gets its own line, right where the player is already looking. */
    private void showEarningsIndicator(Player viewer, Location center, long coins, int gemsEarned, int combo) {
        Component text = Text.parse("<#55FF7F>+<coins> coins</#55FF7F>", Placeholder.unparsed("coins", Formatting.format(coins)));
        if (gemsEarned > 0) {
            text = text.append(Component.newline())
                    .append(Text.parse("<#55FFFF>+<gems> gems</#55FFFF>", Placeholder.unparsed("gems", Formatting.format(gemsEarned))));
        }
        if (combo > 1) {
            text = text.append(Component.newline())
                    .append(Text.parse("<#FFAA00><bold>x<combo> COMBO!</bold></#FFAA00>", Placeholder.unparsed("combo", String.valueOf(combo))));
        }
        spawnFloatingText(viewer, center, text, EARNINGS_INDICATOR_RISE_TICKS, EARNINGS_INDICATOR_LIFETIME_TICKS);
    }

    /**
     * A real milestone flourish (distinct, escalating sound + particles)
     * every 10/25/50/100/250/500/1000 kills in one streak - the combo
     * counter itself is quiet the rest of the time (just the small floating
     * line above), this is the "big moment" on top of the constant small
     * one. No title anymore (removed by design - it covered too much of the
     * screen for something that fires this often); the sound/particles
     * alone still make a big streak feel distinct.
     */
    private void announceCombo(Player player, Location center, ComboService.ComboResult combo) {
        if (!combo.milestone()) {
            return;
        }
        // Pitch climbs with the milestone tier itself (10 -> lowest, 1000 ->
        // highest) so a huge streak audibly sounds bigger, not just a
        // repeat of the same triumphant blip every time.
        float pitch = (float) Math.min(2.0, 0.8 + Math.log10(combo.count()) * 0.4);
        player.playSound(player.getLocation(), Sound.ENTITY_PUFFER_FISH_STING, 1f, pitch);
        player.spawnParticle(Particle.FIREWORK, center, 20, 0.4, 0.4, 0.4, 0.08);
    }

    /** A short-lived, randomly-offset text_display that glides upward from {@code center} then despawns - the shared mechanic behind both indicators above. */
    private void spawnFloatingText(Player viewer, Location center, Component text, int riseTicks, int lifetimeTicks) {
        int entityId = PacketEntityManager.nextEntityId();
        double dx = ThreadLocalRandom.current().nextDouble(-0.4, 0.4);
        double dz = ThreadLocalRandom.current().nextDouble(-0.4, 0.4);
        Location spawnAt = center.clone().add(dx, 0.2, dz);

        PacketEntityManager.beginBundle(viewer);
        TextDisplayManager.spawn(viewer, entityId, spawnAt);
        TextDisplayManager.setBillboard(viewer, entityId, TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, entityId, 0x00000000);
        TextDisplayManager.setStyle(viewer, entityId, true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setScale(viewer, entityId, 0.9f, 0.9f, 0.9f);
        TextDisplayManager.setText(viewer, entityId, text);
        TextDisplayManager.setInterpolation(viewer, entityId, 0, riseTicks, riseTicks);
        PacketEntityManager.endBundle(viewer);

        PacketEntityManager.teleportEntity(viewer, entityId, spawnAt.clone().add(0, 0.9, 0));
        Bukkit.getScheduler().runTaskLater(plugin, () -> PacketEntityManager.destroyEntity(viewer, entityId), lifetimeTicks);
    }

    /**
     * Removing {@code cube} from {@code cubesByPlayer} FIRST, and bailing
     * out if it wasn't there to remove, is what makes this method properly
     * idempotent no matter how many times it's reached for the same cube -
     * necessary because {@link #flushDamage} can genuinely re-enter itself:
     * a kill fires {@link OreCubeKilledEvent} synchronously, which
     * {@code PetCombatController} handles by immediately re-running this
     * same player's tick (see that class's own Javadoc on why), which ends
     * in its own {@code flushDamage} call - all while the OUTER
     * {@code flushDamage} call that triggered this kill is still iterating
     * its own (now-stale) batch of queued damage. If that outer batch also
     * contained a second cube that the reentrant inner call independently
     * happens to also kill first, the outer loop would otherwise reach that
     * same already-dead cube's leftover entry and call this a second time -
     * double `despawnCube` (a stray extra destroy-entity packet, and an
     * unregister that could rip out a brand-new cube's click handler if one
     * has already spawned at the same block position), a double payout, an
     * incorrectly-cleared boss bar, and two respawns scheduled for one
     * actual kill. Spamming clicks/attacks makes this far more likely to
     * actually surface than it would under normal play.
     */
    private void killCube(Player player, ZoneDefinition zone, OreCube cube, Set<UUID> contributingInstanceIds) {
        List<OreCube> cubes = cubesByPlayer.get(player.getUniqueId());
        if (cubes == null || !cubes.remove(cube)) {
            return;
        }
        Location at = cube.location();
        Location center = at.clone().add(0.5, 0.5, 0.5);
        player.spawnParticle(Particle.BLOCK, center, 40, 0.35, 0.35, 0.35, 0.15, cube.tier().material().createBlockData());
        player.playSound(at, Sound.BLOCK_STONE_BREAK, 1f, 1f);
        despawnCube(player, cube);
        targetByPlayer.remove(player.getUniqueId(), cube);
        hideBossBar(player);

        payOut(player, cube.tier(), cube.bonus(), at.clone().add(0.5, 0.5, 0.5), contributingInstanceIds);
        // Deliberately doesn't touch the pet-display attack override here -
        // in single-send mode other pets may still be fighting different,
        // still-live cubes. PetCombatController's own next tick (a few
        // hundred ms away at most) re-derives the correct visual state from
        // liveCubes()/currentTarget() either way.

        scheduledByPlayer.computeIfAbsent(player.getUniqueId(), k -> new AtomicInteger()).incrementAndGet();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduledByPlayer.computeIfAbsent(player.getUniqueId(), k -> new AtomicInteger()).decrementAndGet();
            if (player.isOnline() && zone.equals(currentZone.get(player.getUniqueId()))) {
                spawnCubeFor(player, zone);
            }
        }, Math.max(1L, zone.respawnDelayMillis() / 50L));
    }

    private void payOut(Player player, CubeTier tier, CubeBonus bonus, Location cubeCenter, Set<UUID> contributingInstanceIds) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        List<PetInstance> contributors = contributingInstanceIds.stream()
                .map(profile::findPet).flatMap(Optional::stream).toList();
        var leveling = packs.getPetLevelingService();
        double bonusMultiplier = bonus != null ? bonus.multiplier() : 1.0;

        ComboService.ComboResult combo = comboService.recordKill(player.getUniqueId());
        double comboMultiplier = comboService.bonusMultiplier(combo.count());

        double earningsBonus = leveling.earningsBonusFor(contributors);
        long coins = Math.round(tier.coinValue() * packs.coinMultiplier(profile) * blockCoinMultiplierSum(profile, tier.material())
                * (1 + earningsBonus) * bonusMultiplier * comboMultiplier) + flatBonusSum(flatCoinBonusProviders, profile, tier.material());
        profile.setCoins(profile.getCoins().add(BigInteger.valueOf(coins)));

        boolean guaranteedGem = contributors.stream().anyMatch(pet -> leveling.hasMilestone(pet, MilestoneEffect.GUARANTEED_GEM_DROP));
        // The Glittering Unique pet-enchant (see PetEnchantService#hasBonusGemDropEnchant) isn't
        // an outright guarantee like the milestone above - a hefty flat chance bump instead,
        // matching its own "bonus chance" framing rather than "always."
        boolean hasGlittering = packs.getPetEnchantService().hasBonusGemDropEnchant(contributors);
        double luck = luckService.totalLuckMultiplier(profile);
        double gemChance = 0.05 * luck + gemChanceBoostSum(profile) + (hasGlittering ? 0.5 : 0.0);
        int gemsEarned = guaranteedGem || ThreadLocalRandom.current().nextDouble() < gemChance ? 1 : 0;
        gemsEarned += (int) flatBonusSum(flatGemBonusProviders, profile, tier.material());
        if (gemsEarned > 0) {
            gemsEarned = (int) Math.round(gemsEarned * packs.gemMultiplier(profile));
            profile.setGems(profile.getGems().add(BigInteger.valueOf(gemsEarned)));
        }
        profile.setLifetimeCubeKills(profile.getLifetimeCubeKills() + 1);
        profile.setLifetimeCoinsEarned(profile.getLifetimeCoinsEarned().add(BigInteger.valueOf(coins)));
        packs.getPlayerStore().save(player.getUniqueId());
        packs.getMasteryService().grantXp(player, MasteryType.COMBAT, 1);
        long petXpAmount = Math.round(tier.xpValue() * bonusMultiplier);
        leveling.grantKillXp(player, contributingInstanceIds, petXpAmount);
        for (UUID contributorId : contributingInstanceIds) {
            packs.getPetDisplayService().showXpGain(player, contributorId, petXpAmount);
        }
        giveCandyDrops(player, luck);
        showEarningsIndicator(player, cubeCenter, coins, gemsEarned, combo.count());
        announceCombo(player, cubeCenter, combo);
        queueSummary(player, coins, gemsEarned);
        Bukkit.getPluginManager().callEvent(new OreCubeKilledEvent(player, tier, coins, gemsEarned));
        // Refresh the sidebar immediately - coins/gems/level/damage all just
        // changed, and waiting up to a second for the periodic tick makes
        // the payout feel laggy rather than instant.
        core().getScoreboardDisplay().refresh(player);
    }

    /** Placeholder candy source for this pass (see Candy's own Javadoc) - a luck-modified roll per configured candy type on every kill. Overflow past a full inventory drops at the player's feet rather than vanishing. */
    private void giveCandyDrops(Player player, double luck) {
        for (Candy c : packs.getPetLevelingService().rollCandyDrops(luck)) {
            ItemStack item = packs.getPetLevelingService().createCandyItem(c);
            player.getInventory().addItem(item).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            player.sendMessage(Text.parse("<#FFB6E1>You found some <name>!</#FFB6E1>",
                    Placeholder.unparsed("name", c.displayName())));
        }
    }

    /** Accumulates into the rolling 1-minute "Slaying Summary" chat block instead of messaging per-kill - see {@link #flushSummaries}. */
    private void queueSummary(Player player, long coins, int gems) {
        pendingSummary.merge(player.getUniqueId(), new WindowStats(coins, gems, 1),
                (existing, fresh) -> existing.add(fresh.coins(), fresh.gems()));
    }

    /** Runs once per minute - sends a combined summary only to players who earned something, then resets their window. */
    private void flushSummaries() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, WindowStats> entry : Map.copyOf(pendingSummary).entrySet()) {
            UUID id = entry.getKey();
            pendingSummary.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            long lastAt = lastSummaryAtMillis.getOrDefault(id, now);
            long elapsedMinutes = Math.max(1, Math.round((now - lastAt) / 60_000.0));
            lastSummaryAtMillis.put(id, now);
            WindowStats stats = entry.getValue();
            player.sendMessage(Text.parse(
                    "<red><bold>SLAYING SUMMARY</bold></red>  <gray>last <minutes>m</gray>\n" +
                            "<dark_gray>EARNINGS</dark_gray>\n" +
                            "<gray>│</gray> <gold>Gold: <yellow><coins></yellow></gold>\n" +
                            "<gray>│</gray> <aqua>Gems: <white><gems></white></aqua>\n" +
                            "<gray>│</gray> <red>Kills: <white>x<kills></white></red>",
                    Placeholder.unparsed("minutes", String.valueOf(elapsedMinutes)),
                    Placeholder.unparsed("coins", Formatting.format(stats.coins())),
                    Placeholder.unparsed("gems", Formatting.format(stats.gems())),
                    Placeholder.unparsed("kills", Formatting.format(stats.kills()))));
        }
    }

    private void updateBossBar(Player player, OreCube cube) {
        float progress = Math.max(0f, Math.min(1f, cube.currentHp() / (float) cube.tier().maxHp()));
        // The boss bar HUD is single-line - the bonus label (if any) only
        // goes on the floating nametag (see healthBarText), which text_display
        // actually renders as separate lines.
        Component title = cube.bonus() == null ? hpBarLine(cube) : bonusLabel(cube).append(Component.text(" ")).append(hpBarLine(cube));
        BossBar bar = bossBarByPlayer.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bossBarByPlayer.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
    }

    private static final int HP_BAR_SEGMENTS = 10;

    /**
     * The floating nametag above a cube - the bonus label (if any) on its
     * own line, then the bar, then a dark-red heart with the current HP
     * next to it in a lighter red (no max HP, no "HP" label - the bar
     * itself already conveys "how full", so the number is just the raw
     * current health).
     */
    private Component healthBarText(OreCube cube) {
        Component hpLines = hpBar(cube).append(Component.newline()).append(heartLine(cube));
        return cube.bonus() == null ? hpLines : bonusLabel(cube).append(Component.newline()).append(hpLines);
    }

    /**
     * Just the colored segment bar, no trailing text - shared by the boss
     * bar (which appends its own "/max HP" text via {@link #hpBarLine}) and
     * the floating nametag (which appends {@link #heartLine} instead).
     * <p>
     * A space has no glyph, so a color alone paints nothing - it's the
     * strikethrough drawn through that space's width that actually shows
     * up, which is what makes a run of them read as a solid bar. The
     * color+strikethrough is also repeated before every single space rather
     * than once before the whole segment, since the client doesn't always
     * draw a whole run under one set of codes.
     */
    private Component hpBar(OreCube cube) {
        double ratio = cube.tier().maxHp() <= 0 ? 0
                : Math.max(0, Math.min(1.0, cube.currentHp() / (double) cube.tier().maxHp()));
        int filled = (int) Math.round(ratio * HP_BAR_SEGMENTS);

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < HP_BAR_SEGMENTS; i++) {
            bar.append(i < filled ? "<green><st> </st></green>" : "<gray><st> </st></gray>");
        }
        return Text.parse(bar.toString());
    }

    /** The boss bar's own single-line title - still "<bar> <hp>/<max> HP", unlike the floating nametag's separate heart+number line. */
    private Component hpBarLine(OreCube cube) {
        return hpBar(cube).append(Text.parse("<white> <hp>/<max> HP</white>",
                Placeholder.unparsed("hp", Formatting.format((double) cube.currentHp())),
                Placeholder.unparsed("max", Formatting.format((double) cube.tier().maxHp()))));
    }

    /** "❤ <currentHp>" - dark red heart, lighter red number - the nametag's own second line, see {@link #healthBarText}. */
    private Component heartLine(OreCube cube) {
        return Text.parse("<dark_red>❤</dark_red> <red><hp></red>",
                Placeholder.unparsed("hp", Formatting.format((double) cube.currentHp())));
    }

    /** "GOLDEN x2" (small-caps, the bonus's own glow color) - shown above the HP bar on the floating nametag, and inline before it on the boss bar. */
    private Component bonusLabel(OreCube cube) {
        String colorName = cube.bonus().color().toString();
        double multiplier = cube.bonus().multiplier();
        String multText = multiplier == Math.rint(multiplier) ? String.valueOf((long) multiplier) : String.valueOf(multiplier);
        return Text.parse("<" + colorName + "><bold><name> x<mult></bold></" + colorName + ">",
                Placeholder.unparsed("name", Formatting.fancyFont(cube.bonus().id())),
                Placeholder.unparsed("mult", multText));
    }

    private void hideBossBar(Player player) {
        BossBar bar = bossBarByPlayer.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private void leaveZone(Player player) {
        currentZone.remove(player.getUniqueId());
        cancelPendingFalls(player.getUniqueId());
        List<OreCube> cubes = cubesByPlayer.remove(player.getUniqueId());
        if (cubes != null) {
            for (OreCube cube : cubes) {
                despawnCube(player, cube);
            }
        }
        targetByPlayer.remove(player.getUniqueId());
        hideBossBar(player);
        packs.getPetDisplayService().clearAttackTarget(player);
    }

    /** Stops every fall this player has in flight right now, instead of leaving each to finish its drop and only self-correct once it actually lands - see {@link #pendingFallIdsByPlayer}. */
    private void cancelPendingFalls(UUID playerId) {
        Set<UUID> fallIds = pendingFallIdsByPlayer.remove(playerId);
        if (fallIds == null) {
            return;
        }
        for (UUID fallId : fallIds) {
            core().getFakeFallingBlock().cancel(fallId);
        }
        pendingByPlayer.remove(playerId);
        pendingColumnsByFallId.remove(playerId);
    }

    /**
     * A landed cube's fake barrier (see {@link #reconcileCubeBlocks}) is
     * only ever painted onto the client via {@code sendBlockChange} - it
     * isn't re-sent on its own schedule beyond that 4-tick reconcile sweep.
     * Whenever the server re-syncs a chunk to this player for any reason
     * (they walked far enough to re-trigger it, teleported via /back,
     * /tpa, or /fasttravel, or simply reconnected), the vanilla chunk data
     * the server sends back contains no barrier at all, silently reverting
     * that block to real (air) terrain - losing the click/hover/collision
     * illusion until the next reconcile pass catches it. The cube's actual
     * visible body (the block_display entity) is unaffected either way,
     * since client-side entities aren't tied to chunk data - only the
     * invisible barrier needs re-painting here, immediately rather than
     * waiting up to 4 ticks for it.
     */
    @EventHandler
    public void onChunkLoad(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        List<OreCube> live = cubesByPlayer.get(player.getUniqueId());
        if (live == null || live.isEmpty()) {
            return;
        }
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (OreCube cube : live) {
            Location loc = cube.location();
            if (loc.getWorld().equals(event.getChunk().getWorld())
                    && (loc.getBlockX() >> 4) == chunkX && (loc.getBlockZ() >> 4) == chunkZ) {
                player.sendBlockChange(loc, Material.BARRIER.createBlockData());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Also covers a kick - Paper routes that through this same event -
        // so their saved gamemode is Survival again before they're gone,
        // not left stuck in Adventure until the onJoin safety net corrects
        // it on their next login.
        exitZoneGameMode(player);
        currentZone.remove(player.getUniqueId());
        cancelPendingFalls(player.getUniqueId());
        cubesByPlayer.remove(player.getUniqueId());
        pendingByPlayer.remove(player.getUniqueId());
        scheduledByPlayer.remove(player.getUniqueId());
        comboService.clear(player.getUniqueId());
        targetByPlayer.remove(player.getUniqueId());
        bossBarByPlayer.remove(player.getUniqueId());
        pendingDamageByPlayer.remove(player.getUniqueId());
        spawnedHealthBarIds.remove(player.getUniqueId());
        pendingSummary.remove(player.getUniqueId());
        lastSummaryAtMillis.remove(player.getUniqueId());
    }

    private CubeTier rollTier(ZoneDefinition zone) {
        double total = zone.cubeTiers().stream().mapToDouble(CubeTier::weight).sum();
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double cumulative = 0;
        for (CubeTier tier : zone.cubeTiers()) {
            cumulative += tier.weight();
            if (roll < cumulative) {
                return tier;
            }
        }
        return zone.cubeTiers().get(zone.cubeTiers().size() - 1);
    }

    private YieldCore core() {
        return JavaPlugin.getPlugin(YieldCore.class);
    }
}
