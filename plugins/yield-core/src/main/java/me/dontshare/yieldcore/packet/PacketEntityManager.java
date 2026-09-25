package me.dontshare.yieldcore.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side-only ("fake") entity operations via PacketEvents: spawn,
 * move, re-style, and destroy entities that exist purely as packets sent to
 * one viewer, never as real server-side entities Bukkit knows about. This
 * is the place to reach for NPCs, visual effects, custom hitboxes, and
 * similar client-only tricks.
 * <p>
 * Every method targets a single viewer; nothing here is broadcast
 * automatically - call once per player who should see the change. Track
 * the entity ID yourself (from {@link #nextEntityId()}) to move/update/
 * destroy the same fake entity later.
 * <p>
 * PacketEvents itself is owned and initialized by the core plugin (see
 * {@code YieldCore#onLoad}/{@code onEnable}/{@code onDisable}) - other
 * plugins just call {@link PacketEvents#getAPI()} directly, same as this
 * class does, rather than initializing it themselves.
 */
public final class PacketEntityManager {

    private PacketEntityManager() {
    }

    /** Generates a fresh client-side entity ID for use with the other methods here. */
    public static int nextEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    public static void spawnEntity(Player viewer, int entityId, EntityType type, Location location) {
        spawnEntity(viewer, entityId, UUID.randomUUID(), type, location);
    }

    /**
     * Spawns with a caller-chosen entity UUID rather than a random one - required
     * for {@code EntityTypes.PLAYER}, where the client resolves the entity's skin
     * by matching this UUID against a tab-list entry, so it must be the same UUID
     * passed to {@link #addPlayerInfo}. Entity types that don't key off a profile
     * (the common case - use the other {@link #spawnEntity} overload) don't care
     * what the UUID is.
     */
    public static void spawnEntity(Player viewer, int entityId, UUID entityUuid, EntityType type, Location location) {
        Vector3d position = new Vector3d(location.getX(), location.getY(), location.getZ());
        send(viewer, new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(entityUuid),
                type,
                position,
                location.getPitch(),
                location.getYaw(),
                location.getYaw(), // head yaw - face the same direction as the body on spawn
                0,
                Optional.of(new Vector3d(0, 0, 0))
        ));
    }

    public static void destroyEntity(Player viewer, int entityId) {
        send(viewer, new WrapperPlayServerDestroyEntities(entityId));
    }

    /**
     * Marks the start/end of a "bundle" - packets sent between a
     * {@link #beginBundle}/{@link #endBundle} pair are processed by the
     * client together in one frame instead of across however many ticks
     * they happen to arrive over. Wrap a tab-list-entry + spawn pair in one
     * (as FancyNpcs does for its player-type NPCs) so there's no frame where
     * the entity exists client-side without a profile to resolve a skin
     * from yet. Requires 1.19.4+ clients - fine here since the server only
     * targets 26.2.
     */
    public static void beginBundle(Player viewer) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        flushOpenBundle(viewer, false);
        send(viewer, new WrapperPlayServerBundle());
        if (Bukkit.isPrimaryThread()) {
            OPEN_BUNDLES.put(viewer.getUniqueId(), new OpenBundle(viewer));
        }
    }

    public static void endBundle(Player viewer) {
        if (viewer == null) {
            return;
        }
        flushOpenBundle(viewer, true);
        send(viewer, new WrapperPlayServerBundle());
    }

    /**
     * Metadata waiting to go out for each viewer with a bundle open, merged
     * per entity.
     * <p>
     * Everything that spawns a display sets it up one field at a time - text,
     * scale, billboard, background, glow, interpolation - and each call used
     * to be its own metadata packet: a damage number was a dozen packets, a
     * squad of pets a hundred. Inside a bundle the client applies them all in
     * the same frame anyway, so they are held and sent as one packet per
     * entity, just before the next packet that isn't metadata (so nothing is
     * reordered) or the bundle's end. Main thread only; anything else goes
     * straight out.
     */
    private static final Map<UUID, OpenBundle> OPEN_BUNDLES = new HashMap<>();
    private static boolean warnedUnclosed;

    private static final class OpenBundle {
        final Player viewer;
        final Map<Integer, Map<Integer, EntityData<?>>> byEntity = new LinkedHashMap<>();

        OpenBundle(Player viewer) {
            this.viewer = viewer;
        }
    }

    /** Call once from core's onEnable: closes any bundle a caller forgot to, at the end of its tick. */
    public static void install(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (OPEN_BUNDLES.isEmpty()) {
                return;
            }
            if (!warnedUnclosed) {
                warnedUnclosed = true;
                plugin.getLogger().warning("A packet bundle was opened and never closed - closed it at the end of the tick.");
            }
            for (OpenBundle bundle : List.copyOf(OPEN_BUNDLES.values())) {
                endBundle(bundle.viewer);
            }
        }, 1L, 1L);
    }

    /** Sends whatever metadata the viewer's open bundle is holding; {@code close} also forgets the bundle. */
    private static void flushOpenBundle(Player viewer, boolean close) {
        OpenBundle bundle = close ? OPEN_BUNDLES.remove(viewer.getUniqueId()) : OPEN_BUNDLES.get(viewer.getUniqueId());
        if (bundle == null || bundle.byEntity.isEmpty()) {
            return;
        }
        List<Map.Entry<Integer, Map<Integer, EntityData<?>>>> entries = new ArrayList<>(bundle.byEntity.entrySet());
        bundle.byEntity.clear();
        for (Map.Entry<Integer, Map<Integer, EntityData<?>>> entry : entries) {
            deliver(viewer, new WrapperPlayServerEntityMetadata(entry.getKey(), new ArrayList<>(entry.getValue().values())));
        }
    }

    /** Overrides the entity's max health attribute - mainly useful for fake player-type NPCs, which default to 20. */
    public static void setMaxHealth(Player viewer, int entityId, double health) {
        send(viewer, new WrapperPlayServerUpdateAttributes(entityId,
                List.of(new WrapperPlayServerUpdateAttributes.Property(Attributes.MAX_HEALTH, health, List.of()))));
    }

    /**
     * Sets a living entity's {@code scale} attribute - its model AND its
     * collision box grow together, which is what makes a scaled, invisible
     * shulker a solid box of any size (see yield-zones' giant cubes).
     */
    public static void setScale(Player viewer, int entityId, double scale) {
        send(viewer, new WrapperPlayServerUpdateAttributes(entityId,
                List.of(new WrapperPlayServerUpdateAttributes.Property(Attributes.SCALE, scale, List.of()))));
    }

    public static void teleportEntity(Player viewer, int entityId, Location location) {
        send(viewer, new WrapperPlayServerEntityTeleport(
                entityId, SpigotConversionUtil.fromBukkitLocation(location), true));
    }

    public static void playAnimation(Player viewer, int entityId, WrapperPlayServerEntityAnimation.EntityAnimationType type) {
        send(viewer, new WrapperPlayServerEntityAnimation(entityId, type));
    }

    /**
     * Sets the shared entity-flags byte (metadata index 0) in one shot.
     * This byte holds all of these flags together, so each call fully
     * replaces it - pass every flag you care about at once rather than
     * calling this repeatedly for one flag at a time, or you'll silently
     * reset the others to false. {@link #setInvisible}/{@link #setGlowing}
     * are convenience wrappers for the common single-flag case.
     */
    public static void setEntityFlags(Player viewer, int entityId, boolean onFire, boolean sneaking,
                                       boolean sprinting, boolean invisible, boolean glowing, boolean flying) {
        byte flags = 0;
        if (onFire) flags |= 0x01;
        if (sneaking) flags |= 0x02;
        if (sprinting) flags |= 0x08;
        if (invisible) flags |= 0x20;
        if (glowing) flags |= 0x40;
        if (flying) flags |= (byte) 0x80;

        sendMetadata(viewer, entityId, new EntityData<>(0, EntityDataTypes.BYTE, flags));
    }

    public static void setInvisible(Player viewer, int entityId, boolean invisible) {
        setEntityFlags(viewer, entityId, false, false, false, invisible, false, false);
    }

    public static void setGlowing(Player viewer, int entityId, boolean glowing) {
        setEntityFlags(viewer, entityId, false, false, false, false, glowing, false);
    }

    public static void setCustomName(Player viewer, int entityId, Component name, boolean visible) {
        sendMetadata(viewer, entityId,
                new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.ofNullable(name)),
                new EntityData<>(3, EntityDataTypes.BOOLEAN, visible));
    }

    public static void setEquipment(Player viewer, int entityId, EquipmentSlot slot, org.bukkit.inventory.ItemStack item) {
        Equipment equipment = new Equipment(slot, SpigotConversionUtil.fromBukkitItemStack(item));
        send(viewer, new WrapperPlayServerEntityEquipment(entityId, List.of(equipment)));
    }

    /**
     * Adds a tab-list entry for a fake player-type entity - required before
     * {@link #spawnEntity} for {@code EntityTypes.PLAYER}, otherwise the
     * client has no profile to resolve a skin from and renders the default
     * Steve/Alex model. {@code listed} is false so it never actually shows in
     * the player's tab list; the entry only exists for the client to resolve
     * the profile/skin from. Call {@link #removePlayerInfo} shortly after the
     * spawn packet once the skin is cached client-side - the registration
     * would otherwise accumulate on the client for as long as they're
     * connected, one per fake NPC they've ever seen.
     */
    public static void addPlayerInfo(Player viewer, UUID npcId, String name) {
        addPlayerInfo(viewer, npcId, name, null);
    }

    /** Same as {@link #addPlayerInfo(Player, UUID, String)}, with a real skin instead of the default. */
    public static void addPlayerInfo(Player viewer, UUID npcId, String name, List<TextureProperty> skin) {
        UserProfile profile = skin == null ? new UserProfile(npcId, name) : new UserProfile(npcId, name, skin);
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile, false, 0, GameMode.SURVIVAL, null, null);
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER), List.of(info)));
    }

    public static void removePlayerInfo(Player viewer, UUID npcId) {
        send(viewer, new WrapperPlayServerPlayerInfoRemove(npcId));
    }

    private static void sendMetadata(Player viewer, int entityId, EntityData<?>... data) {
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(data)));
    }

    /**
     * Sends one packet to one viewer - or nothing, if they've logged off.
     * Plenty of packet work is scheduled a few ticks out (a damage number
     * despawning, a hatch animation step), and PacketEvents has no User for
     * a disconnected player: without this check, each of those threw from
     * its task the moment someone quit mid-effect.
     */
    public static void send(Player viewer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        if (!OPEN_BUNDLES.isEmpty() && Bukkit.isPrimaryThread()) {
            OpenBundle bundle = OPEN_BUNDLES.get(viewer.getUniqueId());
            if (bundle != null) {
                if (packet instanceof WrapperPlayServerEntityMetadata metadata) {
                    Map<Integer, EntityData<?>> fields = bundle.byEntity.computeIfAbsent(metadata.getEntityId(), id -> new LinkedHashMap<>());
                    for (EntityData<?> data : metadata.getEntityMetadata()) {
                        // A later value for the same field replaces the earlier
                        // one - the client would have ended up on it anyway.
                        fields.remove(data.getIndex());
                        fields.put(data.getIndex(), data);
                    }
                    return;
                }
                flushOpenBundle(viewer, false);
            }
        }
        deliver(viewer, packet);
    }

    private static void deliver(Player viewer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        User user = user(viewer);
        if (user != null) {
            me.dontshare.yieldcore.perf.PerfTracker.countPacket(packet);
            user.sendPacket(packet);
        }
    }

    private static User user(Player viewer) {
        return PacketEvents.getAPI().getPlayerManager().getUser(viewer);
    }
}
