package me.dontshare.yieldcore.packet;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Client-side-only item_display entities via PacketEvents - a floating,
 * freely-rotatable rendering of an {@link ItemStack} rather than the plain
 * entity/name-tag stuff {@link PacketEntityManager} covers or the text
 * panels {@link TextDisplayManager} covers. Same rules apply: every method
 * targets a single viewer, nothing is broadcast automatically, and
 * PacketEvents itself is owned by the core plugin (see
 * {@code YieldCore#onLoad}/{@code onEnable}/{@code onDisable}).
 * <p>
 * Field indices come from the vanilla Entity/Display/ItemDisplay metadata
 * layout (Entity 0-7, Display 8-22, ItemDisplay 23-24 as of 1.20.2+) -
 * PacketEvents doesn't expose typed wrappers for these, so they're sent as
 * raw {@link EntityData}, same as {@link TextDisplayManager} does for its
 * own fields.
 */
public final class ItemDisplayManager {

    public enum DisplayContext {
        NONE((byte) 0), GROUND((byte) 7), FIXED((byte) 8);

        private final byte value;

        DisplayContext(byte value) {
            this.value = value;
        }
    }

    private ItemDisplayManager() {
    }

    public static void spawn(Player viewer, int entityId, Location location) {
        PacketEntityManager.send(viewer, new WrapperPlayServerSpawnEntity(
                entityId,
                UUID.randomUUID(),
                EntityTypes.ITEM_DISPLAY,
                SpigotConversionUtil.fromBukkitLocation(location),
                location.getYaw(),
                0,
                null
        ));
    }

    public static void setItem(Player viewer, int entityId, ItemStack item) {
        sendMetadata(viewer, entityId, new EntityData<>(23, EntityDataTypes.ITEMSTACK, SpigotConversionUtil.fromBukkitItemStack(item)));
    }

    public static void setDisplayContext(Player viewer, int entityId, DisplayContext context) {
        sendMetadata(viewer, entityId, new EntityData<>(24, EntityDataTypes.BYTE, context.value));
    }

    /**
     * The vanilla glowing outline (the Spectral Arrow effect) - entity flag
     * bit 0x40 on the shared flags byte at metadata index 0.
     * <p>
     * Sent on its own rather than folded into the spawn bundle because it
     * is a state a caller turns on and off mid-life: the hatch reveal lights
     * up only the pets worth lighting up, once they have actually hatched.
     */
    public static void setGlowing(Player viewer, int entityId, boolean glowing) {
        sendMetadata(viewer, entityId, new EntityData<>(0, EntityDataTypes.BYTE, (byte) (glowing ? 0x40 : 0x00)));
    }

    /**
     * What color that outline is, as packed RGB - Display entities carry
     * their own {@code glow_color_override} at metadata index 22, which
     * overrides the team-color the outline would otherwise take. Set it
     * alongside {@link #setGlowing}; on its own it does nothing.
     */
    public static void setGlowColor(Player viewer, int entityId, int rgb) {
        sendMetadata(viewer, entityId, new EntityData<>(22, EntityDataTypes.INT, rgb));
    }

    /**
     * Always turns the item to face the viewer - {@code CENTER} for a flat
     * sprite like a coin, so it reads as a coin from every side rather than
     * a sliver edge-on. Display billboard is metadata index 15.
     */
    public static void setBillboardCenter(Player viewer, int entityId) {
        sendMetadata(viewer, entityId, new EntityData<>(15, EntityDataTypes.BYTE, (byte) 3));
    }

    public static void setScale(Player viewer, int entityId, float x, float y, float z) {
        sendMetadata(viewer, entityId, new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(x, y, z)));
    }

    /**
     * Tells the client to smoothly glide/tween toward new transform
     * (translation/scale/rotation, set via metadata - see {@link #setYRotation})
     * and position/rotation (set via a real teleport packet) values over the
     * given number of ticks, rather than snapping instantly - set this once
     * right after spawning, before sending any per-tick updates. Without it,
     * per-tick teleports/rotation changes render as a visible stepped
     * "snap" once per update rather than a continuous glide.
     */
    public static void setInterpolation(Player viewer, int entityId, int delayTicks, int transformDurationTicks, int positionDurationTicks) {
        sendMetadata(viewer, entityId,
                new EntityData<>(8, EntityDataTypes.INT, delayTicks),
                new EntityData<>(9, EntityDataTypes.INT, transformDurationTicks),
                new EntityData<>(10, EntityDataTypes.INT, positionDurationTicks));
    }

    /**
     * Only how long a position change takes to glide - leaves the
     * transformation interpolation alone. Setting the delay field (index 8)
     * restarts the transformation interpolation on the client, and a
     * restart with nothing new to interpolate toward replays the rotation
     * from wherever the client last started one - a pet visibly spinning
     * round on every attack lunge.
     */
    public static void setPositionInterpolation(Player viewer, int entityId, int ticks) {
        sendMetadata(viewer, entityId, new EntityData<>(10, EntityDataTypes.INT, ticks));
    }

    /**
     * {@link #setRotation} as a smooth turn: the new rotation and a fresh
     * interpolation start go out in ONE packet, so the client turns from
     * what it is showing right now to the new facing over {@code ticks}.
     */
    public static void setRotationInterpolated(Player viewer, int entityId, float pitchDegrees, float yawDegrees, int ticks) {
        sendMetadata(viewer, entityId,
                new EntityData<>(8, EntityDataTypes.INT, 0),
                new EntityData<>(9, EntityDataTypes.INT, ticks),
                new EntityData<>(13, EntityDataTypes.QUATERNION, rotationOf(pitchDegrees, yawDegrees)));
    }

    /**
     * Glides the display's transformation translation to {@code (x, y, z)}
     * over {@code ticks} on the client - movement that costs one packet per
     * keyframe instead of a teleport every few ticks. Metadata indices are
     * shared by every Display type, so this works on text displays too.
     */
    public static void setTranslationInterpolated(Player viewer, int entityId, float x, float y, float z, int ticks) {
        sendMetadata(viewer, entityId,
                new EntityData<>(8, EntityDataTypes.INT, 0),
                new EntityData<>(9, EntityDataTypes.INT, ticks),
                new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(x, y, z)));
    }

    /** Angle in radians, rotating around the Y axis (a "spin in place"). */
    public static void setYRotation(Player viewer, int entityId, double radians) {
        float halfAngle = (float) (radians / 2.0);
        Quaternion4f rotation = new Quaternion4f(0f, (float) Math.sin(halfAngle), 0f, (float) Math.cos(halfAngle));
        sendMetadata(viewer, entityId, new EntityData<>(13, EntityDataTypes.QUATERNION, rotation));
    }

    /**
     * A pitch (X-axis tilt) + yaw orientation, combined into one quaternion.
     * {@code yawDegrees} follows the same convention as a real entity's
     * yaw/{@link Location#getYaw()} - 0 faces south, increasing clockwise
     * when viewed from above - so a caller can feed this a player's own
     * yaw (or an angle computed the same way, e.g. via
     * {@code Math.toDegrees(Math.atan2(-dx, dz))}) and get a matching
     * facing. Unlike {@link #setYRotation}, callers own tracking whatever
     * the display should currently face; call this again whenever that
     * changes rather than once at spawn.
     */
    public static void setRotation(Player viewer, int entityId, float pitchDegrees, float yawDegrees) {
        sendMetadata(viewer, entityId, new EntityData<>(13, EntityDataTypes.QUATERNION, rotationOf(pitchDegrees, yawDegrees)));
    }

    private static Quaternion4f rotationOf(float pitchDegrees, float yawDegrees) {
        double halfPitch = Math.toRadians(pitchDegrees) / 2.0;
        // Negated: a quaternion's positive rotation around +Y is
        // counterclockwise viewed from above, but Minecraft yaw increases
        // clockwise - without this, increasing yawDegrees spins the model
        // the opposite way from how a player turning right increases their
        // own yaw (this is what made pets appear to mirror the player).
        double halfYaw = -Math.toRadians(yawDegrees) / 2.0;
        float sp = (float) Math.sin(halfPitch);
        float cp = (float) Math.cos(halfPitch);
        float sy = (float) Math.sin(halfYaw);
        float cy = (float) Math.cos(halfYaw);
        // Quaternion product yaw(Y) * pitch(X): yaw applied after pitch.
        return new Quaternion4f(cy * sp, sy * cp, -sy * sp, cy * cp);
    }

    private static void sendMetadata(Player viewer, int entityId, EntityData<?>... data) {
        PacketEntityManager.send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(data)));
    }

}
