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
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final ItemIconFactory iconFactory;
    private volatile PetDisplayConfig config;

    private final Map<UUID, List<PetDisplayInstance>> ownerInstances = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> viewersByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastOwnerLocation = new ConcurrentHashMap<>();
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
        List<String> equipped = playerStore.getOrCreate(ownerId).getEquippedItemIds();
        if (equipped.isEmpty()) {
            ownerInstances.remove(ownerId);
            return;
        }
        List<PetDisplayInstance> instances = new ArrayList<>(equipped.size());
        for (String itemId : equipped) {
            instances.add(new PetDisplayInstance(itemId, PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId()));
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
                spawnFor(viewer, instances, positionsFor(owner, instances.size(), 0));
                currentlySeeing.add(viewerId);
            } else if (!shouldSeeNow && seeingNow) {
                despawnFor(viewer, instances);
                currentlySeeing.remove(viewerId);
            }
        }
    }

    /** Destroys this owner's pets for every viewer currently seeing them and forgets their tracked state. Call on quit. */
    public void despawnAll(Player owner) {
        UUID ownerId = owner.getUniqueId();
        List<PetDisplayInstance> instances = ownerInstances.get(ownerId);
        Set<UUID> viewers = viewersByOwner.remove(ownerId);
        if (instances != null && viewers != null) {
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    despawnFor(viewer, instances);
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
        List<Location> positions = positionsFor(owner, instances.size(), hoverOffset);

        Set<UUID> shouldSee = computeViewers(owner);
        Set<UUID> currentlySeeing = viewersByOwner.computeIfAbsent(ownerId, id -> ConcurrentHashMap.newKeySet());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            UUID viewerId = viewer.getUniqueId();
            boolean shouldSeeNow = shouldSee.contains(viewerId);
            boolean seeingNow = currentlySeeing.contains(viewerId);
            if (shouldSeeNow && !seeingNow) {
                spawnFor(viewer, instances, positions);
                currentlySeeing.add(viewerId);
            } else if (!shouldSeeNow && seeingNow) {
                despawnFor(viewer, instances);
                currentlySeeing.remove(viewerId);
            } else if (shouldSeeNow) {
                moveFor(viewer, instances, positions);
            }
        }
    }

    private List<Location> positionsFor(Player owner, int count, double hoverOffset) {
        Location ownerLocation = owner.getLocation();
        List<Location> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Location pos = PetFormation.positionFor(ownerLocation, i, config);
            if (hoverOffset != 0.0) {
                pos.setY(pos.getY() + hoverOffset);
            }
            positions.add(pos);
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

    private void spawnFor(Player viewer, List<PetDisplayInstance> instances, List<Location> positions) {
        for (int i = 0; i < instances.size(); i++) {
            PetDisplayInstance instance = instances.get(i);
            Location pos = positions.get(i);
            itemRegistry.get().find(instance.itemId()).ifPresent(item -> {
                PacketEntityManager.beginBundle(viewer);

                ItemDisplayManager.spawn(viewer, instance.itemEntityId(), pos);
                ItemDisplayManager.setItem(viewer, instance.itemEntityId(), iconFactory.baseIcon(item).build());
                ItemDisplayManager.setScale(viewer, instance.itemEntityId(), config.scale(), config.scale(), config.scale());
                // Fixed tilt/facing, set once - never re-sent per tick, since it
                // never changes and must never track the owner's live look direction.
                ItemDisplayManager.setRotation(viewer, instance.itemEntityId(), config.pitchDegrees(), config.yawDegrees());
                ItemDisplayManager.setInterpolation(viewer, instance.itemEntityId(), 0,
                        config.updateIntervalTicks(), config.updateIntervalTicks());

                Location labelPos = pos.clone().add(0, 0.4, 0);
                TextDisplayManager.spawn(viewer, instance.textEntityId(), labelPos);
                TextDisplayManager.setBillboard(viewer, instance.textEntityId(), TextDisplayManager.Billboard.CENTER);
                // Fully transparent background, drop shadow instead for legibility.
                TextDisplayManager.setBackgroundColor(viewer, instance.textEntityId(), 0x00000000);
                TextDisplayManager.setStyle(viewer, instance.textEntityId(), true, false, false,
                        TextDisplayManager.Alignment.CENTER);
                TextDisplayManager.setText(viewer, instance.textEntityId(), labelFor(item));
                TextDisplayManager.setInterpolation(viewer, instance.textEntityId(), 0,
                        config.updateIntervalTicks(), config.updateIntervalTicks());

                PacketEntityManager.endBundle(viewer);
            });
        }
    }

    private void moveFor(Player viewer, List<PetDisplayInstance> instances, List<Location> positions) {
        for (int i = 0; i < instances.size(); i++) {
            PetDisplayInstance instance = instances.get(i);
            Location pos = positions.get(i);
            PacketEntityManager.teleportEntity(viewer, instance.itemEntityId(), pos);
            PacketEntityManager.teleportEntity(viewer, instance.textEntityId(), pos.clone().add(0, 0.4, 0));
        }
    }

    private void despawnFor(Player viewer, List<PetDisplayInstance> instances) {
        for (PetDisplayInstance instance : instances) {
            PacketEntityManager.destroyEntity(viewer, instance.itemEntityId());
            PacketEntityManager.destroyEntity(viewer, instance.textEntityId());
        }
    }

    private Component labelFor(ItemDefinition item) {
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String rarityColor = rarity != null ? rarity.colorHex() : "#FFFFFF";
        Component nameLine = Text.parse("<" + rarityColor + "><bold>" + item.displayName() + "</bold>");
        Component valueLine = Text.parse("<#55FF7F>$" + Formatting.format(item.valuePerSecond()) + "<#7F7F7F>/sec");
        return nameLine.append(Component.newline()).append(valueLine);
    }
}
