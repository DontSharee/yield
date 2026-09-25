package me.dontshare.yieldcore.packet;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Client-side-only interaction entities via PacketEvents - an invisible,
 * zero-render hitbox with an adjustable width/height, purely for detecting a
 * right/left-click at a specific fake location (see {@link EntityClickRegistry}
 * for turning that click into a callback, exactly how yield-tutorial's NPC
 * click already works). This entity renders nothing itself - pair it with a
 * real visual (e.g. a {@link BlockDisplayManager} entity) at the same spot.
 * <p>
 * Field indices come from the vanilla Entity/Interaction metadata layout
 * (Entity 0-7, Interaction 8-9 as of 1.20.2+) - same raw-{@link EntityData}
 * approach as the other display managers.
 */
public final class InteractionEntityManager {

    private InteractionEntityManager() {
    }

    public static void spawn(Player viewer, int entityId, Location location) {
        PacketEntityManager.send(viewer, new WrapperPlayServerSpawnEntity(
                entityId,
                UUID.randomUUID(),
                EntityTypes.INTERACTION,
                SpigotConversionUtil.fromBukkitLocation(location),
                location.getYaw(),
                0,
                null
        ));
    }

    /** The clickable hitbox's footprint - vanilla's own default is a 1x1 column, override to match whatever visual sits at the same spot. */
    public static void setSize(Player viewer, int entityId, float width, float height) {
        sendMetadata(viewer, entityId,
                new EntityData<>(8, EntityDataTypes.FLOAT, width),
                new EntityData<>(9, EntityDataTypes.FLOAT, height));
    }

    private static void sendMetadata(Player viewer, int entityId, EntityData<?>... data) {
        PacketEntityManager.send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(data)));
    }

}
