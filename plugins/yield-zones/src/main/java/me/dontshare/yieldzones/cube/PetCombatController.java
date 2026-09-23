package me.dontshare.yieldzones.cube;

import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.leveling.MilestoneEffect;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.AttackMode;
import me.dontshare.yieldpacks.player.AutoTargetMode;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.player.SendMode;
import me.dontshare.yieldzones.boss.WorldBossService;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Makes equipped pets actually fight - the thing {@code OreCubeService}'s
 * flat per-click test damage stood in for.
 * <p>
 * Two completely separate settings decide how a click behaves, per the
 * player's own {@code PackPlayerProfile}:
 * <ul>
 *   <li>{@code SendMode.AUTO} - today's original, fully hands-off behavior.
 *       {@code AutoTargetMode.CLOSEST}/{@code STRONGEST}/{@code WEAKEST}
 *       (set via /autotarget) share one target across every equipped pet -
 *       a left-click (via {@link #assignSharedTarget}) picks it manually,
 *       and whenever there's no manual target yet, this auto-targets
 *       according to the mode ({@link #tickShared}).
 *   <li>{@code SendMode.MANUAL} (the default) - pets never auto-target at
 *       all; a click is required. {@code AttackMode} (toggled from the
 *       Settings GUI, not a command) picks what a click actually does:
 *       {@code SINGLE} sends exactly the next pet in rotation per click
 *       (via {@link #assignNextPetTo}), spreading pets across several
 *       targets as different cubes get clicked ({@link #tickSingle});
 *       {@code ALL} sends the whole currently-idle squad at once (via
 *       {@link #assignSharedTarget}) - like AUTO's shared-target modes,
 *       but with no auto-targeting fallback: nothing clicked means nobody
 *       fights ({@link #tickManualMulti}).
 * </ul>
 * Layered on top of both: a pet that's reached the AUTO_ATTACK milestone
 * (level 10 - see {@code PetLevelingService}) always independently targets
 * the closest live cube on its own, regardless of the player's SendMode -
 * that one pet never needs sending, while its non-milestone squadmates
 * still do.
 * <p>
 * Every per-pet piece of state here (single-send target, attack cooldown)
 * is keyed by the pet's own permanent {@code instanceId}, never by its
 * current position in the equipped list - a slot-indexed array was tried
 * first and had a real bug: unequipping ANY pet shifts every later pet's
 * list index down by one, so whichever pet slid into a slot silently
 * inherited that slot's stale target/cooldown, even though it never asked
 * to attack anything. Identity-based keys make that whole class of bug
 * impossible, and cost nothing extra now that every pet has a stable id.
 */
public final class PetCombatController implements Listener {

    private static final long TICK_INTERVAL = 4L;
    /** How long a pet spends "arriving" at a newly-assigned target before its first hit lands - kept at 1 (not 0) only so a same-tick re-target can't double-fire before the next scheduled tick. Used for every MANUAL send (single-pet click, or the shared-target reassignment in MANUAL+ALL) - those already require real player input each time, so there's no reason to slow them further. */
    private static final long ARRIVAL_DELAY_TICKS = 1L;
    /** Every pet attacks on this same shared base cadence, regardless of rarity/tier - only damage varies pet-to-pet. Shortened per-player by {@code YieldPacks#attackSpeedMultiplier} (a home for future attack-speed potions), so this is a baseline, not an absolute. */
    private static final long ATTACK_INTERVAL_TICKS = 20L;
    /**
     * Unlike a MANUAL send, {@code SendMode.AUTO} re-targets itself with zero
     * player input the instant a cube dies (see #tickShared) - it used to
     * reuse the same trivial {@link #ARRIVAL_DELAY_TICKS} for that, which
     * made fully hands-off Auto Mode strictly better than actively clicking
     * in Manual Mode. This is Auto's own, real switch-cooldown baseline
     * (1s) instead - the "Free Auto Send" tier (permission {@code
     * yieldpacks.automode}, already required just to toggle {@code
     * SendMode.AUTO} on in the Bag GUI), reducible by the Quick Reflexes
     * upgrade (see UpgradeService#autoSwitchSpeedMultiplier) down to
     * {@link #FREE_AUTO_SWITCH_FLOOR_TICKS}.
     */
    private static final long BASE_AUTO_SWITCH_COOLDOWN_TICKS = 20L;
    /** The paid tier - twice as fast as Free's own current (upgrade-adjusted) switch-cooldown, softcapped at {@link #PREMIUM_AUTO_SWITCH_FLOOR_TICKS} so it can never bypass the cooldown entirely regardless of stacked upgrades. */
    private static final String PREMIUM_AUTO_MODE_PERMISSION = SendMode.PREMIUM_AUTO_PERMISSION;
    private static final double PREMIUM_AUTO_SWITCH_MULTIPLIER = 2.0;
    private static final long PREMIUM_AUTO_SWITCH_FLOOR_TICKS = 4L;
    private static final long FREE_AUTO_SWITCH_FLOOR_TICKS = 8L;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final OreCubeService cubeService;
    private final WorldBossService worldBossService;

    // CLOSEST/STRONGEST/WEAKEST - the whole squad's shared target. Player-level state, no per-pet identity needed.
    /** The send mode each player's last tick ran under - how an AUTO to MANUAL switch is noticed. See {@link #tickPlayer}. */
    private final Map<UUID, SendMode> lastSendMode = new ConcurrentHashMap<>();
    private final Map<UUID, OreCube> sharedTargetByPlayer = new ConcurrentHashMap<>();
    // SINGLE - one independent target per pet instance, and where the next click's pet comes from.
    private final Map<UUID, Map<UUID, OreCube>> singleTargetsByPet = new ConcurrentHashMap<>();
    // Next-attack-ready tick, per pet instance - shared across every mode.
    private final Map<UUID, Map<UUID, Long>> cooldownsByPet = new ConcurrentHashMap<>();
    private long currentTick;

    // Keyed, composable extra-hit-chance registries (see yield-blocktree) -
    // rolled independently of each other in applyDamage, so a pet can land a
    // double AND a triple hit on the very same swing.
    private final Map<String, Function<PackPlayerProfile, Double>> doubleHitChanceProviders = new ConcurrentHashMap<>();
    private final Map<String, Function<PackPlayerProfile, Double>> tripleHitChanceProviders = new ConcurrentHashMap<>();
    /** Additive on top of {@link #BASE_CRIT_CHANCE} - same composable-registry shape as double/triple-hit, a home for a future crit-chance upgrade/shard. */
    private final Map<String, Function<PackPlayerProfile, Double>> critChanceProviders = new ConcurrentHashMap<>();

    /** Every hit has SOME chance to crit out of the box - this is a baseline "always a little exciting" rate, not something that requires an upgrade to ever see at all. */
    private static final double BASE_CRIT_CHANCE = 0.10;
    private static final double CRIT_MULTIPLIER = 2.0;

    public void registerCritChanceProvider(String key, Function<PackPlayerProfile, Double> provider) {
        critChanceProviders.put(key, provider);
    }

    public void unregisterCritChanceProvider(String key) {
        critChanceProviders.remove(key);
    }

    public void registerDoubleHitChanceProvider(String key, Function<PackPlayerProfile, Double> provider) {
        doubleHitChanceProviders.put(key, provider);
    }

    public void unregisterDoubleHitChanceProvider(String key) {
        doubleHitChanceProviders.remove(key);
    }

    public void registerTripleHitChanceProvider(String key, Function<PackPlayerProfile, Double> provider) {
        tripleHitChanceProviders.put(key, provider);
    }

    public void unregisterTripleHitChanceProvider(String key) {
        tripleHitChanceProviders.remove(key);
    }

    /**
     * Rolls a crit FIRST (doubling this one hit's own amount before it's
     * ever queued), then queues it and independently rolls the double- and
     * triple-hit chance registries on that same (possibly already-crit)
     * amount, so a lucky swing can stack a crit with an extra hit too - see
     * {@code OreCubeService#queueDamage}'s own Javadoc for why several
     * queued hits on the same cube in one tick combine into a single kill/
     * payout rather than each re-running it.
     */
    private void applyDamage(Player player, PackPlayerProfile profile, OreCube target, long amount, UUID petId) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double critChance = BASE_CRIT_CHANCE + sumProviders(critChanceProviders, profile);
        boolean crit = random.nextDouble() < critChance;
        long finalAmount = crit ? Math.round(amount * CRIT_MULTIPLIER) : amount;
        if (crit) {
            cubeService.playCritFlourish(player, target);
        }
        cubeService.queueDamage(player, target, finalAmount, petId);
        if (random.nextDouble() < sumProviders(doubleHitChanceProviders, profile)) {
            cubeService.queueDamage(player, target, finalAmount, petId);
        }
        if (random.nextDouble() < sumProviders(tripleHitChanceProviders, profile)) {
            cubeService.queueDamage(player, target, finalAmount, petId);
        }
    }

    private double sumProviders(Map<String, Function<PackPlayerProfile, Double>> providers, PackPlayerProfile profile) {
        double total = 0.0;
        for (Function<PackPlayerProfile, Double> provider : providers.values()) {
            total += provider.apply(profile);
        }
        return total;
    }

    public PetCombatController(JavaPlugin plugin, YieldPacks packs, OreCubeService cubeService, WorldBossService worldBossService) {
        this.plugin = plugin;
        this.packs = packs;
        this.cubeService = cubeService;
        this.worldBossService = worldBossService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /** Left-click handling for CLOSEST/STRONGEST/WEAKEST - overrides the whole squad's shared target. */
    public void assignSharedTarget(Player player, OreCube cube) {
        cubeService.setTarget(player, cube);
    }

    /** Sneak-to-recall: drops every shared and per-pet target assignment this player has, in every mode at once. */
    public void recallAll(Player player) {
        UUID id = player.getUniqueId();
        sharedTargetByPlayer.remove(id);
        Map<UUID, OreCube> targets = singleTargetsByPet.get(id);
        if (targets != null) {
            targets.clear();
        }
        cubeService.clearTarget(player);
    }

    /**
     * Left-click handling for SINGLE - sends exactly one pet at the clicked
     * cube per click, leaving every other pet's assignment untouched.
     * Repeat-clicking the same still-live cube is intentionally supported -
     * it reinforces that cube with another pet, one per click, rather than
     * being a no-op - so this always prefers the strongest currently-idle
     * pet (no live target of its own), meaning successive clicks on one
     * cube pile pets onto it highest-damage-first; only once every pet is
     * already busy fighting something else does it fall back to reassigning
     * the weakest one.
     * <p>
     * Once every pet is already on a target, the chosen pet can turn out to
     * already be the one attacking this exact cube - a no-op, not a
     * reassignment, and must be treated as one: resetting its cooldown here
     * too would let spam-clicking the same cube reset that pet's attack
     * timer back to {@link #ARRIVAL_DELAY_TICKS} every click, bypassing its
     * real attack-speed cooldown entirely.
     */
    public void assignNextPetTo(Player player, OreCube cube) {
        PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        List<UUID> equipped = profile.getEquippedPetIds();
        if (equipped.isEmpty()) {
            return;
        }
        UUID id = player.getUniqueId();
        Map<UUID, OreCube> targets = singleTargetsByPet.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        List<OreCube> live = cubeService.liveCubes(player);

        UUID chosen = equipped.stream()
                .filter(petId -> {
                    OreCube current = targets.get(petId);
                    return current == null || !live.contains(current);
                })
                .max(Comparator.comparingDouble(petId -> effectiveDamageOf(profile, petId)))
                .orElseGet(() -> equipped.stream()
                        .min(Comparator.comparingDouble(petId -> effectiveDamageOf(profile, petId)))
                        .orElse(null));
        if (chosen == null || cube.equals(targets.get(chosen))) {
            return;
        }

        targets.put(chosen, cube);
        // Newly sent - hold this one pet's first hit until it's had time to arrive, same as a shared-target change.
        cooldownsByPet.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(chosen, currentTick + ARRIVAL_DELAY_TICKS);
    }

    private double effectiveDamageOf(PackPlayerProfile profile, UUID petId) {
        return profile.findPet(petId).map(pet -> packs.getEquipmentService().effectiveDamage(profile, pet)).orElse(0.0);
    }

    private void tick() {
        currentTick += TICK_INTERVAL;
        for (Player player : Bukkit.getOnlinePlayers()) {
            tickPlayer(player);
        }
    }

    /**
     * One player's share of a combat tick - extracted so {@link
     * #onCubeKilled} can force an immediate extra pass for just the player
     * who scored a kill, rather than waiting up to {@link #TICK_INTERVAL}
     * ticks for the next scheduled one. Without that, a squad would keep
     * lunging at/idling on a cube's last position for a visible moment
     * after it's already gone. Safe to call twice in the same tick: {@code
     * flushDamage} is a no-op once its queue for this player is already
     * drained (which it will be, since {@link OreCubeKilledEvent} only ever
     * fires from inside a {@code flushDamage} call in the first place), so
     * the extra pass only ever recomputes targeting/visuals, never deals
     * extra damage.
     */
    private void tickPlayer(Player player) {
        if (worldBossService.isEngaged(player)) {
            // A world boss owns this player's pets right now - see
            // WorldBossService#isEngaged. Deferring entirely (not just
            // skipping the damage) is what stops this loop's own
            // setAttackTargets call from fighting WorldBossService's every
            // tick over which location the player's pets actually lunge at.
            return;
        }
        PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        List<UUID> equipped = profile.getEquippedPetIds();
        if (equipped.isEmpty()) {
            return;
        }
        List<OreCube> live = cubeService.liveCubes(player);

        // Switching auto-attack OFF must stop the fight, not just stop the
        // NEXT one: AUTO leaves its auto-picked cube as the player's
        // target, and MANUAL would otherwise keep the whole squad on it
        // until it died. A cube the player then clicks is theirs again.
        SendMode previous = lastSendMode.put(player.getUniqueId(), profile.getSendMode());
        if (previous == SendMode.AUTO && profile.getSendMode() == SendMode.MANUAL) {
            recallAll(player);
        }

        if (profile.getSendMode() == SendMode.AUTO) {
            tickShared(player, profile, profile.getAutoTargetMode(), equipped, live);
        } else if (profile.getAttackMode() == AttackMode.SINGLE) {
            tickSingle(player, profile, equipped, live);
        } else {
            tickManualMulti(player, profile, equipped, live);
        }
        // Every pet's hit this tick was queued, not applied - combine them
        // into one damage indicator/kill per targeted cube now that every
        // slot has had a chance to attack.
        cubeService.flushDamage(player);
    }

    /** Everything here is per-session - cubes don't outlive a visit - so none of it should outlive the player either. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastSendMode.remove(id);
        sharedTargetByPlayer.remove(id);
        singleTargetsByPet.remove(id);
        cooldownsByPet.remove(id);
    }

    /** Immediately recomputes this player's targeting/formation state the instant one of their cubes dies, instead of waiting for the next scheduled tick - see {@link #tickPlayer}. */
    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        tickPlayer(event.getPlayer());
    }

    private void tickShared(Player player, PackPlayerProfile profile, AutoTargetMode mode, List<UUID> equipped, List<OreCube> live) {
        UUID id = player.getUniqueId();
        Map<UUID, Long> cooldowns = cooldownsByPet.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        if (live.isEmpty()) {
            packs.getPetDisplayService().clearAttackTarget(player);
            return;
        }
        OreCube target = cubeService.currentTarget(player);
        if (target == null || !live.contains(target)) {
            target = autoTarget(mode, player, live);
            cubeService.setTarget(player, target);
        }
        if (target != sharedTargetByPlayer.get(id)) {
            sharedTargetByPlayer.put(id, target);
            long switchCooldown = autoSwitchCooldownTicks(player, profile);
            for (UUID petId : equipped) {
                cooldowns.put(petId, currentTick + switchCooldown);
            }
        }

        Location centered = target.center();
        Map<Integer, Location> slotTargets = new HashMap<>();
        for (int slot = 0; slot < equipped.size(); slot++) {
            slotTargets.put(slot, centered);
        }
        packs.getPetDisplayService().setAttackTargets(player, slotTargets, ringRadii(List.of(target)));

        OreCube finalTarget = target;
        for (int slot = 0; slot < equipped.size(); slot++) {
            UUID petId = equipped.get(slot);
            if (currentTick < cooldowns.getOrDefault(petId, 0L)) {
                continue;
            }
            PetInstance pet = profile.findPet(petId).orElse(null);
            if (pet == null) {
                continue;
            }
            cooldowns.put(petId, currentTick + Math.round(ATTACK_INTERVAL_TICKS / packs.attackSpeedMultiplier(profile)));
            double damage = packs.getEquipmentService().effectiveDamage(profile, pet) * packs.damageMultiplier(profile);
            applyDamage(player, profile, finalTarget, Math.round(damage), petId);
            packs.getPetDisplayService().playAttackLunge(player, slot);
        }
    }

    /**
     * {@code MANUAL+AttackMode.SINGLE} - every left-click (via
     * {@link #assignNextPetTo}) sends just the next pet in rotation, and
     * this tick method purely reacts to whatever that pet's own tracked
     * target already holds. The one addition is milestone auto-attack
     * (level 10 - see PetLevelingService): a pet that's reached it
     * independently targets the closest live cube whenever it has no
     * manual assignment of its own - that one pet never needs sending at
     * all, while its non-milestone squadmates still do.
     */
    private void tickSingle(Player player, PackPlayerProfile profile, List<UUID> equipped, List<OreCube> live) {
        UUID id = player.getUniqueId();
        Map<UUID, OreCube> targets = singleTargetsByPet.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Map<UUID, Long> cooldowns = cooldownsByPet.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        var leveling = packs.getPetLevelingService();

        // First pass: drop dead assignments, fill in milestone auto-attack
        // pets, and push the current visual state before anything attacks -
        // playAttackLunge (below) needs the target override already in
        // place to know where to lunge toward.
        Map<Integer, Location> slotTargets = new HashMap<>();
        Map<UUID, OreCube> effectiveTargets = new HashMap<>();
        for (UUID petId : equipped) {
            OreCube target = targets.get(petId);
            if (target != null && !live.contains(target)) {
                // Its cube died or despawned - clear the assignment, that pet returns to formation until re-sent.
                targets.remove(petId);
                target = null;
            }
            if (target == null && !live.isEmpty()) {
                PetInstance pet = profile.findPet(petId).orElse(null);
                if (pet != null && milestoneAutoAttacks(profile, pet)) {
                    target = closest(player, live);
                }
            }
            if (target != null) {
                effectiveTargets.put(petId, target);
            }
        }
        // Also drop tracking for anything no longer equipped at all (fused/deleted/unequipped).
        targets.keySet().retainAll(equipped);

        for (int slot = 0; slot < equipped.size(); slot++) {
            OreCube target = effectiveTargets.get(equipped.get(slot));
            if (target != null) {
                slotTargets.put(slot, target.center());
            }
        }
        if (slotTargets.isEmpty()) {
            packs.getPetDisplayService().clearAttackTarget(player);
        } else {
            packs.getPetDisplayService().setAttackTargets(player, slotTargets, ringRadii(effectiveTargets.values()));
        }

        // Second pass: damage/cooldowns now that the visual state matches.
        for (int slot = 0; slot < equipped.size(); slot++) {
            UUID petId = equipped.get(slot);
            OreCube target = effectiveTargets.get(petId);
            if (target == null || currentTick < cooldowns.getOrDefault(petId, 0L)) {
                continue;
            }
            PetInstance pet = profile.findPet(petId).orElse(null);
            if (pet == null) {
                continue;
            }
            cooldowns.put(petId, currentTick + Math.round(ATTACK_INTERVAL_TICKS / packs.attackSpeedMultiplier(profile)));
            double damage = packs.getEquipmentService().effectiveDamage(profile, pet) * packs.damageMultiplier(profile);
            applyDamage(player, profile, target, Math.round(damage), petId);
            packs.getPetDisplayService().playAttackLunge(player, slot);
        }
    }

    /**
     * {@code MANUAL+ALL} - like {@link #tickShared}, but with the
     * auto-targeting fallback removed: with nothing clicked, non-milestone
     * pets simply stand at formation instead of self-selecting a cube. A
     * milestone (level 10 AUTO_ATTACK) pet is the one exception - it always
     * independently targets the closest live cube, ignoring the squad's
     * shared target entirely, exactly like in {@link #tickSingle}.
     */
    private void tickManualMulti(Player player, PackPlayerProfile profile, List<UUID> equipped, List<OreCube> live) {
        UUID id = player.getUniqueId();
        Map<UUID, Long> cooldowns = cooldownsByPet.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        if (live.isEmpty()) {
            packs.getPetDisplayService().clearAttackTarget(player);
            return;
        }
        OreCube sharedTarget = cubeService.currentTarget(player);
        if (sharedTarget != null && !live.contains(sharedTarget)) {
            sharedTarget = null;
        }
        if (sharedTarget != sharedTargetByPlayer.get(id)) {
            if (sharedTarget == null) {
                sharedTargetByPlayer.remove(id);
            } else {
                sharedTargetByPlayer.put(id, sharedTarget);
            }
            for (UUID petId : equipped) {
                cooldowns.put(petId, currentTick + ARRIVAL_DELAY_TICKS);
            }
        }

        var leveling = packs.getPetLevelingService();
        Map<Integer, Location> slotTargets = new HashMap<>();
        Map<UUID, OreCube> effectiveTargets = new HashMap<>();
        for (UUID petId : equipped) {
            PetInstance pet = profile.findPet(petId).orElse(null);
            OreCube target = null;
            if (pet != null && milestoneAutoAttacks(profile, pet)) {
                target = closest(player, live);
            } else if (sharedTarget != null) {
                target = sharedTarget;
            }
            if (target != null) {
                effectiveTargets.put(petId, target);
            }
        }
        for (int slot = 0; slot < equipped.size(); slot++) {
            OreCube target = effectiveTargets.get(equipped.get(slot));
            if (target != null) {
                slotTargets.put(slot, target.center());
            }
        }
        if (slotTargets.isEmpty()) {
            packs.getPetDisplayService().clearAttackTarget(player);
        } else {
            packs.getPetDisplayService().setAttackTargets(player, slotTargets, ringRadii(effectiveTargets.values()));
        }

        for (int slot = 0; slot < equipped.size(); slot++) {
            UUID petId = equipped.get(slot);
            OreCube target = effectiveTargets.get(petId);
            if (target == null || currentTick < cooldowns.getOrDefault(petId, 0L)) {
                continue;
            }
            PetInstance pet = profile.findPet(petId).orElse(null);
            if (pet == null) {
                continue;
            }
            cooldowns.put(petId, currentTick + Math.round(ATTACK_INTERVAL_TICKS / packs.attackSpeedMultiplier(profile)));
            double damage = packs.getEquipmentService().effectiveDamage(profile, pet) * packs.damageMultiplier(profile);
            applyDamage(player, profile, target, Math.round(damage), petId);
            packs.getPetDisplayService().playAttackLunge(player, slot);
        }
    }

    /**
     * How long every equipped pet waits before its first hit on a newly
     * auto-picked shared target - Premium (if held) applies its own 2x
     * multiplier on top of whatever Free/upgrades already computed, then
     * floors at its own tighter softcap, so stacking upgrades can never let
     * either tier bypass the cooldown entirely.
     */
    private long autoSwitchCooldownTicks(Player player, PackPlayerProfile profile) {
        double speedMultiplier = packs.autoSwitchSpeedMultiplier(profile);
        if (player.hasPermission(PREMIUM_AUTO_MODE_PERMISSION)) {
            speedMultiplier *= PREMIUM_AUTO_SWITCH_MULTIPLIER;
            return Math.max(PREMIUM_AUTO_SWITCH_FLOOR_TICKS, Math.round(BASE_AUTO_SWITCH_COOLDOWN_TICKS / speedMultiplier));
        }
        return Math.max(FREE_AUTO_SWITCH_FLOOR_TICKS, Math.round(BASE_AUTO_SWITCH_COOLDOWN_TICKS / speedMultiplier));
    }

    private OreCube autoTarget(AutoTargetMode mode, Player player, List<OreCube> cubes) {
        return switch (mode) {
            case CLOSEST -> closest(player, cubes);
            case STRONGEST -> extremeTier(cubes, true);
            case WEAKEST -> extremeTier(cubes, false);
        };
    }

    private OreCube closest(Player player, List<OreCube> cubes) {
        OreCube nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (OreCube cube : cubes) {
            double distance = cube.location().distanceSquared(player.getLocation());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = cube;
            }
        }
        return nearest;
    }

    /** Compares by tier toughness (max HP) - {@code strongest} true picks the highest tier, false the lowest. */
    private OreCube extremeTier(List<OreCube> cubes, boolean strongest) {
        OreCube best = null;
        for (OreCube cube : cubes) {
            if (best == null
                    || (strongest && cube.tier().maxHp() > best.tier().maxHp())
                    || (!strongest && cube.tier().maxHp() < best.tier().maxHp())) {
                best = cube;
            }
        }
        return best;
    }

    /**
     * Whether a pet's level-10 AUTO_ATTACK milestone lets it pick its own
     * cube. It does - that is how a free player, without the bought Auto
     * Send, gets any hands-off fighting - EXCEPT once the player has
     * switched auto-attack off: an off switch that half the squad ignored
     * was the bug.
     */
    private boolean milestoneAutoAttacks(PackPlayerProfile profile, PetInstance pet) {
        return !profile.isAutoAttackOff()
                && packs.getPetLevelingService().hasMilestone(pet, MilestoneEffect.AUTO_ATTACK);
    }

    /**
     * The ring radius for each targeted cube bigger than a block, keyed by
     * the same centre point the targets are - so pets stand clear of a big
     * safe or a 2x2x2 boss block instead of inside it. Ordinary cubes are
     * left out and keep the normal ring.
     */
    private static Map<Location, Double> ringRadii(Collection<OreCube> targets) {
        Map<Location, Double> radii = new HashMap<>();
        for (OreCube cube : targets) {
            if (cube.tier().giant()) {
                radii.put(cube.center(), PetDisplayService.ringRadiusFor(cube.size()));
            }
        }
        return radii;
    }
}
