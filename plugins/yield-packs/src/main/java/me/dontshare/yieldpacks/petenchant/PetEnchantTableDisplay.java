package me.dontshare.yieldpacks.petenchant;

import com.github.retrooper.packetevents.util.Vector3f;
import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.EntityClickRegistry;
import me.dontshare.yieldcore.packet.InteractionEntityManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The packet-visual layer for the Enchanting Table's clickable button -
 * near-identical to yield-zonemachines' own {@code ZoneMachineDisplay}
 * (same invisible hitbox + visible button + floating text readout,
 * spawned per-viewer purely by distance), trimmed down: only one thing to
 * click here (no per-type switch), and global rather than zone-gated - no
 * unlock check, unlike a real zone machine.
 */
public final class PetEnchantTableDisplay implements org.bukkit.event.Listener {

    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;
    private static final long TICK_INTERVAL = 20L; // 1 second
    private static final float BUTTON_SCALE = 0.35f;
    private static final float BUTTON_TRANSLATE = (1f - BUTTON_SCALE) / 2f;
    private static final float HITBOX_SIZE = 0.8f;
    private static final float HITBOX_INSET_X = 0.0f;
    private static final float HITBOX_INSET_Y = 0.1f;
    private static final float HITBOX_INSET_Z = 0.4f;
    private static final float PUSH_SCALE = BUTTON_SCALE * 0.6f;
    private static final float PUSH_TRANSLATE = (1f - PUSH_SCALE) / 2f;
    private static final int PUSH_TICKS = 3;
    private static final float WALL_FACE_SCALE = 0.9f;
    private static final float WALL_DEPTH_SCALE = 0.15f;
    private static final float WALL_FACE_TRANSLATE = (1f - WALL_FACE_SCALE) / 2f;
    private static final float WALL_DEPTH_TRANSLATE = (1f - WALL_DEPTH_SCALE) / 2f;

    private final JavaPlugin plugin;
    private final PetEnchantTableGui tableGui;

    private volatile List<PetEnchantTable> tables = List.of();
    private final Map<PetEnchantTable, Set<UUID>> viewersByTable = new ConcurrentHashMap<>();

    public PetEnchantTableDisplay(JavaPlugin plugin, PetEnchantTableGui tableGui) {
        this.plugin = plugin;
        this.tableGui = tableGui;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /**
     * Forgets a player who logged off: their client dropped every packet
     * entity, so on rejoin each table must count them as a new viewer and
     * spawn again - left in, it never reappeared until they walked away
     * and back.
     */
    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        for (java.util.Set<java.util.UUID> viewers : viewersByTable.values()) {
            viewers.remove(id);
        }
    }

    /** Same teardown-then-rebuild reasoning as {@code ZoneMachineDisplay#reload} - a content reload replaces every table (and its entity ids) wholesale. */
    public void reload(List<PetEnchantTable> newTables) {
        for (PetEnchantTable table : tables) {
            EntityClickRegistry.unregister(table.hitboxEntityId());
            Set<UUID> viewers = viewersByTable.remove(table);
            if (viewers == null) {
                continue;
            }
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    despawnFor(viewer, table);
                }
            }
        }
        tables = newTables;
        for (PetEnchantTable table : newTables) {
            viewersByTable.put(table, ConcurrentHashMap.newKeySet());
            EntityClickRegistry.register(table.hitboxEntityId(),
                    EntityClickRegistry.inReach(table.location(), player -> handleClick(player, table)));
        }
    }

    private void tick() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            org.bukkit.Location viewerAt = viewer.getLocation();
            for (PetEnchantTable table : tables) {
                Set<UUID> viewers = viewersByTable.get(table);
                if (viewers == null) {
                    continue;
                }
                boolean inRange = viewer.getWorld().equals(table.location().getWorld())
                        && table.location().distanceSquared(viewerAt) <= VIEW_DISTANCE_SQUARED;
                boolean seeing = viewers.contains(viewer.getUniqueId());
                if (inRange && !seeing) {
                    spawnFor(viewer, table);
                    viewers.add(viewer.getUniqueId());
                } else if (!inRange && seeing) {
                    despawnFor(viewer, table);
                    viewers.remove(viewer.getUniqueId());
                }
            }
        }
    }

    private void handleClick(Player player, PetEnchantTable table) {
        playPushAnimation(player, table);
        tableGui.open(player);
    }

    private void spawnFor(Player viewer, PetEnchantTable table) {
        Location loc = table.location();

        PacketEntityManager.beginBundle(viewer);
        InteractionEntityManager.spawn(viewer, table.hitboxEntityId(), hitboxLocation(loc));
        InteractionEntityManager.setSize(viewer, table.hitboxEntityId(), HITBOX_SIZE, HITBOX_SIZE);

        BlockDisplayManager.spawn(viewer, table.wallEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, table.wallEntityId(), Material.PURPLE_CONCRETE);
        BlockDisplayManager.setTransformation(viewer, table.wallEntityId(),
                new Vector3f(WALL_FACE_TRANSLATE, WALL_FACE_TRANSLATE, WALL_DEPTH_TRANSLATE),
                new Vector3f(WALL_FACE_SCALE, WALL_FACE_SCALE, WALL_DEPTH_SCALE));

        BlockDisplayManager.spawn(viewer, table.buttonEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, table.buttonEntityId(), Material.MAGENTA_CONCRETE);
        BlockDisplayManager.setTransformation(viewer, table.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);

        TextDisplayManager.spawn(viewer, table.textEntityId(), textLocation(loc));
        TextDisplayManager.setBillboard(viewer, table.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, table.textEntityId(), 0x00000000);
        TextDisplayManager.setStyle(viewer, table.textEntityId(), true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setText(viewer, table.textEntityId(), buildText());
        PacketEntityManager.endBundle(viewer);
    }

    private Component buildText() {
        return Text.parse("<#B15CFF><bold>Enchanting Table</bold></#B15CFF>\n&7Smack to enchant a pet!");
    }

    private void playPushAnimation(Player viewer, PetEnchantTable table) {
        BlockDisplayManager.setInterpolation(viewer, table.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
        BlockDisplayManager.setTransformation(viewer, table.buttonEntityId(), PUSH_TRANSLATE, PUSH_SCALE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            BlockDisplayManager.setInterpolation(viewer, table.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
            BlockDisplayManager.setTransformation(viewer, table.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);
        }, PUSH_TICKS);
    }

    private Location hitboxLocation(Location tableCorner) {
        return tableCorner.clone().add(HITBOX_INSET_X, HITBOX_INSET_Y, HITBOX_INSET_Z);
    }

    private Location textLocation(Location tableCorner) {
        return tableCorner.clone().add(0.2, 1.1, 0.5);
    }

    private void despawnFor(Player viewer, PetEnchantTable table) {
        PacketEntityManager.destroyEntity(viewer, table.hitboxEntityId());
        PacketEntityManager.destroyEntity(viewer, table.buttonEntityId());
        PacketEntityManager.destroyEntity(viewer, table.wallEntityId());
        PacketEntityManager.destroyEntity(viewer, table.textEntityId());
    }
}
