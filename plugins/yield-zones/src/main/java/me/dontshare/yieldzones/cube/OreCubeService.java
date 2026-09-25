package me.dontshare.yieldzones.cube;

import com.github.retrooper.packetevents.util.Vector3f;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.fakeblock.FakeBlockClickRegistry;
import me.dontshare.yieldcore.math.WeightedRandom;
import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.leveling.Candy;
import me.dontshare.yieldpacks.leveling.MilestoneEffect;
import me.dontshare.yieldpacks.mastery.MasteryType;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzones.combo.ComboService;
import me.dontshare.yieldzones.data.CubeBonus;
import me.dontshare.yieldzones.data.CubeTier;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.data.ZoneRegion;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import me.dontshare.yieldzones.event.ZoneEnteredEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundGroup;
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
import org.bukkit.util.BoundingBox;

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
    /** {@link #tick()} passes between drift reconciles - 5 x 4 ticks, so once a second. */
    private static final int RECONCILE_EVERY_N_CYCLES = 5;
    private static final long SUMMARY_INTERVAL = 20L * 60; // 1 minute
    private static final long HIGHLIGHT_TICK_INTERVAL = 2L; // 0.1s - see tickHighlights
    // Effectively unbounded, same as FakeBlockClickRegistry's own click
    // range - liveCubes() already only ever contains cubes in the zone the
    // player is currently standing in, so that zone's own size is the real
    // limit on how far a highlight (or a click-to-target) can reach, not
    // this constant.
    private static final double HIGHLIGHT_RANGE = 512.0;
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
    /** The cube a tap flush already labelled with its exact number - see {@link #applyTap}. */
    private final Map<UUID, OreCube> quietIndicatorFor = new ConcurrentHashMap<>();
    private final LootDropService lootDrops;
    private static final long HIT_SOUND_GAP_MILLIS = 120L;
    private final Map<UUID, Long> lastHitSoundAt = new ConcurrentHashMap<>();
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
    private final Map<UUID, Map<UUID, PendingColumn>> pendingColumnsByFallId = new ConcurrentHashMap<>();

    /** Where a still-falling cube will land, and whether it is a giant one that needs room around it - see {@link #isColumnOccupied}. */
    private record PendingColumn(int x, int z, boolean giant) {
    }
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

    private record WindowStats(long coins, long diamonds, int kills) {
        WindowStats add(long moreCoins, long moreDiamonds) {
            return new WindowStats(coins + moreCoins, diamonds + moreDiamonds, kills + 1);
        }
    }

    /**
     * One cube's accumulated damage this tick, and every pet instance that
     * contributed at least one hit - see queueDamage/flushDamage. A tap
     * adds damage with no pet behind it (a null id), so it never earns a
     * pet kill XP it didn't fight for. {@code crit} is whether any of
     * those hits was a crit, so the one combined number can say so.
     */
    private record PendingDamage(long amount, Set<UUID> contributingInstanceIds, boolean crit) {
        PendingDamage add(long moreAmount, UUID petInstanceId, boolean moreCrit) {
            Set<UUID> merged = new HashSet<>(contributingInstanceIds);
            if (petInstanceId != null) {
                merged.add(petInstanceId);
            }
            return new PendingDamage(amount + moreAmount, merged, crit || moreCrit);
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
    private final Map<String, BiFunction<PackPlayerProfile, Material, Long>> flatDiamondBonusProviders = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<PackPlayerProfile, Material, Double>> blockCoinMultiplierProviders = new ConcurrentHashMap<>();
    /** Global (not block-scoped) - additive on top of the base 5%-times-luck diamond-drop roll below. */
    private final Map<String, Function<PackPlayerProfile, Double>> diamondChanceBoostProviders = new ConcurrentHashMap<>();
    /** Additive extra concurrent-cube slots on top of a zone's own configured {@code maxConcurrentCubes} - see topUpCubes. */
    private final Map<String, Function<PackPlayerProfile, Integer>> extraCubeCapProviders = new ConcurrentHashMap<>();
    /** Additive boost to every configured {@link CubeBonus}'s own chance (golden/diamond) - see rollBonus. */
    private final Map<String, Function<PackPlayerProfile, Double>> cubeBonusChanceBoostProviders = new ConcurrentHashMap<>();
    /** Per-tier spawn weight factors (e.g. "treasure chests twice as often") - multiplied together, 1.0 = unchanged. */
    private final Map<String, BiFunction<PackPlayerProfile, CubeTier, Double>> spawnWeightMultiplierProviders = new ConcurrentHashMap<>();
    /** Factors on a zone's respawn delay - multiplied together, below 1.0 respawns faster. */
    private final Map<String, Function<PackPlayerProfile, Double>> respawnDelayMultiplierProviders = new ConcurrentHashMap<>();

    public void registerFlatCoinBonusProvider(String key, BiFunction<PackPlayerProfile, Material, Long> provider) {
        flatCoinBonusProviders.put(key, provider);
    }

    public void unregisterFlatCoinBonusProvider(String key) {
        flatCoinBonusProviders.remove(key);
    }

    public void registerFlatDiamondBonusProvider(String key, BiFunction<PackPlayerProfile, Material, Long> provider) {
        flatDiamondBonusProviders.put(key, provider);
    }

    public void unregisterFlatDiamondBonusProvider(String key) {
        flatDiamondBonusProviders.remove(key);
    }

    public void registerBlockCoinMultiplierProvider(String key, BiFunction<PackPlayerProfile, Material, Double> provider) {
        blockCoinMultiplierProviders.put(key, provider);
    }

    public void unregisterBlockCoinMultiplierProvider(String key) {
        blockCoinMultiplierProviders.remove(key);
    }

    public void registerDiamondChanceBoostProvider(String key, Function<PackPlayerProfile, Double> provider) {
        diamondChanceBoostProviders.put(key, provider);
    }

    public void unregisterDiamondChanceBoostProvider(String key) {
        diamondChanceBoostProviders.remove(key);
    }

    public void registerSpawnWeightMultiplierProvider(String key, BiFunction<PackPlayerProfile, CubeTier, Double> provider) {
        spawnWeightMultiplierProviders.put(key, provider);
    }

    public void unregisterSpawnWeightMultiplierProvider(String key) {
        spawnWeightMultiplierProviders.remove(key);
    }

    public void registerRespawnDelayMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        respawnDelayMultiplierProviders.put(key, provider);
    }

    public void unregisterRespawnDelayMultiplierProvider(String key) {
        respawnDelayMultiplierProviders.remove(key);
    }

    private double respawnDelayMultiplier(UUID playerId) {
        PackPlayerProfile profile = packs.getPlayerStore().getCached(playerId);
        double factor = 1.0;
        if (profile != null) {
            for (Function<PackPlayerProfile, Double> provider : respawnDelayMultiplierProviders.values()) {
                Double value = provider.apply(profile);
                if (value != null) {
                    factor *= Math.max(0.0, value);
                }
            }
        }
        return factor;
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

    private double diamondChanceBoostSum(PackPlayerProfile profile) {
        double total = 0.0;
        for (Function<PackPlayerProfile, Double> provider : diamondChanceBoostProviders.values()) {
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
        this.lootDrops = new LootDropService(plugin, packs);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** The coin/diamond drops cubes spill - register magnet range providers here. */
    public LootDropService getLootDrops() {
        return lootDrops;
    }

    /** Overrides what a left-click on a landed cube does - see yield-zones' wiring, which makes this mode-aware. */
    public void setClickHandler(BiConsumer<Player, OreCube> onCubeClicked) {
        this.onCubeClicked = onCubeClicked;
    }

    public void start() {
        lootDrops.start();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushSummaries, SUMMARY_INTERVAL, SUMMARY_INTERVAL);
        // Its own, much faster loop - the main tick()'s 1-second cadence
        // would make "what am I looking at" feel laggy and behind.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHighlights, HIGHLIGHT_TICK_INTERVAL, HIGHLIGHT_TICK_INTERVAL);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickRainbows, 3L, 3L);
        // One sweeper for every short visual follow-up - see deferredByTick.
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweepDeferred, 1L, 1L);
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

    /**
     * The closest live cube the player's eye direction actually intersects
     * within {@link #HIGHLIGHT_RANGE} - null if none. Asks
     * {@link FakeBlockClickRegistry#intersectDistance} with the cube's own
     * size, so what lights up when you look at it is exactly what a click
     * would hit - a big safe included.
     */
    /** How far along this player's look the nearest of their own cubes is, or null if they aren't aiming at one (within {@code range}). */
    public Double aimedCubeDistance(Player player, double range) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection();
        Double best = null;
        for (OreCube cube : liveCubes(player)) {
            Location at = cube.location();
            Double distance = FakeBlockClickRegistry.intersectDistance(eye, direction,
                    at.getBlockX(), at.getBlockY(), at.getBlockZ(), cube.size(), range);
            if (distance != null && (best == null || distance < best)) {
                best = distance;
            }
        }
        return best;
    }

    private OreCube raycastClosest(Player player, List<OreCube> live) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection();
        OreCube closest = null;
        double closestDistance = HIGHLIGHT_RANGE;
        for (OreCube cube : live) {
            Location at = cube.location();
            Double distance = FakeBlockClickRegistry.intersectDistance(eye, direction,
                    at.getBlockX(), at.getBlockY(), at.getBlockZ(), cube.size(), HIGHLIGHT_RANGE);
            if (distance != null && distance < closestDistance) {
                closestDistance = distance;
                closest = cube;
            }
        }
        return closest;
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

    /** Counts {@link #tick()} passes so the reconciles below can run on their own, slower cadence. */
    private int tickCycle;

    private void tick() {
        boolean reconcileThisCycle = ++tickCycle % RECONCILE_EVERY_N_CYCLES == 0;
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
            // Both reconciles are safety nets for drift that the normal
            // spawn/despawn paths already handle, so they run once a second
            // rather than on every one of this loop's five-per-second passes -
            // which is the guarantee reconcileHealthBars documents anyway.
            // At the faster cadence the block reconcile alone was re-sending a
            // block change per live cube per player five times a second to
            // correct something that is almost never wrong.
            if (reconcileThisCycle) {
                reconcileHealthBars(player);
                reconcileCubeBlocks(player);
            }
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
            player.sendBlockChange(loc, BARRIER_DATA);
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
     * No cube may be chosen within this many blocks of its owner - the same
     * idea as vanilla never spawning a hostile mob right next to a player.
     * Measured horizontally from the player to the NEAREST edge of the
     * cube's footprint, so a 2x2x2 boss block keeps the same gap from you
     * as a one-block stone cube does, rather than 5 blocks from a centre
     * that is already a block closer.
     */
    private static final double MIN_SPAWN_DISTANCE = 5.0;

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
        CubeTier tier = rollTier(zone, packs.getPlayerStore().getCached(player.getUniqueId()));
        ZoneRegion region = zone.region();
        UUID playerId = player.getUniqueId();
        int x = 0;
        int z = 0;
        // A giant hangs past its own column on every side (half a block for
        // a 2x2x2), so it keeps one column in from the zone's edge rather
        // than overhanging into whatever borders it. A zone too narrow for
        // that falls back to the full width.
        int inset = tier.giant() && region.maxX() - region.minX() >= 2 && region.maxZ() - region.minZ() >= 2 ? 1 : 0;
        boolean found = false;
        for (int attempt = 0; attempt < SPAWN_COLUMN_RETRY_ATTEMPTS && !found; attempt++) {
            x = ThreadLocalRandom.current().nextInt(region.minX() + inset, region.maxX() - inset + 1);
            z = ThreadLocalRandom.current().nextInt(region.minZ() + inset, region.maxZ() - inset + 1);
            found = !isColumnOccupied(playerId, x, z, tier.giant()) && !tooCloseToOwner(player, x, z, tier.size());
        }
        if (!found) {
            // Never fall back to a bad column. This used to spawn on the
            // last roll anyway, which is how two cubes ended up sharing a
            // column; now it would also be how one landed on the player.
            // Skipping is safe: the cube count is still short, so the
            // top-up in tick() tries again four ticks later, by which time
            // the player has usually moved.
            return;
        }
        int groundY = region.minY();
        double spawnY = groundY + FALL_HEIGHT_BLOCKS;
        // Block-corner coordinates (no +0.5) - FakeFallingBlock's fall is a
        // client-side block_display now, which (like the health-bar glow
        // overlay's own cube.location() usage) renders its 1x1x1 block model
        // aligned to the grid from its entity position as the corner, not
        // centered the way a real vanilla FallingBlock entity's hitbox is.
        Location spawnAt = new Location(region.world(), x, spawnY, z);

        CubeBonus rolledBonus = rollBonus(zone, packs.getPlayerStore().getOrCreate(playerId));
        if (tier.treasure() && rolledBonus == null) {
            // A chest must be visible from across the zone or nobody walks to
            // it. Reusing the bonus glow rather than inventing a second
            // highlight path means it gets the same see-through-walls outline
            // every bonus cube already has, for free. Multiplier 1.0 so this
            // is purely a light: a chest that ALSO rolls a real golden/diamond
            // bonus keeps that one instead, and keeps its multiplier.
            rolledBonus = TREASURE_GLOW;
        }
        if (tier.glow() != null && rolledBonus == null) {
            // Same reasoning as the chest above: a giant is the thing worth
            // walking across the zone for, so it carries a light of its
            // own. A giant that ALSO rolls a real bonus keeps that one.
            rolledBonus = new CubeBonus(GIANT_GLOW_ID, 0.0, 1.0, tier.glow());
        }
        final CubeBonus bonus = rolledBonus;
        pendingByPlayer.computeIfAbsent(playerId, k -> new AtomicInteger()).incrementAndGet();
        PendingColumn column = new PendingColumn(x, z, tier.giant());
        var blockData = tier.material().createBlockData();
        UUID[] fallId = new UUID[1];
        fallId[0] = core().getFakeFallingBlock().spawn(player, spawnAt, groundY, blockData, blockData, tier.size(),
                (owner, landedAt, blockEntityId, blockEntityUuid) -> {
                    pendingFallIdsByPlayer.getOrDefault(playerId, Set.of()).remove(fallId[0]);
                    Map<UUID, PendingColumn> columns = pendingColumnsByFallId.get(playerId);
                    if (columns != null) {
                        columns.remove(fallId[0]);
                    }
                    onLanded(owner, zone, tier, bonus, landedAt, blockEntityId, blockEntityUuid);
                });
        pendingFallIdsByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(fallId[0]);
        pendingColumnsByFallId.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(fallId[0], column);
    }

    /**
     * Whether a cube landing at {@code (x, z)} would share space with one of
     * this player's cubes, landed or still falling.
     * <p>
     * An ordinary cube fills exactly its own column, so two only collide on
     * the same one. A giant cube's body hangs past its column on every side,
     * so when either cube is giant the neighbouring columns count as
     * occupied too - otherwise a big safe could land flush against a normal
     * cube and the two models would render through each other.
     */
    /** Whether column (x, z) is inside the owner's no-spawn radius - see {@link #MIN_SPAWN_DISTANCE}. */
    private boolean tooCloseToOwner(Player player, int x, int z, float size) {
        Location at = player.getLocation();
        double half = size / 2.0;
        double dx = Math.max(0.0, Math.abs(at.getX() - (x + 0.5)) - half);
        double dz = Math.max(0.0, Math.abs(at.getZ() - (z + 0.5)) - half);
        return dx * dx + dz * dz < MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE;
    }

    /**
     * Whether a cube landing at {@code landedAt} would come down on its
     * owner - their hitbox against the cube's full body.
     * <p>
     * The scan keeps new cubes {@link #MIN_SPAWN_DISTANCE} away, but a
     * cube falls for over a second and a sprinting player covers more than
     * that, so where they are when it LANDS is checked too. An ordinary
     * cube's landing column is already blocked by its fake barrier from
     * the moment the fall starts; a giant's overhang is not, so this is
     * what stops a big safe or a boss block (and its collision box)
     * appearing around someone who walked under it.
     */
    private boolean landsOnOwner(Player player, Location landedAt, float size) {
        double minX = landedAt.getBlockX() + 0.5 - size / 2.0;
        double minZ = landedAt.getBlockZ() + 0.5 - size / 2.0;
        BoundingBox body = new BoundingBox(minX, landedAt.getBlockY(), minZ,
                minX + size, landedAt.getBlockY() + size, minZ + size);
        return player.getWorld().equals(landedAt.getWorld()) && player.getBoundingBox().overlaps(body);
    }

    private boolean isColumnOccupied(UUID playerId, int x, int z, boolean giant) {
        for (OreCube cube : cubesByPlayer.getOrDefault(playerId, List.of())) {
            int reach = giant || cube.tier().giant() ? 1 : 0;
            if (Math.abs(cube.location().getBlockX() - x) <= reach
                    && Math.abs(cube.location().getBlockZ() - z) <= reach) {
                return true;
            }
        }
        Map<UUID, PendingColumn> columns = pendingColumnsByFallId.get(playerId);
        if (columns == null) {
            return false;
        }
        for (PendingColumn column : columns.values()) {
            int reach = giant || column.giant() ? 1 : 0;
            if (Math.abs(column.x() - x) <= reach && Math.abs(column.z() - z) <= reach) {
                return true;
            }
        }
        return false;
    }

    /** The neutral, purely-cosmetic glow a treasure chest falls back to when it didn't roll a real bonus of its own - see spawnCubeFor. */
    private static final CubeBonus TREASURE_GLOW = new CubeBonus("treasure", 0.0, 1.0, NamedTextColor.GOLD);
    /** The id a giant cube's cosmetic-only glow carries - its label is the tier's own, not this. */
    private static final String GIANT_GLOW_ID = "giant";
    /**
     * Each tier label parsed once. The HP label and boss bar are rebuilt on
     * every damage flush of every cube several times a second - the same
     * reason the HP bars themselves are prebuilt (see {@link #HP_BARS}) - and
     * there are only ever a couple of distinct labels in a whole config.
     */
    private static final Map<String, Component> PARSED_LABELS = new ConcurrentHashMap<>();

    private static Component tierLabel(CubeTier tier) {
        return PARSED_LABELS.computeIfAbsent(tier.label(), Text::parse);
    }

    /** Independent of tier - every spawn also rolls each configured bonus's own chance (boosted by any registered CUBE_BONUS_CHANCE upgrades, clamped to 100%); the highest-multiplier one that hits (if any) wins. Null for a plain cube. */
    private CubeBonus rollBonus(ZoneDefinition zone, PackPlayerProfile profile) {
        double boost = cubeBonusChanceBoostSum(profile);
        CubeBonus best = null;
        for (CubeBonus bonus : zone.cubeBonuses()) {
            // Lucky Cubes' boost is flat for the everyday bonuses, but a very
            // rare one (the x10 rainbow at 0.2%) scales by the same ratio the
            // boost gives golden's 5% instead - a flat +5% would make it a
            // 1-in-20 cube and hand out ~45% more income.
            double chance = Math.min(1.0, bonus.chance() >= 0.01
                    ? bonus.chance() + boost
                    : bonus.chance() * (1.0 + boost / 0.05));
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
            owner.sendBlockChange(landedAt, AIR_DATA);
            PacketEntityManager.destroyEntity(owner, blockEntityId);
            return;
        }
        if (landsOnOwner(owner, landedAt, tier.size())) {
            // Never lands on the player: this one is withdrawn and a fresh
            // cube is scanned for against where they are NOW.
            owner.sendBlockChange(landedAt, AIR_DATA);
            PacketEntityManager.destroyEntity(owner, blockEntityId);
            spawnCubeFor(owner, zone);
            return;
        }
        int textEntityId = PacketEntityManager.nextEntityId();
        if (bonus != null) {
            applyBonusGlow(owner, blockEntityId, blockEntityUuid, bonus);
        }
        OreCube cube = new OreCube(landedAt, tier, blockEntityId, blockEntityUuid, textEntityId, bonus);
        rankCube(zone, cube);
        // Registration first, health bar last - the block has already
        // physically landed by this point (FakeFallingBlock's own handler
        // already did the sendBlockChange before invoking this callback),
        // so if the health bar display throws for any reason it must not
        // also silently prevent the cube from becoming targetable/
        // attackable - that failure mode is far worse than a missing nametag.
        cubesByPlayer.computeIfAbsent(owner.getUniqueId(), k -> new ArrayList<>()).add(cube);
        FakeBlockClickRegistry.register(owner, landedAt, cube.size(), clicker -> onCubeClicked.accept(clicker, cube));
        spawnHealthBar(owner, cube);
        if (tier.giant()) {
            spawnCollision(owner, cube);
            announceGiantLanding(owner, cube);
        }
        if (isRainbow(cube)) {
            owner.sendActionBar(Text.parse("<rainbow><bold>✦ A RAINBOW CUBE LANDED! ✦</bold></rainbow>"));
            owner.playSound(cube.center(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.8f);
            owner.playSound(cube.center(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f);
            owner.spawnParticle(Particle.END_ROD, cube.center(), 30, 0.4, 0.6, 0.4, 0.05);
        }
    }

    /** The bonus id for the rare ×10 cube whose outline cycles through every colour - see zones.yml's cube-bonuses. */
    private static final String RAINBOW_ID = "rainbow";

    private static boolean isRainbow(OreCube cube) {
        return cube.bonus() != null && RAINBOW_ID.equals(cube.bonus().id());
    }

    /**
     * Cycles every live rainbow cube's outline through the hue wheel. The
     * team colour its bonus glow starts with only offers sixteen fixed
     * colours; a display entity's own glow-colour override takes any RGB,
     * so the owner's client is simply told a new one a few times a second.
     * Only rainbow cubes are touched, and they're rare.
     */
    private void tickRainbows() {
        float hue = (Bukkit.getCurrentTick() % 60) / 60f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, 1f) & 0xFFFFFF;
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (OreCube cube : liveCubes(player)) {
                if (isRainbow(cube)) {
                    me.dontshare.yieldcore.packet.ItemDisplayManager.setGlowColor(player, cube.blockEntityId(), rgb);
                }
            }
        }
    }

    /**
     * Gives a bonus cube - and every big safe and boss block, which carry a
     * cosmetic one - its coloured see-through-walls outline, on the cube's
     * OWN body entity.
     * <p>
     * This used to be a second display entity: a copy of the same block 2%
     * bigger, sat over the body purely to carry the glow flag, from when the
     * body was a real (fake) block that could not glow. The body has long
     * been a display entity that can, and the copy had a cost nobody saw
     * until giants made glowing cubes the ones worth fighting: it never
     * animated, so it hid the body's hit squish completely - every golden
     * cube, big safe and boss block took its hits without reacting. Glowing
     * the body means the outline squishes with it.
     * <p>
     * The colour comes from a scoreboard {@code Team} on the OWNER'S OWN
     * board (see {@code ScoreboardManager#scoreboardFor} - every player has
     * a private board for the sidebar, so a team on the shared main board
     * would be invisible to them; only the owner ever sees a cube anyway).
     * A body can only be on one team, which is fine: the white look-at
     * outline is never applied to a cube that already glows.
     */
    private void applyBonusGlow(Player owner, int bodyEntityId, UUID bodyEntityUuid, CubeBonus bonus) {
        PacketEntityManager.setGlowing(owner, bodyEntityId, true);
        Scoreboard board = core().getScoreboardManager().scoreboardFor(owner);
        String teamName = "cube_glow_" + bonus.color().toString().toLowerCase(Locale.ROOT);
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
            team.color(bonus.color());
        }
        team.addEntry(bodyEntityUuid.toString());
    }

    /**
     * Makes a giant cube solid across its whole visible body, for its owner.
     * <p>
     * The fake barrier under every cube is one block, so on its own a 1.5
     * safe let you walk a quarter-block into it and a 2x2x2 boss block half
     * a block. Fake blocks can't fix that - they only come in whole blocks,
     * so filling the neighbouring columns would put an invisible wall well
     * outside what you can see. A shulker can: the client treats it as a
     * solid box, and the scale attribute grows that box with it. Vanilla
     * snaps a shulker to the centre of its block and stands it on the
     * block's floor, which is exactly where a giant is centred and standing,
     * so at scale {@code size} the box is precisely the visible cube.
     * Invisible, packet-only and owner-only like the rest of the cube.
     */
    private void spawnCollision(Player owner, OreCube cube) {
        int entityId = PacketEntityManager.nextEntityId();
        Location at = cube.location().clone().add(0.5, 0, 0.5);
        PacketEntityManager.beginBundle(owner);
        PacketEntityManager.spawnEntity(owner, entityId, EntityTypes.SHULKER, at);
        PacketEntityManager.setInvisible(owner, entityId, true);
        PacketEntityManager.setScale(owner, entityId, cube.size());
        PacketEntityManager.endBundle(owner);
        cube.setCollisionEntityId(entityId);
    }

    /**
     * A giant cube lands like it weighs something: a low thud, a ring of its
     * own material kicked up off the floor, and its name in the action bar
     * so the player looks round for it. Owner-only like everything else
     * about a cube.
     */
    private void announceGiantLanding(Player owner, OreCube cube) {
        Location floor = cube.location().clone().add(0.5, 0.05, 0.5);
        owner.playSound(floor, Sound.BLOCK_ANVIL_LAND, 0.45f, 0.6f);
        owner.playSound(floor, Sound.ENTITY_IRON_GOLEM_STEP, 1f, 0.5f);
        owner.spawnParticle(Particle.BLOCK, floor, 30, cube.size() * 0.5, 0.05, cube.size() * 0.5, 0.1,
                cube.tier().material().createBlockData());
        owner.spawnParticle(Particle.CLOUD, floor, 12, cube.size() * 0.5, 0.05, cube.size() * 0.5, 0.02);
        if (cube.tier().landingTitle() != null) {
            // The boss block's arrival is an event, not a notification: a
            // full title and a sound nobody mistakes for a normal landing.
            owner.showTitle(Title.title(
                    Text.parse(cube.tier().landingTitle()),
                    Text.parse("<gray>It's yours - go break it!</gray>")));
            owner.playSound(owner.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.3f);
        } else if (cube.tier().label() != null) {
            owner.sendActionBar(Text.parse(cube.tier().label() + " <gray>landed nearby!</gray>"));
        }
    }

    /** Every cube currently live for this player - the pool {@code PetCombatController} picks a target from. */
    /** The zone this player's cubes are spawning in right now, or null outside every zone. */
    public ZoneDefinition currentZoneOf(Player player) {
        return currentZone.get(player.getUniqueId());
    }

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
        Location labelPos = cube.location().clone().add(0.5, cube.size() + 0.4, 0.5);
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
        player.sendBlockChange(cube.location(), AIR_DATA);
        PacketEntityManager.destroyEntity(player, cube.blockEntityId());
        if (cube.collisionEntityId() != -1) {
            PacketEntityManager.destroyEntity(player, cube.collisionEntityId());
        }
        FakeBlockClickRegistry.unregister(player, cube.location());
        PacketEntityManager.destroyEntity(player, cube.textEntityId());
        Set<Integer> tracked = spawnedHealthBarIds.get(player.getUniqueId());
        if (tracked != null) {
            tracked.remove(cube.textEntityId());
        }
        if (cube.bonus() != null) {
            // The body carried the bonus colour's team entry itself (see
            // applyBonusGlow); the entity is gone, the entry must go too or
            // the team keeps a dead UUID for the rest of the session.
            Scoreboard board = core().getScoreboardManager().scoreboardFor(player);
            Team team = board.getEntryTeam(cube.blockEntityUuid().toString());
            if (team != null) {
                team.removeEntry(cube.blockEntityUuid().toString());
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
    public void queueDamage(Player player, OreCube cube, long amount, UUID petInstanceId) {
        queueDamage(player, cube, amount, petInstanceId, false);
    }

    /** {@link #queueDamage(Player, OreCube, long, UUID)} for a hit that crit - its tick's number is drawn as a crit. */
    public void queueDamage(Player player, OreCube cube, long amount, UUID petInstanceId, boolean crit) {
        if (amount <= 0) {
            return;
        }
        pendingDamageByPlayer.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .merge(cube, new PendingDamage(amount, petInstanceId == null ? Set.of() : Set.of(petInstanceId), crit),
                        (existing, fresh) -> existing.add(fresh.amount(), petInstanceId, crit));
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
        if (isOutermostFlush && hitSoundReady(player.getUniqueId())) {
            // At the cube being hit, not at the player - the player hears
            // the fight where it is, and hears it quieter the further off.
            OreCube loudest = queued.keySet().iterator().next();
            playHitImpactSound(player, loudest.center(), loudest.tier().material());
            playAttackSound(player, loudest.center());
        }
        try {
            for (Map.Entry<OreCube, PendingDamage> entry : queued.entrySet()) {
                OreCube cube = entry.getKey();
                long amount = entry.getValue().amount();
                Location center = cube.center();
                if (!cube.equals(quietIndicatorFor.get(player.getUniqueId()))) {
                    showDamageIndicator(player, cube, amount, entry.getValue().crit());
                }
                showHitImpact(player, center);
                boolean dead = cube.damage(amount);
                if (dead) {
                    killCube(player, zone, cube, entry.getValue().contributingInstanceIds());
                } else {
                    payChips(player, cube);
                    // The boss bar is a single, per-player HUD element - only
                    // this player's actual target should drive it, or a
                    // single-send player hitting several cubes at once would
                    // have it flicker to show whichever cube's HP happened to
                    // be processed last in this loop.
                    if (cube.equals(currentTarget(player))) {
                        updateBossBar(player, cube);
                    }
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
        float size = cube.size();
        // Shrinks toward the cube's own centre, whatever its size - the
        // same squish a normal cube does, just around a bigger middle.
        float shrunk = size * HIT_SHRINK_SCALE;
        float horizontal = 0.5f - shrunk / 2f;
        float vertical = (size - shrunk) / 2f;
        BlockDisplayManager.setInterpolation(viewer, entityId, 0, HIT_SHRINK_TICKS, HIT_SHRINK_TICKS);
        BlockDisplayManager.setTransformation(viewer, entityId,
                new Vector3f(horizontal, vertical, horizontal), new Vector3f(shrunk, shrunk, shrunk));
        UUID viewerId = viewer.getUniqueId();
        defer(HIT_SHRINK_TICKS, () -> {
            Player stillOnline = Bukkit.getPlayer(viewerId);
            if (stillOnline == null) {
                return;
            }
            BlockDisplayManager.setInterpolation(stillOnline, entityId, 0, HIT_GROW_TICKS, HIT_GROW_TICKS);
            BlockDisplayManager.setBlockSize(stillOnline, entityId, size);
        });
    }

    /** A small spark at the moment of impact - purely visual, per cube (each cube hit this tick gets its own spark at its own location). The "thwack" sound is a separate, once-per-flush call - see {@link #flushDamage}. */
    private void showHitImpact(Player viewer, Location center) {
        viewer.spawnParticle(Particle.CRIT, center, 6, 0.2, 0.2, 0.2, 0.05);
    }

    /** The impact "thwack" - deliberately separate from {@link #showHitImpact}'s particle, and from {@link #playAttackSound}'s own squish, so {@link #flushDamage} can gate each of this method's own single call per player-tick without touching the per-cube visuals. */
    /**
     * At most one pair of hit sounds per {@link #HIT_SOUND_GAP_MILLIS} per
     * player. A squad spread over several cubes, taps and kill-triggered
     * retargets can each flush in the same moment; without a floor on the
     * gap those stacked into a machine-gun of hit sounds that sped up as
     * the fight did.
     */
    private boolean hitSoundReady(UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = lastHitSoundAt.get(playerId);
        if (last != null && now - last < HIT_SOUND_GAP_MILLIS) {
            return false;
        }
        lastHitSoundAt.put(playerId, now);
        return true;
    }

    /** The block's own hit sound - ice clinks, copper rings, stone thuds - rather than stone for everything. */
    private void playHitImpactSound(Player viewer, Location at, Material material) {
        viewer.playSound(at, soundsOf(material).getHitSound(), 0.35f, 1.1f);
    }

    /** A cube's own block sounds - stone's for anything that somehow isn't a block, rather than throwing mid-fight. */
    private static SoundGroup soundsOf(Material material) {
        return (material.isBlock() ? material : Material.STONE).createBlockData().getSoundGroup();
    }

    /** Once per targeted cube per tick - not once per contributing pet, even though several pets landing a hit on the same cube in the same tick is the common case. */
    private void playAttackSound(Player viewer, Location center) {
        float pitch = 1.2f + ThreadLocalRandom.current().nextFloat() * 0.2f;
        viewer.playSound(center, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.2f, pitch);
    }

    /**
     * The sound and sparks of a single crit roll (see {@code
     * PetCombatController#applyDamage}). The number itself is drawn as a
     * crit - gold, starred, bigger - by that tick's damage indicator, so
     * this no longer floats a separate "CRIT!" over the top of it.
     */
    public void playCritFlourish(Player player, OreCube target) {
        Location center = target.center();
        player.playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.35f, 1f);
        player.spawnParticle(Particle.CRIT, center, 12, 0.25, 0.25, 0.25, 0.3);
    }

    /** "-<amount>" in red, floating up from a randomized spot near the cube so simultaneous hits from several pets don't overlap. */
    /**
     * A tap on {@code cube}: {@code whole} is the HP it actually takes (tap
     * damage keeps its fraction between clicks - see TapService), {@code
     * shown} the exact amount the player dealt, which is what the floating
     * number says. The flush that applies {@code whole} doesn't add a
     * second, rounded number of its own for this cube.
     */
    public void applyTap(Player player, OreCube cube, long whole, double shown) {
        spawnDamageNumber(player, cube, shown, false);
        queueDamage(player, cube, whole, null);
        if (whole <= 0) {
            // Nothing to flush for this cube, but the click still lands.
            playHitSquish(player, cube);
            if (hitSoundReady(player.getUniqueId())) {
                playHitImpactSound(player, cube.center(), cube.tier().material());
            }
        }
        quietIndicatorFor.put(player.getUniqueId(), cube);
        try {
            flushDamage(player);
        } finally {
            quietIndicatorFor.remove(player.getUniqueId());
        }
    }

    private void showDamageIndicator(Player viewer, OreCube cube, long amount, boolean crit) {
        spawnDamageNumber(viewer, cube, amount, crit);
    }

    /**
     * The floating "-amount", sized and coloured by how much of the cube
     * that hit took rather than the same small red number for everything.
     * <p>
     * A chip (under a tenth of the cube) is small and soft red; a real
     * chunk is bigger and brighter; a hit that takes a third or more is
     * big, bold and orange, and the blow that kills is bigger still. A crit
     * is gold with a star either side, on top of whichever size it earned.
     * Size is keyed to share of HP, not the raw number, so a zone-20 hit
     * doesn't read as more exciting than a zone-1 one just for having more
     * digits - what matters is how hard it hit the thing in front of you.
     */
    private void spawnDamageNumber(Player viewer, OreCube cube, double amount, boolean crit) {
        long maxHp = Math.max(1L, cube.tier().maxHp());
        double share = Math.min(1.0, amount / maxHp);
        boolean killing = amount >= cube.currentHp();
        float scale = (float) (0.75 + 0.85 * Math.sqrt(share));
        if (killing) {
            scale += 0.25f;
        }
        if (crit) {
            scale *= 1.2f;
        }
        String number = Formatting.format(amount);
        String markup;
        if (crit) {
            markup = "<bold><gradient:#FFF36B:#FFA600>✦ -<n> ✦</gradient></bold>";
        } else if (killing || share >= 0.33) {
            markup = "<bold><#FF8A1F>-<n></#FF8A1F></bold>";
        } else if (share >= 0.10) {
            markup = "<#FF3B3B>-<n></#FF3B3B>";
        } else {
            markup = "<#FF8080>-<n></#FF8080>";
        }
        Component text = Text.parse(markup, Placeholder.unparsed("n", number));
        // Bigger numbers float a little higher and linger a little longer,
        // so a heavy hit is still readable once the next chips land.
        int lifetime = DAMAGE_INDICATOR_LIFETIME_TICKS + (int) Math.round((scale - 0.75f) * 8);
        spawnFloatingText(viewer, cube.center(), text, DAMAGE_INDICATOR_RISE_TICKS, lifetime, scale, 0.7 + 0.35 * scale);
    }

    /**
     * A chest's own reward on top of the coins/diamonds every cube pays -
     * a clutch of that zone's own eggs, hatched free, right where the player
     * is standing.
     * <p>
     * Deliberately eggs rather than more coins: coins are already what the
     * chest's inflated coin-value pays, and a second pile of them would just
     * be a bigger number. Pets are the thing a player turns into power, so a
     * chest reads as "your squad just got better" rather than "the counter
     * moved".
     * <p>
     * They hatch on the spot rather than going into a stockpile, because
     * there is no stockpile any more - and a chest that cracks open eight
     * eggs in front of you is a better moment than one that increments a
     * number you have to walk somewhere to spend. This is the one hatch that
     * happens away from a station, which is exactly what makes a chest feel
     * like a chest.
     */
    private void grantTreasurePacks(Player player, PackPlayerProfile profile, CubeTier tier) {
        String packId = tier.rewardPackId();
        if (packId == null || tier.rewardPackAmount() <= 0) {
            return;
        }
        String packName = packs.getPackRegistry().find(packId)
                .map(pack -> Formatting.stripLeadingColorCodes(pack.displayName()))
                .orElse("Egg");
        player.sendMessage(Text.parse("<#FFD700><bold>TREASURE!</bold></#FFD700> <gray>+<amount>x</gray> <white><pack></white>",
                Placeholder.unparsed("amount", String.valueOf(tier.rewardPackAmount())),
                Placeholder.unparsed("pack", packName)));
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1.1f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
        packs.getPackOpenService().grantHatch(player, packId, tier.rewardPackAmount());
    }

    /** "+<coins> coins" (and "+<diamonds> diamonds" only if any were earned), floating up from the cube on a kill - a combo of 2+ gets its own line, right where the player is already looking. */
    private void showEarningsIndicator(Player viewer, Location center, long coins, long diamondsEarned, int combo) {
        Component text = Text.parse("<#55FF7F>+<coins> coins</#55FF7F>", Placeholder.unparsed("coins", Formatting.format(coins)));
        if (diamondsEarned > 0) {
            text = text.append(Component.newline())
                    .append(Text.parse("<#55FFFF>+<diamonds> diamonds</#55FFFF>", Placeholder.unparsed("diamonds", Formatting.format(diamondsEarned))));
        }
        if (combo > 1) {
            text = text.append(Component.newline())
                    .append(Text.parse("<#FFAA00><bold>x<combo> COMBO!</bold></#FFAA00>", Placeholder.unparsed("combo", String.valueOf(combo))));
        }
        spawnFloatingText(viewer, center, text, EARNINGS_INDICATOR_RISE_TICKS, EARNINGS_INDICATOR_LIFETIME_TICKS, 1.15f, 1.2);
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
        spawnFloatingText(viewer, center, text, riseTicks, lifetimeTicks, 0.9f, 0.9);
    }

    private void spawnFloatingText(Player viewer, Location center, Component text, int riseTicks, int lifetimeTicks,
                                   float scale, double rise) {
        int entityId = PacketEntityManager.nextEntityId();
        double dx = ThreadLocalRandom.current().nextDouble(-0.4, 0.4);
        double dz = ThreadLocalRandom.current().nextDouble(-0.4, 0.4);
        Location spawnAt = center.clone().add(dx, 0.2, dz);

        PacketEntityManager.beginBundle(viewer);
        TextDisplayManager.spawn(viewer, entityId, spawnAt);
        // Every display field in one packet rather than six. These are spawned
        // per cube per hit, so the per-field packets were the bulk of what
        // this method cost.
        TextDisplayManager.metadata()
                .billboard(TextDisplayManager.Billboard.VERTICAL)
                .backgroundColor(0x00000000)
                .style(true, false, false, TextDisplayManager.Alignment.CENTER)
                .scale(scale, scale, scale)
                .text(text)
                .interpolation(0, riseTicks, riseTicks)
                .send(viewer, entityId);
        PacketEntityManager.endBundle(viewer);

        PacketEntityManager.teleportEntity(viewer, entityId, spawnAt.clone().add(0, rise, 0));
        scheduleDespawn(viewer, entityId, lifetimeTicks);
    }

    /**
     * Short visual follow-ups, bucketed by the tick they come due on.
     * <p>
     * Each of these used to schedule its own {@code runTaskLater} - one per
     * floating number and one per cube hit - which is a great many scheduled
     * tasks when several pets are hitting several cubes a few times a second,
     * each for work amounting to a packet or two. One sweeper that only ever
     * looks at the bucket actually due does the same job.
     */
    private final Map<Long, List<Runnable>> deferredByTick = new HashMap<>();

    private void defer(int delayTicks, Runnable action) {
        deferredByTick.computeIfAbsent(Bukkit.getCurrentTick() + (long) delayTicks, tick -> new ArrayList<>())
                .add(action);
    }

    private void sweepDeferred() {
        List<Runnable> due = deferredByTick.remove((long) Bukkit.getCurrentTick());
        if (due == null) {
            return;
        }
        for (Runnable action : due) {
            action.run();
        }
    }

    private void scheduleDespawn(Player viewer, int entityId, int lifetimeTicks) {
        UUID viewerId = viewer.getUniqueId();
        defer(lifetimeTicks, () -> {
            Player stillOnline = Bukkit.getPlayer(viewerId);
            if (stillOnline != null) {
                PacketEntityManager.destroyEntity(stillOnline, entityId);
            }
        });
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
        Location center = cube.center();
        // Spread and count grow with the cube, so a big safe bursts like a
        // big safe instead of a normal cube's puff coming out of its middle.
        double spread = 0.35 * cube.size();
        int count = Math.round(40 * cube.size() * cube.size());
        player.spawnParticle(Particle.BLOCK, center, count, spread, spread, spread, 0.15, cube.tier().material().createBlockData());
        player.playSound(center, soundsOf(cube.tier().material()).getBreakSound(), 0.6f, 1f);
        if (cube.tier().giant()) {
            player.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.4f);
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 30, spread, spread, spread, 0.35);
        }
        despawnCube(player, cube);
        // Single-send mode can have several cubes alive for this player at
        // once, and this kill isn't necessarily their current target - only
        // tear down the boss bar (a single, per-player HUD element) when the
        // cube that just died is the one it was actually showing.
        if (targetByPlayer.remove(player.getUniqueId(), cube)) {
            hideBossBar(player);
        }

        payOut(player, cube, center, contributingInstanceIds);
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
        }, Math.max(1L, Math.round(zone.respawnDelayMillis() * respawnDelayMultiplier(player.getUniqueId()) / 50.0)));
    }

    private void payOut(Player player, OreCube cube, Location cubeCenter, Set<UUID> contributingInstanceIds) {
        CubeTier tier = cube.tier();
        CubeBonus bonus = cube.bonus();
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
        // What the fight already dropped comes off the top: the kill pays the
        // rest, so a cube pays the same in total however it was broken.
        long killCoins = Math.max(0L, coins - cube.chippedCoins());

        boolean guaranteedDiamond = contributors.stream().anyMatch(pet -> leveling.hasMilestone(pet, MilestoneEffect.GUARANTEED_DIAMOND_DROP));
        // The Glittering Unique pet-enchant (see PetEnchantService#hasBonusDiamondDropEnchant) isn't
        // an outright guarantee like the milestone above - a hefty flat chance bump instead,
        // matching its own "bonus chance" framing rather than "always."
        boolean hasGlittering = packs.getPetEnchantService().hasBonusDiamondDropEnchant(contributors);
        double luck = luckService.totalLuckMultiplier(profile);
        double diamondChance = (0.05 * luck + diamondChanceBoostSum(profile)) * cube.diamondChanceMultiplier()
                + (hasGlittering ? 0.5 : 0.0);
        // The roll is a flat chance; the TIER decides how big the payout is,
        // so diamond income tracks the zone the same way coins do.
        long diamondsEarned = guaranteedDiamond || ThreadLocalRandom.current().nextDouble() < diamondChance
                ? tier.diamondValue() : 0L;
        diamondsEarned += flatBonusSum(flatDiamondBonusProviders, profile, tier.material());
        if (diamondsEarned > 0) {
            diamondsEarned = Math.round(diamondsEarned * packs.diamondMultiplier(profile)
                    * (1.0 - CHIP_SHARE * cube.chipsPaid() / CHIPS_PER_CUBE));
            diamondsEarned = Math.max(1L, diamondsEarned);
        }
        long killDiamonds = diamondsEarned;
        // For everything that counts earnings (lifetime totals, quests, the
        // summary, blocktree perks) the cube paid its whole amount.
        diamondsEarned += cube.chippedDiamonds();
        dropLoot(player, cube, killCoins, killDiamonds, true);
        if (tier.treasure()) {
            grantTreasurePacks(player, profile, tier);
            rareMoment(player, cube, "TREASURE", "#FFD700", 2, null);
        }
        if (isRainbow(cube)) {
            rareMoment(player, cube, "RAINBOW x" + (long) bonus.multiplier(), "#FF55FF", 2, null);
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
        // Rarer cubes roll their rare drops at better odds - see rankCube.
        giveCandyDrops(player, cube, luck * cube.rareDropMultiplier());
        // Enchant Books: very rare from an ordinary cube, likelier from a big
        // safe, guaranteed (and at least Epic) from a boss block - the cube's
        // own chance and floor, set at load (see CubeTier#bookChance).
        var book = packs.getEnchantService().rollBookDrop(player, tier.bookChance() * cube.rareDropMultiplier(), luck, tier.bookMinRarity());
        if (book != null) {
            int intensity = book.sortOrder() >= 4 ? 2 : book.sortOrder() >= 2 ? 1 : 0;
            rareMoment(player, cube, book.displayName().toUpperCase(java.util.Locale.ROOT) + " BOOK", book.colorHex(),
                    intensity, new ItemStack(Material.ENCHANTED_BOOK));
        }
        if (killDiamonds > 0 && (cube.tierRank() >= 3 || bonus != null) && !tier.treasure()) {
            rareMoment(player, cube, "DIAMONDS", "#55FFFF", 0, null);
        }
        showEarningsIndicator(player, cubeCenter, killCoins, killDiamonds, combo.count());
        announceCombo(player, cubeCenter, combo);
        queueSummary(player, coins, diamondsEarned);
        Bukkit.getPluginManager().callEvent(new OreCubeKilledEvent(player, tier, coins, diamondsEarned, bonusMultiplier));
        // Level/damage may have just changed; the balance itself updates as
        // the drops are collected (see LootDropService).
        core().getScoreboardDisplay().refresh(player);
    }

    /** How many times a cube pays out mid-fight - every fifth of its HP, stopping short of the kill. */
    private static final int CHIPS_PER_CUBE = 4;
    /** The share of a cube's coins (and diamond odds) paid out mid-fight; the kill pays the rest. */
    private static final double CHIP_SHARE = 0.35;

    /**
     * Pays whatever mid-fight payouts this cube's lost HP has earned: one at
     * each fifth of its HP knocked off (20/40/60/80%), each a share of the
     * cube's coins and a roll of its usual diamond chance for a share of its
     * diamonds. They drop as loot like the kill does, just smaller - the
     * money comes out while you're hitting it, then the big burst on the
     * break. The kill takes whatever these paid off its own payout, so the
     * cube's total never changes (see {@link #payOut}).
     */
    private void payChips(Player player, OreCube cube) {
        CubeTier tier = cube.tier();
        double lost = 1.0 - cube.currentHp() / (double) Math.max(1L, tier.maxHp());
        int due = Math.min(CHIPS_PER_CUBE, (int) Math.floor(lost * (CHIPS_PER_CUBE + 1)));
        if (due <= cube.chipsPaid()) {
            return;
        }
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        double bonusMultiplier = cube.bonus() != null ? cube.bonus().multiplier() : 1.0;
        double diamondChance = (0.05 * luckService.totalLuckMultiplier(profile) + diamondChanceBoostSum(profile))
                * cube.diamondChanceMultiplier();
        double perChip = CHIP_SHARE / CHIPS_PER_CUBE;
        while (cube.chipsPaid() < due) {
            long coins = Math.round(tier.coinValue() * packs.coinMultiplier(profile)
                    * blockCoinMultiplierSum(profile, tier.material()) * bonusMultiplier * perChip);
            long diamonds = ThreadLocalRandom.current().nextDouble() < diamondChance
                    ? Math.max(1L, Math.round(tier.diamondValue() * packs.diamondMultiplier(profile) * perChip)) : 0L;
            cube.recordChip(coins, diamonds);
            dropLoot(player, cube, coins, diamonds, false);
        }
    }

    /** Spills {@code coins}/{@code diamonds} out of {@code cube} as collectable drops - a few for a mid-fight payout, a fountain for the kill, more for a rarer cube. */
    private void dropLoot(Player player, OreCube cube, long coins, long diamonds, boolean kill) {
        CubeTier tier = cube.tier();
        // Each tier up spills visibly more: Tier I 5, II 8, III 12 on the
        // break (2/3/4 mid-fight), a giant or chest a real fountain.
        int rank = Math.max(1, cube.tierRank());
        int coinPieces;
        if (!kill) {
            coinPieces = 1 + rank;
        } else if (isRainbow(cube)) {
            coinPieces = 30;
        } else if (tier.treasure() || tier.giant()) {
            coinPieces = 16;
        } else {
            coinPieces = rank == 1 ? 5 : rank == 2 ? 8 : 12 + 3 * (rank - 3);
        }
        int diamondPieces = kill ? Math.max(2, coinPieces / 3) : 1;
        Location center = cube.center();
        double floorY = cube.location().getY();
        double spread = cube.size() / 2.0;
        lootDrops.spawn(player, center, floorY, spread, LootDropService.Kind.COIN, coins, coinPieces);
        lootDrops.spawn(player, center, floorY, spread, LootDropService.Kind.DIAMOND, diamonds, diamondPieces);
    }

    /** Placeholder candy source for this pass (see Candy's own Javadoc) - a luck-modified roll per configured candy type on every kill. Overflow past a full inventory drops at the player's feet rather than vanishing. */
    private void giveCandyDrops(Player player, OreCube cube, double luck) {
        for (Candy c : packs.getPetLevelingService().rollCandyDrops(luck)) {
            ItemStack item = packs.getPetLevelingService().createCandyItem(c);
            rareMoment(player, cube, "CANDY", "#FFB6E1", 0, item.clone());
            player.getInventory().addItem(item).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            player.sendMessage(Text.parse("<#FFB6E1>You found some <name>!</#FFB6E1>",
                    Placeholder.unparsed("name", c.displayName())));
        }
    }

    /**
     * The moment a rare drop gets: a column of light in its colour rising
     * out of the cube, "✦ LABEL ✦" floating up big and bold, a burst and a
     * chime that grow with {@code intensity} (0 small, 1 notable, 2 huge),
     * and - when there's an {@code item} to show - that item popping out
     * and flying to the player.
     * <p>
     * Every piece of it is sent to this one player only (packet entities,
     * per-player particles and sounds): with a hundred players in a zone,
     * nobody else's screen or connection pays for it.
     */
    private void rareMoment(Player player, OreCube cube, String label, String colorHex, int intensity, ItemStack item) {
        int rgb;
        try {
            rgb = Integer.parseInt(colorHex.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            rgb = 0xFFFFFF;
        }
        Location center = cube.center();
        org.bukkit.Color color = org.bukkit.Color.fromRGB(rgb);
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.3f + 0.3f * intensity);
        double height = 2.0 + intensity * 1.2;
        for (double y = 0; y <= height; y += 0.2) {
            player.spawnParticle(Particle.DUST, center.clone().add(0, y, 0), 2, 0.08, 0.05, 0.08, 0, dust);
        }
        if (intensity >= 1) {
            player.spawnParticle(Particle.FIREWORK, center, 14 * intensity, 0.3, 0.3, 0.3, 0.12);
        }
        if (intensity >= 2) {
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 45, 0.4, 0.5, 0.4, 0.45);
        }

        Component text = Text.parse("<" + colorHex + "><bold>✦ " + label + " ✦</bold></" + colorHex + ">");
        float scale = 1.15f + 0.25f * intensity;
        spawnFloatingText(player, center, text, 14, 34 + 10 * intensity, scale, 1.3 + 0.2 * intensity);

        player.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.9f, 1.4f);
        if (intensity >= 1) {
            player.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.8f);
        }
        if (intensity >= 2) {
            player.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
        }

        if (item != null) {
            lootDrops.spawnCosmetic(player, center, cube.location().getY(), cube.size() / 2.0, item, rgb, 0.7f);
        }
    }

    /** Accumulates into the rolling 1-minute "Slaying Summary" chat block instead of messaging per-kill - see {@link #flushSummaries}. */
    private void queueSummary(Player player, long coins, long diamonds) {
        pendingSummary.merge(player.getUniqueId(), new WindowStats(coins, diamonds, 1),
                (existing, fresh) -> existing.add(fresh.coins(), fresh.diamonds()));
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
                            "<gray>│ Gold: </gray><yellow><coins></yellow>\n" +
                            "<gray>│ Diamonds: </gray><aqua><diamonds></aqua>\n" +
                            "<gray>│ Kills: </gray><red>x<kills></red>",
                    Placeholder.unparsed("minutes", String.valueOf(elapsedMinutes)),
                    Placeholder.unparsed("coins", Formatting.format(stats.coins())),
                    Placeholder.unparsed("diamonds", Formatting.format(stats.diamonds())),
                    Placeholder.unparsed("kills", Formatting.format(stats.kills()))));
        }
    }

    private void updateBossBar(Player player, OreCube cube) {
        float progress = Math.max(0f, Math.min(1f, cube.currentHp() / (float) cube.tier().maxHp()));
        // The boss bar HUD is single-line - the bonus label (if any) only
        // goes on the floating nametag (see healthBarText), which text_display
        // actually renders as separate lines.
        Component title = hpBarLine(cube);
        if (cube.bonus() != null && !GIANT_GLOW_ID.equals(cube.bonus().id())) {
            title = bonusLabel(cube).append(Component.text(" ")).append(title);
        }
        title = nameLabel(cube).append(Component.text(" ")).append(title);
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
        Component text = hpBar(cube).append(Component.newline()).append(heartLine(cube));
        if (cube.bonus() != null && !GIANT_GLOW_ID.equals(cube.bonus().id())) {
            text = bonusLabel(cube).append(Component.newline()).append(text);
        }
        // The cube's name sits on top: "BIG SAFE" (or plain "Stone") above
        // "GOLDEN x2" above the bar. A giant's cosmetic glow has no line of
        // its own - the name already says what it is.
        return nameLabel(cube).append(Component.newline()).append(text);
    }

    /** How much likelier each tier of a zone is to pay out its rarer things, before normalising - Tier I, II, III, then +0.9 a tier beyond. */
    private static double rarityFactor(int rank) {
        return switch (rank) {
            case 0, 1 -> 1.0;
            case 2 -> 1.6;
            case 3 -> 2.5;
            default -> 2.5 + 0.9 * (rank - 3);
        };
    }

    /**
     * Works out where this cube sits in its zone's ladder (Tier I is the
     * weakest ordinary cube by HP) and how its odds shift for it.
     * <p>
     * Higher tiers are likelier to drop diamonds, but the zone's diamond
     * income stays where the pacing has it: the factors are normalised so
     * that, weighted by how often each tier spawns and what it pays, the
     * average chance is unchanged - Tier I gives up a little so Tier III
     * can pay out far more often. Enchant books and candy just get the
     * factor straight: they're rare, and not what the pacing runs on.
     * <p>
     * A giant takes the tier of the ordinary cube it's a giant version of;
     * treasure sits outside the ladder.
     */
    private void rankCube(ZoneDefinition zone, OreCube cube) {
        CubeTier tier = cube.tier();
        List<CubeTier> ladder = zone.cubeTiers().stream()
                .filter(t -> !t.treasure() && !t.giant())
                .sorted(java.util.Comparator.comparingLong(CubeTier::maxHp))
                .toList();
        int rank = 0;
        if (!tier.treasure()) {
            for (int i = 0; i < ladder.size(); i++) {
                CubeTier rung = ladder.get(i);
                if (tier.giant() ? rung.material() == tier.material() : rung.equals(tier)) {
                    rank = i + 1;
                    break;
                }
            }
        }
        double paid = 0;
        double paidWeighted = 0;
        for (int i = 0; i < ladder.size(); i++) {
            CubeTier rung = ladder.get(i);
            double value = rung.weight() * rung.diamondValue();
            paid += value;
            paidWeighted += value * rarityFactor(i + 1);
        }
        double norm = paidWeighted > 0 ? paid / paidWeighted : 1.0;
        double factor = rarityFactor(rank);
        cube.setRarity(rank, tier.giant() || tier.treasure() ? 1.0 : factor * norm, factor);
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private static final Map<CubeTier, Component> BLOCK_NAMES = new ConcurrentHashMap<>();

    /**
     * What the cube is called: its tier's own label if it has one (a big
     * safe, a boss block, a treasure chest), otherwise the block's name -
     * white for a zone's common cube, green for the uncommon one, aqua for
     * the rare one, so the rare cube reads as rare before you hit it.
     */
    private static Component nameLabel(OreCube cube) {
        CubeTier tier = cube.tier();
        int rank = cube.tierRank();
        Component name = tier.label() != null ? tierLabel(tier)
                : BLOCK_NAMES.computeIfAbsent(tier, t -> {
                    String color = t.weight() >= 50 ? "&f" : t.weight() >= 10 ? "&a" : "&b";
                    return Text.parse(color + "&l" + blockName(t.material()));
                });
        if (rank <= 0) {
            return name;
        }
        return TIER_TAGS.computeIfAbsent(rank, OreCubeService::tierTag).append(name);
    }

    private static final Map<Integer, Component> TIER_TAGS = new ConcurrentHashMap<>();

    /** "[Tier II] " - dark gray brackets, gray text, in front of the cube's name. */
    private static Component tierTag(int rank) {
        String numeral = rank < ROMAN.length ? ROMAN[rank] : String.valueOf(rank);
        return Text.parse("&8[&7Tier " + numeral + "&8] ");
    }

    /** "COPPER_BLOCK" -> "Copper Block". */
    private static String blockName(Material material) {
        StringBuilder name = new StringBuilder();
        for (String word : material.name().split("_")) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return name.toString();
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
    /**
     * Every bar the game can draw, built once.
     * <p>
     * A bar has only {@code HP_BAR_SEGMENTS + 1} possible appearances, but it
     * was being assembled into a ~20-tag string and pushed through
     * MiniMessage on every damage flush of every cube - several times a
     * second per player, for one of eleven results.
     */
    private static final Component[] HP_BARS = buildHpBars();

    /**
     * The two constant block states this service sends, built once rather
     * than per call - the self-heal pass below sends one per live cube per
     * player several times a second, and was allocating a fresh instance for
     * every one of them. yield-core's FakeFallingBlock already holds its
     * barrier this way.
     */
    private static final BlockData BARRIER_DATA = Material.BARRIER.createBlockData();
    private static final BlockData AIR_DATA = Material.AIR.createBlockData();

    private static Component[] buildHpBars() {
        Component[] bars = new Component[HP_BAR_SEGMENTS + 1];
        for (int filled = 0; filled <= HP_BAR_SEGMENTS; filled++) {
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < HP_BAR_SEGMENTS; i++) {
                bar.append(i < filled ? "<green><st> </st></green>" : "<gray><st> </st></gray>");
            }
            bars[filled] = Text.parse(bar.toString());
        }
        return bars;
    }

    private Component hpBar(OreCube cube) {
        double ratio = cube.tier().maxHp() <= 0 ? 0
                : Math.max(0, Math.min(1.0, cube.currentHp() / (double) cube.tier().maxHp()));
        return HP_BARS[(int) Math.round(ratio * HP_BAR_SEGMENTS)];
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
        if (isRainbow(cube)) {
            return Text.parse("<rainbow><bold>" + Formatting.fancyFont("rainbow") + " x"
                    + (long) cube.bonus().multiplier() + "</bold></rainbow>");
        }
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
        lootDrops.collectAll(player);
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
                player.sendBlockChange(loc, BARRIER_DATA);
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
        lastHitSoundAt.remove(player.getUniqueId());
    }

    private CubeTier rollTier(ZoneDefinition zone, PackPlayerProfile profile) {
        if (profile == null || spawnWeightMultiplierProviders.isEmpty()) {
            return WeightedRandom.pick(zone.cubeTiers(), CubeTier::weight);
        }
        return WeightedRandom.pick(zone.cubeTiers(), tier -> {
            double weight = tier.weight();
            for (BiFunction<PackPlayerProfile, CubeTier, Double> provider : spawnWeightMultiplierProviders.values()) {
                Double value = provider.apply(profile, tier);
                if (value != null) {
                    weight *= Math.max(0.0, value);
                }
            }
            return weight;
        });
    }

    private YieldCore core() {
        return JavaPlugin.getPlugin(YieldCore.class);
    }
}
