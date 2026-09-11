package me.dontshare.yieldpacks.display;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.packet.ItemDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
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

    /** How far (as a fraction of the distance to the target) an attacking pet lunges forward per hit. */
    private static final double LUNGE_REACH = 0.65;
    /** Duration of both the forward lunge and the return spring-back - see {@link #playAttackLunge}. */
    private static final int LUNGE_TICKS = 3;

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final ItemIconFactory iconFactory;
    private volatile PetDisplayConfig config;

    private final Map<UUID, List<PetDisplayInstance>> ownerInstances = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastOwnerLocation = new ConcurrentHashMap<>();
    /** Per-owner, per-equip-slot target overrides - a slot missing from the map stays in formation. Multiple slots may point at different targets at once (see single-send). */
    private final Map<UUID, Map<Integer, Location>> attackOverrides = new ConcurrentHashMap<>();
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
        attackOverrides.put(owner.getUniqueId(), Map.copyOf(slotTargets));
    }

    public void clearAttackTarget(Player owner) {
        attackOverrides.remove(owner.getUniqueId());
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
        Vector towardTarget = target.toVector().subtract(current.toVector()).multiply(LUNGE_REACH);
        Location lunge = current.clone().add(towardTarget);

        PetDisplayInstance instance = instances.get(slot);
        for (UUID viewerId : viewersByOwner.getOrDefault(ownerId, Set.of())) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null) {
                continue;
            }
            ItemDisplayManager.setInterpolation(viewer, instance.itemEntityId(), 0, LUNGE_TICKS, LUNGE_TICKS);
            PacketEntityManager.teleportEntity(viewer, instance.itemEntityId(), lunge);
            TextDisplayManager.setInterpolation(viewer, instance.textEntityId(), 0, LUNGE_TICKS, LUNGE_TICKS);
            PacketEntityManager.teleportEntity(viewer, instance.textEntityId(), lunge.clone().add(0, 0.4, 0));
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
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, config.updateIntervalTicks(), config.updateIntervalTicks());
    }

    private void tick() {
        elapsedTicks += config.updateIntervalTicks();
        for (Player owner : Bukkit.getOnlinePlayers()) {
            updateOwner(owner);
        }
    }

    /** Rebuilds this owner's pet instances from their current equip list. Call after any equip/unequip mutation. */
    public void refresh(Player owner) {
        despawnAll(owner);
        UUID ownerId = owner.getUniqueId();
        PackPlayerProfile profile = playerStore.getOrCreate(ownerId);
        List<UUID> equippedIds = profile.getEquippedPetIds();
        if (equippedIds.isEmpty()) {
            ownerInstances.remove(ownerId);
            return;
        }
        List<PetDisplayInstance> instances = new ArrayList<>(equippedIds.size());
        for (UUID instanceId : equippedIds) {
            profile.findPet(instanceId).ifPresent(pet ->
                    instances.add(new PetDisplayInstance(pet.getItemId(), pet.getLevel(), PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId())));
        }
        ownerInstances.put(ownerId, instances);
        // Deliberately simple: a full despawn+rebuild rather than diffing which
        // pets are unchanged. The next tick() cycle (within update-interval-ticks,
        // a few hundred ms at most) re-spawns for every in-range viewer, so any
        // equip/unequip causes a brief, imperceptible flicker rather than a
        // seamless in-place swap - an acceptable v1 trade-off for far simpler,
        // harder-to-get-wrong bookkeeping.
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
                List<Location> positions = resolvePositions(owner, instances, 0);
                List<Float> yaws = computeYaws(owner, positions);
                spawnFor(viewer, instances, positions, yaws);
                currentlySeeing.add(viewerId);
            } else if (!shouldSeeNow && seeingNow) {
                despawnFor(viewer, instances);
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
                    despawnFor(viewer, instances);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to despawn " + owner.getName() + "'s pet display for viewer "
                            + viewer.getName() + ": " + e.getMessage());
                }
            }
        }
        lastOwnerLocation.remove(ownerId);
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

        double hoverOffset = stationary
                ? config.hoverAmplitude() * Math.sin(2 * Math.PI * elapsedTicks / config.hoverPeriodTicks())
                : 0.0;
        List<Location> positions = resolvePositions(owner, instances, hoverOffset);
        // Tracks the owner's live yaw (not a fixed world-space constant) for
        // formation slots, so pets keep facing back toward the player as
        // they turn; attacking slots instead face whatever they're
        // targeting - see computeYaws.
        List<Float> yaws = computeYaws(owner, positions);

        Set<UUID> shouldSee = computeViewers(owner);
        Set<UUID> currentlySeeing = viewersByOwner.computeIfAbsent(ownerId, id -> ConcurrentHashMap.newKeySet());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            UUID viewerId = viewer.getUniqueId();
            boolean shouldSeeNow = shouldSee.contains(viewerId);
            boolean seeingNow = currentlySeeing.contains(viewerId);
            if (shouldSeeNow && !seeingNow) {
                spawnFor(viewer, instances, positions, yaws);
                currentlySeeing.add(viewerId);
            } else if (!shouldSeeNow && seeingNow) {
                despawnFor(viewer, instances);
                currentlySeeing.remove(viewerId);
            } else if (shouldSeeNow) {
                moveFor(viewer, instances, positions, yaws);
            }
        }
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
        List<Location> positions;
        if (overrides == null || overrides.isEmpty()) {
            positions = positionsFor(owner, count, hoverOffset);
        } else {
            Map<Location, List<Integer>> slotsByTarget = new LinkedHashMap<>();
            for (Map.Entry<Integer, Location> entry : overrides.entrySet()) {
                slotsByTarget.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
            }
            Map<Integer, Location> ringPositionBySlot = new HashMap<>();
            for (Map.Entry<Location, List<Integer>> group : slotsByTarget.entrySet()) {
                List<Integer> slots = group.getValue();
                for (int i = 0; i < slots.size(); i++) {
                    ringPositionBySlot.put(slots.get(i), ringPositionFor(group.getKey(), i, slots.size()));
                }
            }

            Location ownerLocation = owner.getLocation();
            positions = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Location pos = ringPositionBySlot.get(i);
                if (pos == null) {
                    pos = PetFormation.positionFor(ownerLocation, i, config);
                    if (hoverOffset != 0.0) {
                        pos.setY(pos.getY() + hoverOffset);
                    }
                }
                positions.add(pos);
            }
        }
        return applyHugeSeparation(positions, instances);
    }

    /**
     * Pushes every OTHER slot directly away (in the horizontal plane) from
     * any Huge pet's slot it's currently closer than {@code requiredClearance}
     * to - Huge pets render {@link PetDisplayConfig#hugeScaleMultiplier()}
     * times larger (see {@code spawnFor}), so the plain grid spacing (tuned
     * for normal-sized pets standing shoulder to shoulder) isn't enough room
     * and both the model and its floating nametag visibly overlap its
     * neighbors. Normal-to-normal spacing is untouched - this only reacts to
     * Huge slots specifically, not a blanket grid-wide increase.
     */
    private List<Location> applyHugeSeparation(List<Location> positions, List<PetDisplayInstance> instances) {
        List<Integer> hugeSlots = new ArrayList<>();
        for (int i = 0; i < instances.size() && i < positions.size(); i++) {
            if (isHuge(instances.get(i).itemId())) {
                hugeSlots.add(i);
            }
        }
        if (hugeSlots.isEmpty()) {
            return positions;
        }
        double baseSpacing = (config.columnSpacing() + config.rowSpacing()) / 2.0;
        double requiredClearance = baseSpacing * (1.0 + config.hugeScaleMultiplier()) / 2.0;

        List<Location> result = new ArrayList<>(positions);
        for (int hugeIndex : hugeSlots) {
            Location hugeCenter = result.get(hugeIndex);
            for (int i = 0; i < result.size(); i++) {
                if (i == hugeIndex) {
                    continue;
                }
                Location pos = result.get(i);
                double dx = pos.getX() - hugeCenter.getX();
                double dz = pos.getZ() - hugeCenter.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance >= requiredClearance) {
                    continue;
                }
                // Degenerate (two slots landing on the exact same point, e.g.
                // ringed tightly around the same attack target) - push along
                // an arbitrary fixed direction rather than dividing by zero.
                double dirX = distance < 1e-6 ? 1.0 : dx / distance;
                double dirZ = distance < 1e-6 ? 0.0 : dz / distance;
                Location pushed = pos.clone();
                pushed.setX(hugeCenter.getX() + dirX * requiredClearance);
                pushed.setZ(hugeCenter.getZ() + dirZ * requiredClearance);
                result.set(i, pushed);
            }
        }
        return result;
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
    private Location ringPositionFor(Location center, int ringIndex, int ringCount) {
        int ring = ringIndex / PETS_PER_RING;
        int indexInRing = ringIndex % PETS_PER_RING;
        int countInThisRing = Math.min(PETS_PER_RING, ringCount - ring * PETS_PER_RING);
        double radius = BASE_RING_RADIUS + ring * RING_RADIUS_STEP;
        double angle = 2 * Math.PI * indexInRing / Math.max(1, countInThisRing);
        double x = center.getX() + radius * Math.cos(angle);
        double z = center.getZ() + radius * Math.sin(angle);
        return new Location(center.getWorld(), x, center.getY(), z);
    }

    private List<Location> positionsFor(Player owner, int count, double hoverOffset) {
        List<Location> positions = PetFormation.positionsFor(owner.getLocation(), count, config);
        if (hoverOffset != 0.0) {
            for (Location pos : positions) {
                pos.setY(pos.getY() + hoverOffset);
            }
        }
        return positions;
    }

    private Set<UUID> computeViewers(Player owner) {
        Set<UUID> result = new HashSet<>();
        double viewDistanceSquared = config.viewDistance() * config.viewDistance();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getWorld().equals(owner.getWorld())) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(owner.getLocation()) > viewDistanceSquared) {
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

    private void spawnFor(Player viewer, List<PetDisplayInstance> instances, List<Location> positions, List<Float> yaws) {
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

                Location labelPos = pos.clone().add(0, 0.4, 0);
                TextDisplayManager.spawn(viewer, instance.textEntityId(), labelPos);
                TextDisplayManager.setBillboard(viewer, instance.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
                // Fully transparent background, drop shadow instead for legibility.
                TextDisplayManager.setBackgroundColor(viewer, instance.textEntityId(), 0x00000000);
                TextDisplayManager.setStyle(viewer, instance.textEntityId(), true, false, false,
                        TextDisplayManager.Alignment.CENTER);
                TextDisplayManager.setText(viewer, instance.textEntityId(), labelFor(item, instance.level()));
                TextDisplayManager.setInterpolation(viewer, instance.textEntityId(), 0,
                        config.updateIntervalTicks(), config.updateIntervalTicks());

                PacketEntityManager.endBundle(viewer);
            });
        }
    }

    private void moveFor(Player viewer, List<PetDisplayInstance> instances, List<Location> positions, List<Float> yaws) {
        int ticks = config.updateIntervalTicks();
        // Bundled the same way spawnFor already is - this runs for every
        // pet, every viewer, every update-interval-ticks, so with a large
        // equip cap it's the single biggest source of outgoing packets this
        // service produces; batching them into one bundle per viewer instead
        // of leaving each of the 5 packets below to flush individually cuts
        // that overhead directly.
        PacketEntityManager.beginBundle(viewer);
        for (int i = 0; i < instances.size(); i++) {
            PetDisplayInstance instance = instances.get(i);
            Location pos = positions.get(i);
            // Explicitly reset every cycle, not just at spawn - an attack
            // lunge (see playAttackLunge) temporarily shortens this same
            // entity's interpolation window, and the client keeps using
            // whatever duration it was LAST told until something resets it;
            // without this, every regular move after a pet's first attack
            // would keep interpolating over the lunge's short window instead
            // of this loop's own cadence, arriving early and visibly
            // "pausing" before the next update - the main cause of pet
            // movement looking less smooth than it should.
            ItemDisplayManager.setInterpolation(viewer, instance.itemEntityId(), 0, ticks, ticks);
            TextDisplayManager.setInterpolation(viewer, instance.textEntityId(), 0, ticks, ticks);
            PacketEntityManager.teleportEntity(viewer, instance.itemEntityId(), pos);
            PacketEntityManager.teleportEntity(viewer, instance.textEntityId(), pos.clone().add(0, 0.4, 0));
            // Re-sent every cycle since this must track a live-updating yaw,
            // not a one-time constant.
            ItemDisplayManager.setRotation(viewer, instance.itemEntityId(), config.pitchDegrees(), yaws.get(i));
        }
        PacketEntityManager.endBundle(viewer);
    }

    private void despawnFor(Player viewer, List<PetDisplayInstance> instances) {
        for (PetDisplayInstance instance : instances) {
            PacketEntityManager.destroyEntity(viewer, instance.itemEntityId());
            PacketEntityManager.destroyEntity(viewer, instance.textEntityId());
        }
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
    private Component labelFor(ItemDefinition item, int level) {
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String rarityColor = rarity != null ? rarity.colorHex() : "#FFFFFF";
        String plainName = Formatting.stripLeadingColorCodes(item.displayName());
        Component nameLine = Text.parse("<" + rarityColor + ">" + plainName);
        Component levelLine = Text.parse("&7[Lv. " + level + "]");

        String tag = item.fusionTier().tag();
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
        instances.set(slot, new PetDisplayInstance(old.itemId(), newLevel, old.itemEntityId(), old.textEntityId()));

        itemRegistry.get().find(old.itemId()).ifPresent(item -> {
            Component text = labelFor(item, newLevel);
            for (UUID viewerId : viewersByOwner.getOrDefault(ownerId, Set.of())) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    TextDisplayManager.setText(viewer, old.textEntityId(), text);
                }
            }
        });
    }
}
