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
 * tap. A tap is a share of the player's PET POWER - their equipped pets'
 * combined hit, after every damage multiplier ({@link #petPower}) - so it
 * keeps pace with the ladder on its own; a flat number would be decisive
 * in the Meadow and invisible by the Genesis Core.
 * <p>
 * That share is {@link #BARE_TAP_POWER} bare-handed, or whatever a
 * registered tap POWER provider says - the held weapon (yield-tools)
 * supplies its own; the best one wins. Keyed MULTIPLIER providers then
 * scale the result (the level-10 pet milestone is one) - the same
 * composable idiom as every other damage modifier here.
 * <p>
 * Taps are rate-limited to one every {@link #MIN_TAP_INTERVAL_MILLIS}, so
 * an autoclicker gets exactly what a fast human does and no more. Faster
 * clicks still redirect the squad; they just don't hit. Auto Tap - the
 * bought perk - taps whatever the pets are on through the same limit.
 */
public final class TapService implements Listener {

    /** A bare-handed tap, as a share of pet power - about what a tap was before weapons existed. */
    public static final double BARE_TAP_POWER = 0.02;
    /** The fastest a player can tap for damage - about 6.7 taps a second. */
    public static final long MIN_TAP_INTERVAL_MILLIS = 150L;
    /**
     * Taps can land a killing blow at most once per this long; a tap that
     * would kill sooner leaves the cube on 1 HP instead. Matches the pets'
     * own one-second re-engage (PetCombatController's
     * BASE_AUTO_SWITCH_COOLDOWN_TICKS), the per-cube cost the whole pacing
     * model assumes.
     * <p>
     * The thing it stops: a click resets the pets' switch cooldown but a
     * tap lands at once, so a player spam-clicking from cube to cube kills
     * with taps alone. Late in a zone a squad is 5-25x a basic cube's HP,
     * which makes even a bare tap half a cube - ~3 kills a second against
     * the one a second the ladder is balanced on, and tools would make it
     * worse. Limiting tap KILLS, not tap damage, is what keeps tools worth
     * buying: a first version capped every tap at 10% of the cube's HP,
     * which flattened every tool past the third on common cubes. This way
     * tool damage scales freely - bigger numbers, faster kills on tough
     * cubes - and clicking still can never out-kill the pets.
     */
    public static final long TAP_KILL_COOLDOWN_MILLIS = 1000L;
    /** Auto Tap taps once every this many ticks - four a second, under the manual cap on purpose. */
    public static final long AUTO_TAP_INTERVAL_TICKS = 5L;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final OreCubeService cubes;
    private final Map<UUID, Long> lastTapAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTapKillAt = new ConcurrentHashMap<>();
    /** The part of a tap that didn't make a whole HP yet - see {@link #tap}. */
    private final Map<UUID, Double> fractionCarry = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<Player, PackPlayerProfile, Double>> multiplierProviders = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<Player, PackPlayerProfile, Double>> powerProviders = new ConcurrentHashMap<>();
    private final Map<String, TapListener> tapListeners = new ConcurrentHashMap<>();

    /**
     * Told about every tap that landed, after its damage is queued and
     * before it is flushed - so anything a listener queues on top (an echo
     * onto another cube, a lucky extra strike) lands in the same flush.
     */
    @FunctionalInterface
    public interface TapListener {
        void onTap(Player player, PackPlayerProfile profile, OreCube cube, double damage);
    }
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

    /**
     * Replaces the bare-handed share of pet power a tap deals - a held
     * weapon is one. The highest answer from every provider wins; nothing
     * can take a tap below {@link #BARE_TAP_POWER}.
     */
    public void registerTapListener(String key, TapListener listener) {
        tapListeners.put(key, listener);
    }

    public void unregisterTapListener(String key) {
        tapListeners.remove(key);
    }

    public void registerTapPowerProvider(String key, BiFunction<Player, PackPlayerProfile, Double> provider) {
        powerProviders.put(key, provider);
    }

    public void unregisterTapPowerProvider(String key) {
        powerProviders.remove(key);
    }

    /** The player's pets' combined hit, after damage boosts - what a tap is measured against. */
    public double petPower(PackPlayerProfile profile) {
        double total = 0;
        for (UUID petId : profile.getEquippedPetIds()) {
            PetInstance pet = profile.findPet(petId).orElse(null);
            if (pet != null) {
                total += packs.getEquipmentService().effectiveDamage(profile, pet);
            }
        }
        return total * packs.damageMultiplier(profile);
    }

    /** The share of pet power one tap deals right now, before multipliers. */
    public double tapPower(Player player, PackPlayerProfile profile) {
        double power = BARE_TAP_POWER;
        for (BiFunction<Player, PackPlayerProfile, Double> provider : powerProviders.values()) {
            Double value = provider.apply(player, profile);
            if (value != null) {
                power = Math.max(power, value);
            }
        }
        return power;
    }

    /** What one tap deals right now, exactly - fractions and all. */
    public double tapDamage(Player player, PackPlayerProfile profile) {
        return tapDamageAt(player, profile, tapPower(player, profile));
    }

    /**
     * What one tap WOULD deal at {@code power} x pet power - how the weapons
     * screen shows a weapon's damage whatever the player is holding while
     * they look at it. Exact: a 0.3x weapon on 1 pet power is 0.3 a tap,
     * and three taps take one HP (see {@link #tap}).
     */
    public double tapDamageAt(Player player, PackPlayerProfile profile, double power) {
        double multiplier = 1.0;
        for (BiFunction<Player, PackPlayerProfile, Double> provider : multiplierProviders.values()) {
            Double value = provider.apply(player, profile);
            if (value != null) {
                multiplier *= Math.max(0.0, value);
            }
        }
        return Math.max(0.0, petPower(profile) * power * multiplier);
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
        // Cube HP is whole numbers, taps aren't: the fraction a tap doesn't
        // spend is carried to the next one, so 0.3 a tap really is one HP
        // every three and a third taps rather than rounding to 0 or 1.
        double exact = tapDamage(player, profile);
        double total = exact + fractionCarry.getOrDefault(id, 0.0);
        long damage = (long) Math.floor(total);
        fractionCarry.put(id, total - damage);
        if (damage > 0 && damage >= cube.currentHp()) {
            Long lastKill = lastTapKillAt.get(id);
            if (lastKill != null && now - lastKill < TAP_KILL_COOLDOWN_MILLIS) {
                // Too soon for another tap kill - see TAP_KILL_COOLDOWN_MILLIS.
                damage = cube.currentHp() - 1;
            } else {
                lastTapKillAt.put(id, now);
            }
        }
        for (TapListener listener : tapListeners.values()) {
            listener.onTap(player, profile, cube, exact);
        }
        cubes.applyTap(player, cube, damage, exact);
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
        lastTapKillAt.remove(event.getPlayer().getUniqueId());
        fractionCarry.remove(event.getPlayer().getUniqueId());
    }
}
