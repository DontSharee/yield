package me.dontshare.yieldcore.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Client-side-only text_display entities via PacketEvents - floating text
 * panels (billboard, background, alignment, etc.) rather than the plain
 * entity/name-tag stuff {@link PacketEntityManager} covers. Same rules
 * apply: every method targets a single viewer, nothing is broadcast
 * automatically, and PacketEvents itself is owned by the core plugin (see
 * {@code YieldCore#onLoad}/{@code onEnable}/{@code onDisable}).
 * <p>
 * Field indices below come from the vanilla Entity/Display/TextDisplay
 * metadata layout (Entity 0-7, Display 8-22, TextDisplay 23-27 as of
 * 1.20.2+) - PacketEvents doesn't expose typed wrappers for these, so
 * they're sent as raw {@link EntityData} the same way
 * {@link PacketEntityManager#setEntityFlags} does for entity flags.
 */
public final class TextDisplayManager {

    private TextDisplayManager() {
    }

    public enum Billboard {
        FIXED((byte) 0), VERTICAL((byte) 1), HORIZONTAL((byte) 2), CENTER((byte) 3);

        private final byte value;

        Billboard(byte value) {
            this.value = value;
        }
    }

    public enum Alignment {
        CENTER((byte) 0x00), LEFT((byte) 0x08), RIGHT((byte) 0x10);

        private final byte value;

        Alignment(byte value) {
            this.value = value;
        }
    }

    public static void spawn(Player viewer, int entityId, Location location) {
        user(viewer).sendPacket(new WrapperPlayServerSpawnEntity(
                entityId,
                UUID.randomUUID(),
                EntityTypes.TEXT_DISPLAY,
                SpigotConversionUtil.fromBukkitLocation(location),
                location.getYaw(),
                0,
                null
        ));
    }

    public static void setText(Player viewer, int entityId, Component text) {
        sendMetadata(viewer, entityId, new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, text));
    }

    public static void setLineWidth(Player viewer, int entityId, int pixels) {
        sendMetadata(viewer, entityId, new EntityData<>(24, EntityDataTypes.INT, pixels));
    }

    /** ARGB, e.g. {@code 0xCC102030} for a translucent dark navy panel. */
    public static void setBackgroundColor(Player viewer, int entityId, int argb) {
        sendMetadata(viewer, entityId, new EntityData<>(25, EntityDataTypes.INT, argb));
    }

    /** 0-255; use {@code (byte) -1} for the default/full-opacity sentinel. */
    public static void setTextOpacity(Player viewer, int entityId, byte opacity) {
        sendMetadata(viewer, entityId, new EntityData<>(26, EntityDataTypes.BYTE, opacity));
    }

    public static void setStyle(Player viewer, int entityId, boolean shadow, boolean seeThrough,
                                 boolean useDefaultBackground, Alignment alignment) {
        sendMetadata(viewer, entityId,
                new EntityData<>(27, EntityDataTypes.BYTE, styleFlags(shadow, seeThrough, useDefaultBackground, alignment)));
    }

    public static void setBillboard(Player viewer, int entityId, Billboard billboard) {
        sendMetadata(viewer, entityId, new EntityData<>(15, EntityDataTypes.BYTE, billboard.value));
    }

    public static void setViewRange(Player viewer, int entityId, float range) {
        sendMetadata(viewer, entityId, new EntityData<>(17, EntityDataTypes.FLOAT, range));
    }

    public static void setScale(Player viewer, int entityId, float x, float y, float z) {
        sendMetadata(viewer, entityId, new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(x, y, z)));
    }

    public static void setTranslation(Player viewer, int entityId, float x, float y, float z) {
        sendMetadata(viewer, entityId, new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(x, y, z)));
    }

    /**
     * Tells the client to smoothly glide/tween toward new transform
     * (translation/scale/rotation) and position/rotation (set via a real
     * teleport packet) values over the given number of ticks, rather than
     * snapping instantly - set this once right after spawning, before
     * sending any per-tick updates. Without it, per-tick teleports render
     * as a visible stepped "snap" once per update rather than a continuous
     * glide.
     */
    /** Only the position glide time - see ItemDisplayManager#setPositionInterpolation for why the delay field is left alone. */
    public static void setPositionInterpolation(Player viewer, int entityId, int ticks) {
        sendMetadata(viewer, entityId, new EntityData<>(10, EntityDataTypes.INT, ticks));
    }

    public static void setInterpolation(Player viewer, int entityId, int delayTicks, int transformDurationTicks, int positionDurationTicks) {
        sendMetadata(viewer, entityId,
                new EntityData<>(8, EntityDataTypes.INT, delayTicks),
                new EntityData<>(9, EntityDataTypes.INT, transformDurationTicks),
                new EntityData<>(10, EntityDataTypes.INT, positionDurationTicks));
    }

    /**
     * Collects several display fields so they go out as one metadata packet
     * rather than one per field.
     * <p>
     * Each setter above sends immediately, which is right for changing a
     * single field on a live entity but wasteful when configuring a freshly
     * spawned one - a floating damage number sets six of them at once, and
     * those are spawned per cube per hit. The protocol carries any number of
     * fields in a single packet, so setting them up individually was paying
     * five extra packets per entity for nothing.
     */
    public static final class Metadata {

        private final List<EntityData<?>> fields = new java.util.ArrayList<>();

        private Metadata() {
        }

        public Metadata text(Component text) {
            fields.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, text));
            return this;
        }

        public Metadata backgroundColor(int argb) {
            fields.add(new EntityData<>(25, EntityDataTypes.INT, argb));
            return this;
        }

        public Metadata style(boolean shadow, boolean seeThrough, boolean useDefaultBackground, Alignment alignment) {
            fields.add(new EntityData<>(27, EntityDataTypes.BYTE, styleFlags(shadow, seeThrough, useDefaultBackground, alignment)));
            return this;
        }

        public Metadata billboard(Billboard billboard) {
            fields.add(new EntityData<>(15, EntityDataTypes.BYTE, billboard.value));
            return this;
        }

        public Metadata scale(float x, float y, float z) {
            fields.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(x, y, z)));
            return this;
        }

        public Metadata interpolation(int delayTicks, int transformDurationTicks, int positionDurationTicks) {
            fields.add(new EntityData<>(8, EntityDataTypes.INT, delayTicks));
            fields.add(new EntityData<>(9, EntityDataTypes.INT, transformDurationTicks));
            fields.add(new EntityData<>(10, EntityDataTypes.INT, positionDurationTicks));
            return this;
        }

        /** Sends everything collected so far to this one viewer. */
        public void send(Player viewer, int entityId) {
            user(viewer).sendPacket(new WrapperPlayServerEntityMetadata(entityId, List.copyOf(fields)));
        }
    }

    public static Metadata metadata() {
        return new Metadata();
    }

    private static byte styleFlags(boolean shadow, boolean seeThrough, boolean useDefaultBackground, Alignment alignment) {
        byte flags = 0;
        if (shadow) flags |= 0x01;
        if (seeThrough) flags |= 0x02;
        if (useDefaultBackground) flags |= 0x04;
        flags |= alignment.value;
        return flags;
    }

    private static void sendMetadata(Player viewer, int entityId, EntityData<?>... data) {
        user(viewer).sendPacket(new WrapperPlayServerEntityMetadata(entityId, List.of(data)));
    }

    private static User user(Player viewer) {
        return PacketEvents.getAPI().getPlayerManager().getUser(viewer);
    }
}
