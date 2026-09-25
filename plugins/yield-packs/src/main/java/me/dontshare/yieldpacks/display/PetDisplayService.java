package me.dontshare.yieldpacks.display;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.packet.ItemDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.pet.PetLabels;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Renders equipped pets in the world as client-side item+text displays
 * (via yield-core's {@link ItemDisplayManager}/{@link TextDisplayManager}/
 * {@link PacketEntityManager}) that follow their owner in a config-driven
 * grid, hovering (sin bob) while the owner stands still and otherwise
 * gliding smoothly to the formation position via client-side interpolation.
 * <p>
 * Visibility is per-viewer: each online player only receives packets for
 * the owners their own {@link PetVisibility} setting allows, within
 * {@link PetDisplayConfig#viewDistance()} - see {@link #computeViewers}.
 * Packet volume is bounded by that view distance and by only sending
 * position updates once every {@link PetDisplayConfig#updateIntervalTicks()}
 * (paired with matching client-side interpolation, so movement still looks
 * smooth), not every tick.
 */
public final class PetDisplayService {

    /** Blocks a pet steps toward its target on each hit. */
    private static final double LUNGE_STEP = 0.3;

    /** Below this, a yaw change isn't worth a packet - it's far finer than anyone can see. */
    private static final float YAW_EPSILON_DEGREES = 0.5f;
    /**
     * How close another player has to be to see someone's pets lunge and
     * bob. Each
     * hit is a lunge and a return per pet per viewer; across a busy zone at
     * the full view distance that was most of the traffic, for a 0.3-block
     * hop nobody can make out from across the zone. The owner always sees
     * their own.
     */
    private static final double LUNGE_VIEW_RANGE = 16.0;
    /** Returns go a little further than lunges, so a viewer drifting outward between the two never keeps a pet stuck mid-lunge. */
    private static final double LUNGE_RETURN_RANGE = LUNGE_VIEW_RANGE + 8.0;

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final ItemIconFactory iconFactory;
    private volatile PetDisplayConfig config;

    private final Map<UUID, List<PetDisplayInstance>> ownerInstances = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastOwnerLocation = new ConcurrentHashMap<>();
    /** Where each owner's pets were last sent - an idle squad whose spots haven't moved sends nothing. */
    private final Map<UUID, List<Location>> lastSentPositions = new ConcurrentHashMap<>();
    /** Owners whose idle pets are currently bobbing client-side (see updateOwner). */
    private final Set<UUID> bobbing = ConcurrentHashMap.newKeySet();
    /** Per owner, the slots that lunged since the last cycle - each needs moving back and its interpolation window put back; see {@link #moveFor}. */
    private final Map<UUID, Set<Integer>> lungedSlots = new ConcurrentHashMap<>();
    /** Last yaw sent per slot, so a stationary player's pets stop re-sending a rotation that hasn't changed. */
    private final Map<UUID, float[]> lastYaws = new ConcurrentHashMap<>();
    /** The same, for what everyone but the owner was last sent - see {@link #othersSeeFormation}. */
    private final Map<UUID, List<Location>> lastSentToOthers = new ConcurrentHashMap<>();
    private final Map<UUID, float[]> lastYawsToOthers = new ConcurrentHashMap<>();
    /** Per-owner, per-equip-slot target overrides - a slot missing from the map stays in formation. Multiple slots may point at different targets at once (see single-send). */
    private final Map<UUID, Map<Integer, Location>> attackOverrides = new ConcurrentHashMap<>();
    /**
     * Per-owner inner ring radius for a target, keyed by the same target
     * location {@link #attackOverrides} uses. Missing means an ordinary
     * one-block cube and {@link #BASE_RING_RADIUS}. A 2x2x2 boss block is a
     * full block wider than that ring was sized for, and pets ringing it at
     * the normal distance would stand inside it.
     */
    private final Map<UUID, Map<Location, Double>> ringRadii = new ConcurrentHashMap<>();
    private long elapsedTicks;

    public PetDisplayService(JavaPlugin plugin, PlayerDataStore<PackPlayerProfile> playerStore,
                              Supplier<ItemRegistry> itemRegistry, Supplier<RarityRegistry> rarityRegistry,
                              ItemIconFactory iconFactory, PetDisplayConfig config) {
        this.plugin = plugin;
        this.playerStore = playerStore;
        this.itemRegistry = itemRegistry;
        this.rarityRegistry = rarityRegistry;
        this.iconFactory = iconFactory;
        this.config = config;
    }

    /** Applied on the next update cycle - doesn't reschedule the loop's own cadence, only a restart does that. */
    public void setConfig(PetDisplayConfig config) {
        this.config = config;
    }

    /**
     * Overrides each named equip slot to ring around its own mapped target
     * instead of the usual owner-relative formation - used while pets are
     * fighting something (see yield-zones' {@code PetCombatController}).
     * Slots sharing the same target ring around it together; slots mapped
     * to different targets fight independently (single-send can have
     * different pets on different cubes at once); any slot missing from
     * {@code slotTargets} stays in formation. Takes effect on the next
     * tick; call {@link #clearAttackTarget} to release everyone back to
     * formation.
     */
    public void setAttackTargets(Player owner, Map<Integer, Location> slotTargets) {
        setAttackTargets(owner, slotTargets, Map.of());
    }

    /**
     * {@link #setAttackTargets(Player, Map)} with an inner ring radius per
     * target, for targets bigger than a block - see {@link #ringRadii}.
     * Targets missing from {@code radii} use the normal radius.
     */
    public void setAttackTargets(Player owner, Map<Integer, Location> slotTargets, Map<Location, Double> radii) {
        attackOverrides.put(owner.getUniqueId(), Map.copyOf(slotTargets));
        if (radii.isEmpty()) {
            ringRadii.remove(owner.getUniqueId());
        } else {
            ringRadii.put(owner.getUniqueId(), Map.copyOf(radii));
        }
    }

    /** The inner ring radius that keeps pets clear of a target {@code size} blocks wide - exactly {@link #BASE_RING_RADIUS} for a normal cube. */
    public static double ringRadiusFor(double size) {
        return BASE_RING_RADIUS + (size - 1.0) / 2.0;
    }

    public void clearAttackTarget(Player owner) {
        attackOverrides.remove(owner.getUniqueId());
        ringRadii.remove(owner.getUniqueId());
    }

    /**
     * A quick, immediate forward lunge toward this slot's current attack
     * target - deliberately simple (no scheduled delay, no separately-
     * scheduled "spring back" step): the very next regular formation
     * update (at most {@link PetDisplayConfig#updateIntervalTicks()} ticks
     * away, and now correctly interpolated - see {@link #moveFor}) already
     * naturally recomputes and moves to the correct position, whether
     * that's still a ring around a live target or back to formation. An
     * earlier version of this method scheduled its own delayed lunge and
     * return via {@code runTaskLater}, entirely separate from that regular
     * loop's own timing - it caused pets to occasionally strand at a stale
     * position when a target died in the gap between the two schedules, so
     * it was reverted in favor of this simpler, single-source-of-truth
     * approach. Call this whenever a slot's pet actually lands a hit (see
     * yield-zones' {@code PetCombatController}). A no-op if the slot isn't
     * currently attacking anything. Purely visual - the attack sound is
     * played once per targeted cube (not per pet) by {@code
     * OreCubeService.flushDamage} instead, since several pets landing a hit
     * on the same cube in the same tick is the common case.
     */
    public void playAttackLunge(Player owner, int slot) {
        UUID ownerId = owner.getUniqueId();
        List<PetDisplayInstance> instances = ownerInstances.get(ownerId);
        Map<Integer, Location> overrides = attackOverrides.get(ownerId);
        if (instances == null || slot >= instances.size() || overrides == null) {
            return;
        }
        Location target = overrides.get(slot);
        if (target == null) {
            return;
        }
        Location current = resolvePositions(owner, instances, 0).get(slot);
        // A short, fixed step toward the target, level with the pet - not a
        // share of the distance to the cube's centre, which carried every
        // pet into the cube on each hit.
        Vector towardTarget = target.toVector().subtract(current.toVector()).setY(0);
        if (towardTarget.lengthSquared() > 1.0E-6) {
            towardTarget.normalize().multiply(LUNGE_STEP);
        }
        Location lunge = current.clone().add(towardTarget);

        PetDisplayInstance instance = instances.get(slot);
        // The next regular cycle sends it back - see moveFor.
        lungedSlots.computeIfAbsent(ownerId, id -> ConcurrentHashMap.newKeySet()).add(slot);
        Location ownerAt = owner.getLocation();
        // One packet per viewer: the pet alone, over its usual glide window.
        // A lunge used to shorten that window (and the name label's) first,
        // then hop the label too - four packets out and four back per hit per
        // viewer, the busiest traffic in a crowded zone, for a hop a tick
        // quicker and a label moving 0.3 blocks.
        for (UUID viewerId : viewersByOwner.getOrDefault(ownerId, Set.of())) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !withinLungeRange(viewer, ownerId, ownerAt, LUNGE_VIEW_RANGE)
                    || (!viewerId.equals(ownerId) && othersSeeFormation(ownerId))) {
                continue;
            }
            PacketEntityManager.teleportEntity(viewer, instance.itemEntityId(), lunge);
        }
    }

    private static final int XP_INDICATOR_RISE_TICKS = 10;
    private static final int XP_INDICATOR_LIFETIME_TICKS = 14;
    /** Right at the pet's own body rather than a full block above it - close enough to read as "this pet, specifically" at a glance. */
    private static final double XP_INDICATOR_START_HEIGHT = 0.15;
    private static final double XP_INDICATOR_RISE_DISTANCE = 0.35;
    /** Small - a per-hit tick, not a headline number like the earnings/damage indicators. */
    private static final float XP_INDICATOR_SCALE = 0.4f;

    private static final Particle LEVEL_UP_PARTICLE = Particle.TOTEM_OF_UNDYING;
    private static final int LEVEL_UP_PARTICLE_COUNT = 25;

    /** This pet's current formation position (the same math the regular render loop uses, with no hover bob) - empty if it isn't currently equipped/rendered for this owner. */
    private Optional<Location> locationOf(Player owner, UUID petInstanceId) {
        PackPlayerProfile profile = playerStore.getCached(owner.getUniqueId());
        List<PetDisplayInstance> instances = ownerInstances.get(owner.getUniqueId());
        if (profile == null || instances == null) {
            return Optional.empty();
        }
        List<UUID> equipped = profile.getEquippedPetIds();
        int slot = equipped.indexOf(petInstanceId);
        if (slot < 0 || slot >= instances.size()) {
            return Optional.empty();
        }
        return Optional.of(resolvePositions(owner, instances, 0.0).get(slot));
    }

    /** "+&lt;amount&gt; XP" floating up from this specific pet's own head, owner-only (their own pets' progress isn't anyone else's business) - same rising-text mechanic as OreCubeService's damage/earnings indicators, just anchored to a pet instead of a cube. A no-op if the pet isn't currently equipped/rendered. */
    public void showXpGain(Player owner, UUID petInstanceId, long xpAmount) {
        Optional<Location> location = locationOf(owner, petInstanceId);
        if (location.isEmpty()) {
            return;
        }
        Component text = Text.parse("<#FFD700>+<amount> XP</#FFD700>", Placeholder.unparsed("amount", Formatting.format(xpAmount)));

        int entityId = PacketEntityManager.nextEntityId();
        Location spawnAt = location.get().clone().add(0, XP_INDICATOR_START_HEIGHT, 0);

        PacketEntityManager.beginBundle(owner);
        TextDisplayManager.spawn(owner, entityId, spawnAt);
        TextDisplayManager.setBillboard(owner, entityId, TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(owner, entityId, 0x00000000);
        TextDisplayManager.setStyle(owner, entityId, true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setScale(owner, entityId, XP_INDICATOR_SCALE, XP_INDICATOR_SCALE, XP_INDICATOR_SCALE);
        TextDisplayManager.setText(owner, entityId, text);
        TextDisplayManager.setInterpolation(owner, entityId, 0, XP_INDICATOR_RISE_TICKS, XP_INDICATOR_RISE_TICKS);
        PacketEntityManager.endBundle(owner);

        PacketEntityManager.teleportEntity(owner, entityId, spawnAt.clone().add(0, XP_INDICATOR_RISE_DISTANCE, 0));
        Bukkit.getScheduler().runTaskLater(plugin, () -> PacketEntityManager.destroyEntity(owner, entityId), XP_INDICATOR_LIFETIME_TICKS);
    }

    /** A celebratory particle burst around this pet's current position - shown to every current viewer of it (not just the owner), since a level-up is a visible moment, not private progress like XP gain. A no-op if the pet isn't currently equipped/rendered. */
    public void playLevelUpEffect(Player owner, UUID petInstanceId) {
        Optional<Location> location = locationOf(owner, petInstanceId);
        if (location.isEmpty()) {
            return;
        }
        Location center = location.get().clone().add(0, 0.5, 0);
        for (UUID viewerId : viewersByOwner.getOrDefault(owner.getUniqueId(), Set.of())) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                viewer.spawnParticle(LEVEL_UP_PARTICLE, center, LEVEL_UP_PARTICLE_COUNT, 0.3, 0.4, 0.3, 0.02);
            }
        }
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, me.dontshare.yieldcore.perf.PerfTracker.timed("pets.display", this::tick), 1L, 1L);
    }

    /**
     * Every owner is still updated once per update-interval-ticks, but on
     * their own tick of it rather than all together: with a busy server the
     * all-at-once pass was a spike every fourth tick (the cause of most of
     * the tick-time spread in the load test) and three quiet ticks between.
     */
    private void tick() {
        elapsedTicks++;
        int interval = Math.max(1, config.updateIntervalTicks());
        long phase = elapsedTicks % interval;
        for (Player owner : Bukkit.getOnlinePlayers()) {
            if (Math.floorMod(owner.getUniqueId().hashCode(), interval) == phase) {
                updateOwner(owner);
            }
        }
    }

    /** Rebuilds this owner's pet instances from their current equip list. Call after any equip/unequip mutation. */
    public void refresh(Player owner) {
        UUID ownerId = owner.getUniqueId();
        PackPlayerProfile profile = playerStore.getOrCreate(ownerId);
        List<UUID> equippedIds = profile.getEquippedPetIds();
        if (unchanged(ownerInstances.get(ownerId), profile, equippedIds)) {
            // Every hatch ends in a refresh, and most hatches equip nothing -
            // rebuilding anyway destroyed and re-spawned the whole squad for
            // every viewer in range.
            return;
        }
        despawnAll(owner);
        if (equippedIds.isEmpty()) {
            ownerInstances.remove(ownerId);
            return;
        }
        List<PetDisplayInstance> instances = new ArrayList<>(equippedIds.size());
        for (UUID instanceId : equippedIds) {
            profile.findPet(instanceId).ifPresent(pet ->
                    instances.add(new PetDisplayInstance(pet.getItemId(), pet.getLevel(), pet.isShiny(), PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId())));
        }
        ownerInstances.put(ownerId, instances);
        // Deliberately simple: a full despawn+rebuild rather than diffing which
        // pets are unchanged. The next tick() cycle (within update-interval-ticks,
        // a few hundred ms at most) re-spawns for every in-range viewer, so any
        // equip/unequip causes a brief, imperceptible flicker rather than a
        // seamless in-place swap - an acceptable v1 trade-off for far simpler,
        // harder-to-get-wrong bookkeeping.
    }

    /** Whether the squad on screen already is exactly the one equipped: same pets, same order, same levels and shine. */
    private static boolean unchanged(List<PetDisplayInstance> current, PackPlayerProfile profile, List<UUID> equippedIds) {
        if (current == null || current.size() != equippedIds.size()) {
            return false;
        }
        for (int i = 0; i < equippedIds.size(); i++) {
            PetDisplayInstance shown = current.get(i);
            var pet = profile.findPet(equippedIds.get(i)).orElse(null);
            if (pet == null || !pet.getItemId().equals(shown.itemId()) || pet.getLevel() != shown.level()
                    || pet.isShiny() != shown.shiny()) {
                return false;
            }
        }
        return true;
    }

    /** Re-evaluates what this one viewer should see of every owner's pets. Call after their PetVisibility setting changes. */
    public void refreshViewer(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        for (Player owner : Bukkit.getOnlinePlayers()) {
            List<PetDisplayInstance> instances = ownerInstances.get(owner.getUniqueId());
            if (instances == null || instances.isEmpty()) {
                continue;
            }
            Set<UUID> shouldSee = computeViewers(owner);
            Set<UUID> currentlySeeing = viewersByOwner.computeIfAbsent(owner.getUniqueId(), id -> ConcurrentHashMap.newKeySet());
            boolean shouldSeeNow = shouldSee.contains(viewerId);
            boolean seeingNow = currentlySeeing.contains(viewerId);
            if (shouldSeeNow && !seeingNow) {
                boolean isOwner = viewerId.equals(owner.getUniqueId());
                boolean formation = !isOwner && othersSeeFormation(owner.getUniqueId());
                List<Location> positions = formation ? resolveFormation(owner, instances) : resolvePositions(owner, instances, 0);
                List<Float> yaws = formation ? formationYaws(owner, instances.size()) : computeYaws(owner, positions);
                spawnFor(viewer, instances, positions, yaws, labelsFor(viewerId, owner.getUniqueId()));
                currentlySeeing.add(viewerId);
            } else if (!shouldSeeNow && seeingNow) {
                despawnFor(viewer, instances, labelsFor(viewerId, owner.getUniqueId()));
                currentlySeeing.remove(viewerId);
            }
        }
    }

    /**
     * Destroys this owner's pets for every viewer currently seeing them and
     * forgets their tracked state. Called both on quit AND from
     * {@link #refresh} (a live, still-connected owner rebuilding their own
     * display with fresh entity ids) - those two callers need opposite
     * treatment of the owner-as-their-own-viewer case (the owner normally
     * sees their own equipped pets, so they're commonly in this set), which
     * is exactly why this must NEVER unconditionally skip self: a quitting
     * owner's own PacketEvents {@code User} mapping is already torn down by
     * the time {@code PlayerQuitEvent} fires, so sending them anything
     * throws - but a `refresh()`-time owner is still fully connected and
     * genuinely needs their own old entities destroyed before new ones
     * spawn, or those old ones (wherever they last were - mid-attack-ring,
     * mid-lunge) are orphaned forever on the owner's own screen while a
     * fresh, correctly-positioned duplicate spawns alongside them. The
     * per-viewer try/catch below handles both cases correctly on its own,
     * without needing to know which caller this is: it succeeds normally
     * for a connected owner, and simply logs-and-continues for a
     * disconnecting one - a bare loop here would let one exception (the
     * quitting case) abort cleanup for every other real viewer too.
     */
    public void despawnAll(Player owner) {
        UUID ownerId = owner.getUniqueId();
        List<PetDisplayInstance> instances = ownerInstances.get(ownerId);
        Set<UUID> viewers = viewersByOwner.remove(ownerId);
        if (instances != null && viewers != null) {
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null) {
                    continue;
                }
                try {
                    despawnFor(viewer, instances, labelsFor(viewerId, ownerId));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to despawn " + owner.getName() + "'s pet display for viewer "
                            + viewer.getName() + ": " + e.getMessage());
                }
            }
        }
        lastOwnerLocation.remove(ownerId);
        lastSentPositions.remove(ownerId);
        lastSentToOthers.remove(ownerId);
        lastYawsToOthers.remove(ownerId);
        othersFacing.remove(ownerId);
        bobbing.remove(ownerId);
        lungedSlots.remove(ownerId);
        lastYaws.remove(ownerId);
    }

    /**
     * A player leaving, as both an owner and a viewer: their own pets go
     * (and every map keyed by them, so an owner who never returns leaves
     * nothing behind), and they drop out of every other owner's viewer set.
     * Left in those sets, a rejoin would count as "already seeing" someone
     * else's pets and only ever receive moves for entities their fresh
     * client never spawned - other players' pets invisible until they walk
     * out of range and back.
     */
    public void onQuit(Player player) {
        UUID playerId = player.getUniqueId();
        despawnAll(player);
        ownerInstances.remove(playerId);
        attackOverrides.remove(playerId);
        ringRadii.remove(playerId);
        for (Set<UUID> viewers : viewersByOwner.values()) {
            viewers.remove(playerId);
        }
    }

    /** Full teardown for every tracked owner - call on plugin disable so nothing lingers client-side. */
    public void shutdown() {
        for (UUID ownerId : List.copyOf(ownerInstances.keySet())) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null) {
                despawnAll(owner);
            }
        }
        ownerInstances.clear();
        viewersByOwner.clear();
        lastOwnerLocation.clear();
        lastSentPositions.clear();
        lastSentToOthers.clear();
        lastYawsToOthers.clear();
        othersFacing.clear();
        bobbing.clear();
        lungedSlots.clear();
        lastYaws.clear();
    }

    private void updateOwner(Player owner) {
        UUID ownerId = owner.getUniqueId();
        List<PetDisplayInstance> instances = ownerInstances.get(ownerId);
        if (instances == null || instances.isEmpty()) {
            return;
        }

        Location current = owner.getLocation();
        Location last = lastOwnerLocation.get(ownerId);
        boolean stationary = last != null && last.getWorld() != null && last.getWorld().equals(current.getWorld())
                && last.distance(current) < config.movementThreshold();
        lastOwnerLocation.put(ownerId, current.clone());

        // A squad whose owner is standing still - idle OR fighting - bobs on
        // the CLIENT: its spots are sent once, then one translation keyframe
        // per half bob per pet glides it up and down, instead of re-sending
        // every pet's position every update just to move it a few
        // centimetres. Fighting used to keep a server-driven bob, which
        // meant every pet of everyone fighting went out to every viewer five
        // times a second - in a busy zone, nearly all of this service's
        // traffic, for a few centimetres of hover.
        boolean clientBob = stationary;
        List<Location> positions = resolvePositions(owner, instances, 0.0);
        // Tracks the owner's live yaw (not a fixed world-space constant) for
        // formation slots, so pets keep facing back toward the player as
        // they turn; attacking slots instead face whatever they're
        // targeting - see computeYaws.
        List<Float> yaws = computeYaws(owner, positions);

        Set<UUID> shouldSee = computeViewers(owner, current);
        Set<UUID> currentlySeeing = viewersByOwner.computeIfAbsent(ownerId, id -> ConcurrentHashMap.newKeySet());

        // Per pet, not per squad: one pet sent to a new cube used to resend
        // every pet's spot and facing to every viewer. A pet that lunged since
        // the last cycle goes back to its spot too.
        Set<Integer> lunged = lungedSlots.remove(ownerId);
        boolean[] turned = turnedSlots(lastYaws, ownerId, yaws);
        boolean[] moved = movedSlots(lastSentPositions, ownerId, positions);
        // Everyone else - see othersSeeFormation - with their own record of
        // what they were last sent, since it can differ from the owner's.
        boolean formationForOthers = othersSeeFormation(ownerId);
        List<Location> othersPositions = formationForOthers ? resolveFormation(owner, instances) : positions;
        List<Float> othersYaws = formationForOthers ? formationYaws(owner, instances.size()) : yaws;
        boolean[] othersTurned = turnedSlots(lastYawsToOthers, ownerId, othersYaws);
        boolean[] othersMoved = movedSlots(lastSentToOthers, ownerId, othersPositions);

        Float bobTarget = null;
        int bobTicks = 0;
        // Everyone else's view of this squad bobs at half the rate - the
        // same gentle hover, slower, for half the keyframes to every viewer
        // in a crowd.
        Float othersBobTarget = null;
        int othersBobTicks = 0;
        int updateTicks = config.updateIntervalTicks();
        if (clientBob && config.hoverAmplitude() > 0) {
            int half = Math.max(updateTicks, config.hoverPeriodTicks() / 2);
            int othersHalf = half * 2;
            boolean starting = bobbing.add(ownerId);
            if (starting || elapsedTicks % half < updateTicks) {
                boolean up = (elapsedTicks / half) % 2 == 0;
                bobTarget = (float) (up ? config.hoverAmplitude() : -config.hoverAmplitude());
                bobTicks = half;
            }
            if (starting || elapsedTicks % othersHalf < updateTicks) {
                boolean up = (elapsedTicks / othersHalf) % 2 == 0;
                othersBobTarget = (float) (up ? config.hoverAmplitude() : -config.hoverAmplitude());
                othersBobTicks = othersHalf;
            }
        } else if (bobbing.remove(ownerId)) {
            // Moving again: settle back onto the real spot.
            bobTarget = 0f;
            bobTicks = updateTicks;
            othersBobTarget = 0f;
            othersBobTicks = updateTicks;
        }

        // Driven off the two viewer sets rather than every online player:
        // whether someone sees these pets has nothing to do with how many
        // people are on the server.
        for (UUID viewerId : List.copyOf(currentlySeeing)) {
            if (shouldSee.contains(viewerId)) {
                continue;
            }
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                String outer = me.dontshare.yieldcore.perf.PerfTracker.enter("pets.display.despawn");
                try {
                    despawnFor(viewer, instances, labelsFor(viewerId, ownerId));
                } finally {
                    me.dontshare.yieldcore.perf.PerfTracker.exit(outer);
                }
            }
            currentlySeeing.remove(viewerId);
        }
        for (UUID viewerId : shouldSee) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null) {
                continue;
            }
            boolean isOwner = viewerId.equals(ownerId);
            if (currentlySeeing.add(viewerId)) {
                String outer = me.dontshare.yieldcore.perf.PerfTracker.enter("pets.display.spawn");
                try {
                    spawnFor(viewer, instances, isOwner ? positions : othersPositions, isOwner ? yaws : othersYaws,
                            labelsFor(viewerId, ownerId));
                } finally {
                    me.dontshare.yieldcore.perf.PerfTracker.exit(outer);
                }
            } else {
                boolean[] viewerMoved = isOwner ? moved : othersMoved;
                boolean[] viewerTurned = isOwner ? turned : othersTurned;
                Set<Integer> returning = lunged != null && (isOwner || !formationForOthers)
                        && withinLungeRange(viewer, ownerId, current, LUNGE_RETURN_RANGE) ? lunged : null;
                if (viewerMoved != null || viewerTurned != null || returning != null) {
                    String outer = me.dontshare.yieldcore.perf.PerfTracker.enter(viewerMoved != null ? "pets.display.move"
                            : viewerTurned != null ? "pets.display.turn" : "pets.display.return");
                    try {
                        moveFor(viewer, instances, isOwner ? positions : othersPositions, isOwner ? yaws : othersYaws,
                                viewerMoved, viewerTurned, returning, labelsFor(viewerId, ownerId));
                    } finally {
                        me.dontshare.yieldcore.perf.PerfTracker.exit(outer);
                    }
                }
                // A far viewer can't make out a few centimetres of hover on
                // someone else's pets; they still get the settle back to 0.
                Float viewerBob = isOwner ? bobTarget : othersBobTarget;
                if (viewerBob != null && (viewerBob == 0f || withinLungeRange(viewer, ownerId, current, LUNGE_VIEW_RANGE))) {
                    String outer = me.dontshare.yieldcore.perf.PerfTracker.enter("pets.display.bob");
                    try {
                        sendBob(viewer, instances, viewerBob, isOwner ? bobTicks : othersBobTicks, labelsFor(viewerId, ownerId));
                    } finally {
                        me.dontshare.yieldcore.perf.PerfTracker.exit(outer);
                    }
                }
            }
        }
    }

    /** One bob keyframe for every pet (and its label) - the client glides there over {@code ticks}. */
    private void sendBob(Player viewer, List<PetDisplayInstance> instances, float y, int ticks, boolean labels) {
        PacketEntityManager.beginBundle(viewer);
        for (PetDisplayInstance instance : instances) {
            ItemDisplayManager.setTranslationInterpolated(viewer, instance.itemEntityId(), 0f, y, 0f, ticks);
            if (labels) {
                ItemDisplayManager.setTranslationInterpolated(viewer, instance.textEntityId(), 0f, y, 0f, ticks);
            }
        }
        PacketEntityManager.endBundle(viewer);
    }

    /** Whether any pet's spot moved since it was last sent - and records the new spots if so. */
    /** Which slots' spots moved since they were last sent - null when none did. */
    private boolean[] movedSlots(Map<UUID, List<Location>> lastSent, UUID ownerId, List<Location> positions) {
        List<Location> previous = lastSent.get(ownerId);
        boolean[] moved = null;
        for (int i = 0; i < positions.size(); i++) {
            Location a = previous != null && i < previous.size() ? previous.get(i) : null;
            Location b = positions.get(i);
            if (a == null || a.getWorld() != b.getWorld() || a.distanceSquared(b) > 1.0e-4) {
                if (moved == null) {
                    moved = new boolean[positions.size()];
                }
                moved[i] = true;
            }
        }
        if (moved != null || previous == null || previous.size() != positions.size()) {
            List<Location> copy = new ArrayList<>(positions.size());
            for (Location position : positions) {
                copy.add(position.clone());
            }
            lastSent.put(ownerId, copy);
        }
        return moved;
    }

    /** Whether any slot's yaw actually moved since the last cycle - if none did, the rotation packet has nothing to say. */
    /** Which slots' facings turned since they were last sent - null when none did. */
    private boolean[] turnedSlots(Map<UUID, float[]> lastSent, UUID ownerId, List<Float> yaws) {
        float[] previous = lastSent.get(ownerId);
        boolean[] turned = null;
        for (int i = 0; i < yaws.size(); i++) {
            if (previous == null || i >= previous.length || Math.abs(previous[i] - yaws.get(i)) > YAW_EPSILON_DEGREES) {
                if (turned == null) {
                    turned = new boolean[yaws.size()];
                }
                turned[i] = true;
            }
        }
        if (turned != null || previous == null || previous.length != yaws.size()) {
            float[] snapshot = new float[yaws.size()];
            for (int i = 0; i < snapshot.length; i++) {
                snapshot[i] = yaws.get(i);
            }
            lastSent.put(ownerId, snapshot);
        }
        return turned;
    }

    /**
     * One yaw per slot: formation slots face the owner's own live facing
     * (matching {@code config.yawDegrees()}'s model-facing correction);
     * slots currently ringed around an attack target instead face that
     * target, using the same correction so both cases agree on which way
     * this particular model's "front" points.
     */
    private List<Float> computeYaws(Player owner, List<Location> positions) {
        Map<Integer, Location> overrides = attackOverrides.get(owner.getUniqueId());
        float formationYaw = owner.getLocation().getYaw() + config.yawDegrees();
        List<Float> yaws = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            Location target = overrides != null ? overrides.get(i) : null;
            yaws.add(target != null ? yawTowards(positions.get(i), target) + config.yawDegrees() : formationYaw);
        }
        return yaws;
    }

    /** The yaw (Minecraft convention: 0 = south, increasing clockwise viewed from above) pointing from {@code from} toward {@code to}. */
    private float yawTowards(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * Formation positions for every slot, except slots named in an active
     * attack override - those ring around whatever they're mapped to
     * instead. Slots that share the same target ring around it together
     * (the normal case - the whole squad on one cube); slots mapped to
     * different targets each ring independently around their own (single-
     * send spreading pets across multiple cubes). Any slot missing from the
     * override stays in formation, leaving a visible gap rather than the
     * rest shifting to fill it.
     */
    private List<Location> resolvePositions(Player owner, List<PetDisplayInstance> instances, double hoverOffset) {
        int count = instances.size();
        Map<Integer, Location> overrides = attackOverrides.get(owner.getUniqueId());
        // Always start from the full bulk formation (one shared facing/right
        // trig derivation for every slot, and the Huge-aware row packing -
        // see PetFormation#positionsFor),
        // even when some slots will be overwritten with a ring position
        // below. Combat with only SOME slots ringed (a partial single-send
        // spread, or a squad bigger than the current target's own ring) is
        // the common case, not the rare one the old per-slot PetFormation
        // #positionFor fallback loop here used to assume - falling back to
        // that per-slot call for every uncovered slot silently reintroduced
        // the exact per-pet trig cost this class was fixed to eliminate.
        List<Location> positions = positionsFor(owner, instances, hoverOffset);
        if (overrides != null && !overrides.isEmpty()) {
            Map<Location, Double> radii = ringRadii.getOrDefault(owner.getUniqueId(), Map.of());
            // Slots are taken in slot order, not the override map's own
            // (Map.copyOf's order is arbitrary and changes whenever the set
            // of targeted slots does). Without this, sending ONE pet to a
            // new cube reshuffled every other pet's place around its ring,
            // and the whole squad visibly swapped places at once - which
            // read as all the pets flying back and out again.
            Map<Location, List<Integer>> slotsByTarget = new LinkedHashMap<>();
            for (Map.Entry<Integer, Location> entry : new java.util.TreeMap<>(overrides).entrySet()) {
                slotsByTarget.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
            }
            for (Map.Entry<Location, List<Integer>> group : slotsByTarget.entrySet()) {
                List<Integer> slots = group.getValue();
                double radius = radii.getOrDefault(group.getKey(), BASE_RING_RADIUS);
                // The target is the cube's centre; its size falls out of the
                // ring radius (see ringRadiusFor), and with it the floor the
                // cube stands on - pets stand on that floor, around the cube,
                // instead of floating at its middle.
                double targetSize = Math.max(1.0, 2.0 * (radius - BASE_RING_RADIUS) + 1.0);
                double floorY = group.getKey().getY() - targetSize / 2.0;
                // A Huge pet is 2.5x as wide - push the ring out so it
                // doesn't stand inside the cube either.
                double widest = 0.0;
                for (int slot : slots) {
                    if (slot < instances.size()) {
                        widest = Math.max(widest, modelHalfSize(instances.get(slot)));
                    }
                }
                radius += Math.max(0.0, widest - modelHalfSize(null));
                for (int i = 0; i < slots.size(); i++) {
                    int slot = slots.get(i);
                    if (slot < positions.size()) {
                        Location ring = ringPositionFor(group.getKey(), i, slots.size(), radius);
                        ring.setY(floorY + modelHalfSize(slot < instances.size() ? instances.get(slot) : null) + hoverOffset);
                        positions.set(slot, ring);
                    }
                }
            }
        }
        return positions;
    }

    /** Half the rendered size of this pet's model - how far above the floor its centre sits, and how far it reaches sideways. Null means a normal-size pet. */
    private double modelHalfSize(PetDisplayInstance instance) {
        float scale = config.scale();
        if (instance != null && isHuge(instance.itemId())) {
            scale *= config.hugeScaleMultiplier();
        }
        return scale * 0.5;
    }

    private boolean isHuge(String itemId) {
        return itemRegistry.get().find(itemId).map(ItemDefinition::huge).orElse(false);
    }

    /** Pets per concentric ring before spilling out to a new, wider one - past this many on one ring they'd visibly overlap. */
    private static final int PETS_PER_RING = 8;
    private static final double BASE_RING_RADIUS = 1.3;
    private static final double RING_RADIUS_STEP = 0.9;

    /**
     * Rings, not a single ever-more-crowded circle: past {@link #PETS_PER_RING}
     * pets on one target, the rest spill onto a second, wider ring (then a
     * third, and so on - no hard cap on ring count, same "just keep
     * extending" philosophy {@link PetFormation}'s own grid already uses).
     * Without this, an equip cap raised well past a normal team size (donor
     * ranks/bonus slots) crams every pet onto one fixed-radius circle -
     * visually a "death ball" rather than a formation.
     */
    private Location ringPositionFor(Location center, int ringIndex, int ringCount, double baseRadius) {
        int ring = ringIndex / PETS_PER_RING;
        int indexInRing = ringIndex % PETS_PER_RING;
        int countInThisRing = Math.min(PETS_PER_RING, ringCount - ring * PETS_PER_RING);
        double radius = baseRadius + ring * RING_RADIUS_STEP;
        double angle = 2 * Math.PI * indexInRing / Math.max(1, countInThisRing);
        double x = center.getX() + radius * Math.cos(angle);
        double z = center.getZ() + radius * Math.sin(angle);
        return new Location(center.getWorld(), x, center.getY(), z);
    }

    /**
     * Whether players other than the owner see this squad trailing its owner
     * (see {@link #othersFacing}) rather than exactly what the owner sees.
     * <p>
     * A player's cubes exist only on their own screen, so to anyone else a
     * fighting squad is pets circling and lunging at empty air - and with a
     * crowd, re-sending every squad's every change of target, and every turn
     * of every owner's camera, to every viewer was nearly all this service's
     * traffic. pet-display.yml's combat.shown-to-others shows others the
     * fighting again.
     */
    private boolean othersSeeFormation(UUID ownerId) {
        if (!config.combatShownToOthers()) {
            return true;
        }
        Map<Integer, Location> overrides = attackOverrides.get(ownerId);
        return overrides == null || overrides.isEmpty();
    }

    private List<Location> resolveFormation(Player owner, List<PetDisplayInstance> instances) {
        List<Boolean> huge = new ArrayList<>(instances.size());
        for (PetDisplayInstance instance : instances) {
            huge.add(isHuge(instance.itemId()));
        }
        Location at = owner.getLocation();
        at.setYaw(othersFacing(owner));
        return PetFormation.positionsFor(at, instances.size(), huge, config);
    }

    /** How far an owner has to walk before everyone else's view of their formation turns to trail them. */
    private static final double OTHERS_HEADING_STEP = 0.75;
    /** Per owner: the heading others see the formation trail, and where the owner stood when it was set. */
    private record Heading(float yaw, Location from) {
    }

    private final Map<UUID, Heading> othersFacing = new ConcurrentHashMap<>();

    /**
     * The facing everyone but the owner sees a formation at: the way the
     * owner last walked, not where they're looking. The formation stands
     * behind that facing, and a player's camera is never still - followed
     * exactly, every glance re-sent every pet to every viewer. Pets trailing
     * the way you walk is also simply how following pets look.
     */
    private float othersFacing(Player owner) {
        Location at = owner.getLocation();
        Heading held = othersFacing.get(owner.getUniqueId());
        if (held == null || held.from().getWorld() != at.getWorld()) {
            othersFacing.put(owner.getUniqueId(), new Heading(at.getYaw(), at));
            return at.getYaw();
        }
        double dx = at.getX() - held.from().getX();
        double dz = at.getZ() - held.from().getZ();
        if (dx * dx + dz * dz < OTHERS_HEADING_STEP * OTHERS_HEADING_STEP) {
            return held.yaw();
        }
        float heading = (float) Math.toDegrees(Math.atan2(-dx, dz));
        othersFacing.put(owner.getUniqueId(), new Heading(heading, at));
        return heading;
    }


    private List<Float> formationYaws(Player owner, int count) {
        float formationYaw = othersFacing(owner) + config.yawDegrees();
        List<Float> yaws = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            yaws.add(formationYaw);
        }
        return yaws;
    }

    private List<Location> positionsFor(Player owner, List<PetDisplayInstance> instances, double hoverOffset) {
        int count = instances.size();
        List<Boolean> huge = new ArrayList<>(count);
        for (PetDisplayInstance instance : instances) {
            huge.add(isHuge(instance.itemId()));
        }
        List<Location> positions = PetFormation.positionsFor(owner.getLocation(), count, huge, config);
        if (hoverOffset != 0.0) {
            for (Location pos : positions) {
                pos.setY(pos.getY() + hoverOffset);
            }
        }
        return positions;
    }

    private Set<UUID> computeViewers(Player owner) {
        return computeViewers(owner, owner.getLocation());
    }

    /**
     * Who can currently see {@code owner}'s pets.
     * <p>
     * Asks the world for players already near the owner rather than walking
     * the whole online list. Scanning everyone here, once per owner, made the
     * display cost grow with the square of the player count every cycle -
     * several times a second - when all but a handful of those players are
     * nowhere near. {@code ownerLocation} is passed in so it isn't re-fetched
     * (and re-allocated) for every candidate.
     */
    /** Blocks past the view distance a viewer who already sees a squad keeps it - see computeViewers. */
    private static final double VIEW_HYSTERESIS = 8.0;

    private Set<UUID> computeViewers(Player owner, Location ownerLocation) {
        Set<UUID> result = new HashSet<>();
        double viewDistance = config.viewDistance();
        double viewDistanceSquared = viewDistance * viewDistance;
        // Someone already seeing this squad keeps it a little past the view
        // distance: without that, anyone walking along the edge had the whole
        // squad destroyed and spawned again every few steps.
        double keepDistance = viewDistance + VIEW_HYSTERESIS;
        double keepDistanceSquared = keepDistance * keepDistance;
        Set<UUID> seeing = viewersByOwner.getOrDefault(owner.getUniqueId(), Set.of());
        for (Player viewer : owner.getWorld().getNearbyPlayers(ownerLocation, keepDistance)) {
            // getNearbyPlayers works to a bounding box; keep the exact radius.
            double distanceSquared = viewer.getLocation().distanceSquared(ownerLocation);
            if (distanceSquared > keepDistanceSquared
                    || (distanceSquared > viewDistanceSquared && !seeing.contains(viewer.getUniqueId()))) {
                continue;
            }
            PackPlayerProfile viewerProfile = playerStore.getCached(viewer.getUniqueId());
            PetVisibility visibility = viewerProfile != null ? viewerProfile.getPetVisibility() : PetVisibility.ALL;
            boolean isSelf = viewer.getUniqueId().equals(owner.getUniqueId());
            boolean visible = switch (visibility) {
                case ALL -> true;
                case MINE_ONLY -> isSelf;
                case OTHERS_ONLY -> !isSelf;
                case NONE -> false;
            };
            if (visible) {
                result.add(viewer.getUniqueId());
            }
        }
        return result;
    }

    private void spawnFor(Player viewer, List<PetDisplayInstance> instances, List<Location> positions, List<Float> yaws, boolean labels) {
        for (int i = 0; i < instances.size(); i++) {
            PetDisplayInstance instance = instances.get(i);
            Location pos = positions.get(i);
            float yaw = yaws.get(i);
            itemRegistry.get().find(instance.itemId()).ifPresent(item -> {
                PacketEntityManager.beginBundle(viewer);

                ItemDisplayManager.spawn(viewer, instance.itemEntityId(), pos);
                ItemDisplayManager.setItem(viewer, instance.itemEntityId(), iconFactory.baseIcon(item).build());
                // Huge pets (see ItemDefinition#huge) render visibly larger -
                // their damage already works completely differently, this is
                // the in-world visual cue to match.
                float scale = config.scale() * (item.huge() ? config.hugeScaleMultiplier() : 1f);
                ItemDisplayManager.setScale(viewer, instance.itemEntityId(), scale, scale, scale);
                // Pitch is a fixed tilt; yaw tracks either the owner's live
                // facing or the pet's current attack target (see
                // computeYaws) so pets keep facing whatever they're
                // actually meant to be looking at.
                ItemDisplayManager.setRotation(viewer, instance.itemEntityId(), config.pitchDegrees(), yaw);
                ItemDisplayManager.setInterpolation(viewer, instance.itemEntityId(), 0,
                        config.updateIntervalTicks(), config.updateIntervalTicks());

                if (!labels) {
                    PacketEntityManager.endBundle(viewer);
                    return;
                }
                Location labelPos = pos.clone().add(0, 0.4, 0);
                TextDisplayManager.spawn(viewer, instance.textEntityId(), labelPos);
                TextDisplayManager.setBillboard(viewer, instance.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
                // Fully transparent background, drop shadow instead for legibility.
                TextDisplayManager.setBackgroundColor(viewer, instance.textEntityId(), 0x00000000);
                TextDisplayManager.setStyle(viewer, instance.textEntityId(), true, false, false,
                        TextDisplayManager.Alignment.CENTER);
                TextDisplayManager.setText(viewer, instance.textEntityId(), labelFor(item, instance.level(), instance.shiny()));
                TextDisplayManager.setInterpolation(viewer, instance.textEntityId(), 0,
                        config.updateIntervalTicks(), config.updateIntervalTicks());

                PacketEntityManager.endBundle(viewer);
            });
        }
    }

    private static boolean withinLungeRange(Player viewer, UUID ownerId, Location ownerAt, double range) {
        if (viewer.getUniqueId().equals(ownerId)) {
            return true;
        }
        Location at = viewer.getLocation();
        return at.getWorld() == ownerAt.getWorld() && at.distanceSquared(ownerAt) <= range * range;
    }

    /**
     * @param moved     slots whose spot changed (pet and label go), or null
     * @param turned    slots whose facing changed, or null
     * @param returning slots that lunged since the last cycle (the pet alone goes back), or null
     */
    private void moveFor(Player viewer, List<PetDisplayInstance> instances, List<Location> positions, List<Float> yaws,
                          boolean[] moved, boolean[] turned, Set<Integer> returning, boolean labels) {
        int ticks = config.updateIntervalTicks();
        // Bundled so the client applies the whole squad's changes in one frame.
        PacketEntityManager.beginBundle(viewer);
        for (int i = 0; i < instances.size(); i++) {
            PetDisplayInstance instance = instances.get(i);
            Location pos = positions.get(i);
            boolean move = moved != null && moved[i];
            if (move || (returning != null && returning.contains(i))) {
                PacketEntityManager.teleportEntity(viewer, instance.itemEntityId(), pos);
            }
            if (move && labels) {
                PacketEntityManager.teleportEntity(viewer, instance.textEntityId(), pos.clone().add(0, 0.4, 0));
            }
            if (turned != null && turned[i]) {
                ItemDisplayManager.setRotationInterpolated(viewer, instance.itemEntityId(), config.pitchDegrees(), yaws.get(i), ticks);
            }
        }
        PacketEntityManager.endBundle(viewer);
    }

    private void despawnFor(Player viewer, List<PetDisplayInstance> instances, boolean labels) {
        for (PetDisplayInstance instance : instances) {
            PacketEntityManager.destroyEntity(viewer, instance.itemEntityId());
            if (labels) {
                PacketEntityManager.destroyEntity(viewer, instance.textEntityId());
            }
        }
    }

    /**
     * Whether this viewer sees name labels over this owner's pets. The owner
     * always does; everyone else only if pet-display.yml says so - a busy
     * zone is a hundred squads, and a label over every pet there is both
     * clutter and a second entity per pet to keep moving for every viewer.
     */
    private boolean labelsFor(UUID viewerId, UUID ownerId) {
        return viewerId.equals(ownerId) || config.labelsForOthers();
    }

    /**
     * The pet's own name always shows in its rarity color - only the fusion
     * tier label above it (for a fused pet) carries the tier's gradient
     * (see {@link FusionTier#tag()} - the name itself is never gradiented,
     * {@code ItemDefinition#displayName()} is identical plain text across
     * every tier of a pet). No bold (removed per request). Level shows
     * gray, bracketed, on its own line below the name - the one stat kept
     * on the nametag (per direct request - level is important enough to
     * always be visible at a glance, unlike damage/other stats which stay
     * Bag-only).
     */
    private Component labelFor(ItemDefinition item, int level, boolean shiny) {
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String rarityColor = rarity != null ? rarity.colorHex() : "#FFFFFF";
        String plainName = Formatting.stripLeadingColorCodes(item.displayName());
        Component nameLine = Text.parse("<" + rarityColor + ">" + plainName);
        Component levelLine = Text.parse("&7[Lv. " + level + "]");

        String tag = PetLabels.tagsFor(item, shiny);
        Component result = tag == null ? nameLine : Text.parse(tag).append(Component.newline()).append(nameLine);
        return result.append(Component.newline()).append(levelLine);
    }

    /**
     * Called right after a pet actually levels up (see
     * PetLevelUpEffectListener) - updates the stored level so any FUTURE
     * spawn (a viewer newly in range, etc.) shows it right, and re-renders
     * the nametag for every viewer CURRENTLY seeing this pet immediately,
     * rather than waiting for the owner's next equip/unequip-triggered
     * {@link #refresh}. A no-op if the pet isn't currently equipped/rendered.
     */
    public void refreshLevelLabel(Player owner, UUID petInstanceId, int newLevel) {
        UUID ownerId = owner.getUniqueId();
        List<PetDisplayInstance> instances = ownerInstances.get(ownerId);
        PackPlayerProfile profile = playerStore.getCached(ownerId);
        if (instances == null || profile == null) {
            return;
        }
        int slot = profile.getEquippedPetIds().indexOf(petInstanceId);
        if (slot < 0 || slot >= instances.size()) {
            return;
        }
        PetDisplayInstance old = instances.get(slot);
        instances.set(slot, new PetDisplayInstance(old.itemId(), newLevel, old.shiny(), old.itemEntityId(), old.textEntityId()));

        itemRegistry.get().find(old.itemId()).ifPresent(item -> {
            Component text = labelFor(item, newLevel, old.shiny());
            for (UUID viewerId : viewersByOwner.getOrDefault(ownerId, Set.of())) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null && labelsFor(viewerId, ownerId)) {
                    TextDisplayManager.setText(viewer, old.textEntityId(), text);
                }
            }
        });
    }
}
