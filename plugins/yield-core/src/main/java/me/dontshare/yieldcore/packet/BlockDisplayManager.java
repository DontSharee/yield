package me.dontshare.yieldcore.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Client-side-only block_display entities via PacketEvents - a floating
 * rendering of a block state, same rules as {@link ItemDisplayManager}/
 * {@link TextDisplayManager}: every method targets a single viewer, nothing
 * is broadcast automatically, and PacketEvents itself is owned by the core
 * plugin (see {@code YieldCore#onLoad}/{@code onEnable}/{@code onDisable}).
 * <p>
 * Field indices come from the vanilla Entity/Display/BlockDisplay metadata
 * layout (Entity 0-7, Display 8-22, BlockDisplay 23 as of 1.20.2+) - same
 * raw-{@link EntityData} approach as the other display managers.
 */
public final class BlockDisplayManager {

    private BlockDisplayManager() {
    }

    /** Overload for callers that don't need this entity's UUID for anything else (e.g. team-based glow color - see {@link #spawn(Player, int, UUID, Location)}). */
    public static void spawn(Player viewer, int entityId, Location location) {
        spawn(viewer, entityId, UUID.randomUUID(), location);
    }

    /**
     * Callers that need this specific entity's UUID afterward - e.g. to add
     * it as a scoreboard {@code Team} entry for a colored glow outline,
     * which vanilla resolves by matching a non-player entity's UUID string
     * against team membership - must supply it themselves rather than
     * letting one get generated internally, so the two stay in sync.
     */
    public static void spawn(Player viewer, int entityId, UUID entityUuid, Location location) {
        user(viewer).sendPacket(new WrapperPlayServerSpawnEntity(
                entityId,
                entityUuid,
                EntityTypes.BLOCK_DISPLAY,
                SpigotConversionUtil.fromBukkitLocation(location),
                location.getYaw(),
                0,
                null
        ));
    }

    public static void setBlockState(Player viewer, int entityId, Material material) {
        WrappedBlockState state = SpigotConversionUtil.fromBukkitBlockData(material.createBlockData());
        sendMetadata(viewer, entityId, new EntityData<>(23, EntityDataTypes.BLOCK_STATE, state.getGlobalId()));
    }

    public static void setScale(Player viewer, int entityId, float x, float y, float z) {
        sendMetadata(viewer, entityId, new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(x, y, z)));
    }

    /**
     * Nudges this display very slightly larger than a real block (translation
     * is the corner offset needed to keep the enlarged box centered - e.g.
     * -0.01 paired with a 1.02 scale) - for an overlay meant to sit exactly
     * on top of a real placed block (a glow-outline carrier, since a real
     * block itself can't glow), this is the standard fix for the two
     * identical, perfectly coplanar surfaces otherwise z-fighting/flickering
     * against each other.
     */
    public static void setTransformation(Player viewer, int entityId, float translate, float scale) {
        setTransformation(viewer, entityId, new Vector3f(translate, translate, translate), new Vector3f(scale, scale, scale));
    }

    /** Non-uniform variant - e.g. a hit-reaction squish (flatten Y, widen X/Z), which needs a different translate/scale per axis to stay centered and floor-anchored rather than a single uniform value. */
    public static void setTransformation(Player viewer, int entityId, Vector3f translate, Vector3f scale) {
        sendMetadata(viewer, entityId,
                new EntityData<>(11, EntityDataTypes.VECTOR3F, translate),
                new EntityData<>(12, EntityDataTypes.VECTOR3F, scale));
    }

    /** Angle in radians, rotating around the Y axis (a "spin in place") - identical field/formula to {@link ItemDisplayManager#setYRotation}, the Display base class shares this metadata index across every display type. */
    public static void setYRotation(Player viewer, int entityId, double radians) {
        float halfAngle = (float) (radians / 2.0);
        Quaternion4f rotation = new Quaternion4f(0f, (float) Math.sin(halfAngle), 0f, (float) Math.cos(halfAngle));
        sendMetadata(viewer, entityId, new EntityData<>(13, EntityDataTypes.QUATERNION, rotation));
    }

    /**
     * Tells the client to smoothly glide/tween toward new transform and
     * position values over the given number of ticks, rather than snapping
     * instantly - set this once right after spawning, before sending any
     * per-tick updates (see {@link ItemDisplayManager#setInterpolation} for
     * the full rationale, identical here).
     */
    public static void setInterpolation(Player viewer, int entityId, int delayTicks, int transformDurationTicks, int positionDurationTicks) {
        sendMetadata(viewer, entityId,
                new EntityData<>(8, EntityDataTypes.INT, delayTicks),
                new EntityData<>(9, EntityDataTypes.INT, transformDurationTicks),
                new EntityData<>(10, EntityDataTypes.INT, positionDurationTicks));
    }

    private static void sendMetadata(Player viewer, int entityId, EntityData<?>... data) {
        user(viewer).sendPacket(new WrapperPlayServerEntityMetadata(entityId, List.of(data)));
    }

    private static User user(Player viewer) {
        return PacketEvents.getAPI().getPlayerManager().getUser(viewer);
    }
}
