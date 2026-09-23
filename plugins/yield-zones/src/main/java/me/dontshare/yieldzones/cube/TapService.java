package me.dontshare.yieldzones.cube;

import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.CombatPerks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Tap damage - the player's own hit, as in Pet Simulator 99.
 * <p>
 * Pets fight on their own; clicking a cube pulls them onto it AND deals a
 * tap. A tap is {@link #BASE_TAP_SHARE} of the player's strongest equipped
 * pet's hit (after every damage multiplier), so it keeps pace with the
 * ladder on its own - a flat number would be decisive in the Meadow and
 * invisible by the Genesis Core - and it stays a garnish on the squad
 * rather than a replacement for it.
 * <p>
 * Everything that makes taps hit harder is a keyed multiplier provider
 * ({@link #registerTapMultiplierProvider}), the same composable idiom as
 * every other damage and payout modifier here: the level-10 pet milestone
 * is one, and hand-held tools are meant to be the next - a tool plugs in
 * as a provider reading what the player is holding, with no change here.
 * <p>
 * Taps are rate-limited to one every {@link #MIN_TAP_INTERVAL_MILLIS}, so
 * an autoclicker gets exactly what a fast human does and no more. Faster
 * clicks still redirect the squad; they just don't hit. Auto Tap - the
 * bought perk - taps whatever the pets are on through the same limit.
 */
public final class TapService implements Listener {

    /** A tap is this share of the strongest equipped pet's hit, before tap multipliers. */
    public static final double BASE_TAP_SHARE = 0.10;
    /** The fastest a player can tap for damage - about 6.7 taps a second. */
    public static final long MIN_TAP_INTERVAL_MILLIS = 150L;
    /**
     * No single tap deals more than this share of a cube's max HP, so every
     * cube takes at least ten taps.
     * <p>
     * Without it, taps quietly broke the whole pacing model. Clicking a cube
     * redirects the squad and resets their one-second switch cooldown, but
     * a tap lands immediately - so a player spam-clicking from cube to cube
     * kills with taps alone, never waiting on the pets. Late in a zone a
     * squad is 5-25x a basic cube's HP, which makes even a plain tap half a
     * cube: ~3 kills a second against the 1 a second the ladder is balanced
     * on. At ten taps minimum, tap-killing tops out around 0.67 cubes a
     * second - below what the pets do alone - so clicking can only ever
     * ADD damage to a fight, never replace the fight. It barely touches an
     * honest tap: early in a zone, when cubes are tough, a tap is far
     * under 10% of one anyway.
     * <p>
     * Tools that raise tap damage are bounded by this too, which is the
     * point. A tool that should break the rule should do it on purpose, by
     * raising this cap for its holder, not by accident.
     */
    public static final double MAX_TAP_SHARE_OF_CUBE_HP = 0.10;
    /** Auto Tap taps once every this many ticks - four a second, under the manual cap on purpose. */
    public static final long AUTO_TAP_INTERVAL_TICKS = 5L;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final OreCubeService cubes;
    private final Map<UUID, Long> lastTapAt = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<Player, PackPlayerProfile, Double>> multiplierProviders = new ConcurrentHashMap<>();
    /** Players whose taps should do nothing right now - a world boss fight owns their attention. */
    private Predicate<Player> paused = player -> false;

    public TapService(JavaPlugin plugin, YieldPacks packs, OreCubeService cubes) {
        this.plugin = plugin;
        this.packs = packs;
        this.cubes = cubes;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::autoTapTick, AUTO_TAP_INTERVAL_TICKS, AUTO_TAP_INTERVAL_TICKS);
    }

    public void setPaused(Predicate<Player> paused) {
        this.paused = paused;
    }

    /** Multiplies tap damage. Several providers multiply together; a provider returning 1.0 changes nothing. */
    public void registerTapMultiplierProvider(String key, BiFunction<Player, PackPlayerProfile, Double> provider) {
        multiplierProviders.put(key, provider);
    }

    public void unregisterTapMultiplierProvider(String key) {
        multiplierProviders.remove(key);
    }

    /** What one tap deals right now - also what a tools or stats screen would show. Never below 1. */
    public long tapDamage(Player player, PackPlayerProfile profile) {
        double best = 0;
        for (UUID petId : profile.getEquippedPetIds()) {
            PetInstance pet = profile.findPet(petId).orElse(null);
            if (pet != null) {
                best = Math.max(best, packs.getEquipmentService().effectiveDamage(profile, pet));
            }
        }
        double multiplier = 1.0;
        for (BiFunction<Player, PackPlayerProfile, Double> provider : multiplierProviders.values()) {
            Double value = provider.apply(player, profile);
            if (value != null) {
                multiplier *= Math.max(0.0, value);
            }
        }
        return Math.max(1L, Math.round(best * packs.damageMultiplier(profile) * BASE_TAP_SHARE * multiplier));
    }

    /**
     * One tap on {@code cube}: queued and flushed straight away, so it lands
     * with its own damage number, squish and sound on the click rather than
     * waiting for the pets' next combat tick. Returns false when it was
     * rate-limited or the cube is no longer this player's.
     */
    public boolean tap(Player player, OreCube cube) {
        UUID id = player.getUniqueId();
        if (paused.test(player) || !cubes.liveCubes(player).contains(cube)) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastTapAt.get(id);
        if (last != null && now - last < MIN_TAP_INTERVAL_MILLIS) {
            return false;
        }
        PackPlayerProfile profile = packs.getPlayerStore().getCached(id);
        if (profile == null) {
            return false;
        }
        lastTapAt.put(id, now);
        long cap = Math.max(1L, Math.round(cube.tier().maxHp() * MAX_TAP_SHARE_OF_CUBE_HP));
        cubes.queueDamage(player, cube, Math.min(tapDamage(player, profile), cap), null);
        cubes.flushDamage(player);
        return true;
    }

    /**
     * Auto Tap: taps whatever the player's pets are on. With nothing
     * targeted - auto-attack off and nothing clicked - it taps nothing,
     * rather than choosing a cube for the player the way the pets would.
     */
    private void autoTapTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!CombatPerks.hasAutoTap(player)) {
                continue;
            }
            PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
            if (profile == null || !profile.isAutoTapEnabled()) {
                continue;
            }
            List<OreCube> live = cubes.liveCubes(player);
            OreCube target = cubes.currentTarget(player);
            if (target != null && live.contains(target)) {
                tap(player, target);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastTapAt.remove(event.getPlayer().getUniqueId());
    }
}
