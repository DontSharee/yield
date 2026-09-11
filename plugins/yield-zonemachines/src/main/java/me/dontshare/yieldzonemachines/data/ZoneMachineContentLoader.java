package me.dontshare.yieldzonemachines.data;

import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Loads zone-machines.yml's "machines:"/economy settings - mirrors yield-packstations' own PackStationContentLoader's style, including its warn-and-skip (never crash) handling of a bad reference. */
public final class ZoneMachineContentLoader {

    public record ZoneMachineContent(List<ZoneMachine> machines, List<WalkInTrigger> walkInTriggers) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Supplier<Map<String, ZoneDefinition>> zones;

    public ZoneMachineContentLoader(JavaPlugin plugin, Logger logger, Supplier<Map<String, ZoneDefinition>> zones) {
        this.plugin = plugin;
        this.logger = logger;
        this.zones = zones;
    }

    public ZoneMachineContent load() {
        plugin.saveResource("zone-machines.yml", false);
        File file = new File(plugin.getDataFolder(), "zone-machines.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        List<ZoneMachine> machines = loadMachines(config);
        List<WalkInTrigger> walkInTriggers = loadWalkInTriggers(config);
        return new ZoneMachineContent(machines, walkInTriggers);
    }

    private List<WalkInTrigger> loadWalkInTriggers(YamlConfiguration config) {
        List<WalkInTrigger> triggers = new ArrayList<>();
        Map<String, ZoneDefinition> liveZones = zones.get();
        for (Map<?, ?> entry : config.getMapList("walk-in-triggers")) {
            Object zoneIdRaw = entry.get("zone");
            String zoneId = zoneIdRaw instanceof String s ? s : null;
            if (zoneId == null || !liveZones.containsKey(zoneId)) {
                logger.warning("Walk-in trigger references unknown zone '" + zoneIdRaw + "' - skipping.");
                continue;
            }
            Object typeRaw = entry.get("type");
            WalkInTriggerType type = parseTriggerType(zoneId, typeRaw);
            if (type == null) {
                continue;
            }
            Location location = parseLocation("zone '" + zoneId + "' (" + type + ")", entry.get("location"));
            if (location == null) {
                continue;
            }
            triggers.add(new WalkInTrigger(zoneId, type, location));
        }
        return triggers;
    }

    private WalkInTriggerType parseTriggerType(String zoneId, Object typeRaw) {
        if (!(typeRaw instanceof String typeStr)) {
            logger.warning("Walk-in trigger in zone '" + zoneId + "' is missing a 'type' - skipping.");
            return null;
        }
        try {
            return WalkInTriggerType.valueOf(typeStr.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Walk-in trigger in zone '" + zoneId + "' has an unknown type '" + typeStr + "' - skipping.");
            return null;
        }
    }

    private List<ZoneMachine> loadMachines(YamlConfiguration config) {
        List<ZoneMachine> machines = new ArrayList<>();
        Map<String, ZoneDefinition> liveZones = zones.get();
        for (Map<?, ?> entry : config.getMapList("machines")) {
            Object zoneIdRaw = entry.get("zone");
            String zoneId = zoneIdRaw instanceof String s ? s : null;
            if (zoneId == null || !liveZones.containsKey(zoneId)) {
                logger.warning("Zone machine references unknown zone '" + zoneIdRaw + "' - skipping.");
                continue;
            }
            Object typeRaw = entry.get("type");
            ZoneMachineType type = parseType(zoneId, typeRaw);
            if (type == null) {
                continue;
            }
            Location location = parseLocation("zone '" + zoneId + "' (" + type + ")", entry.get("location"));
            if (location == null) {
                continue;
            }
            machines.add(new ZoneMachine(zoneId, type, location,
                    PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(),
                    PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId()));
        }
        return machines;
    }

    private ZoneMachineType parseType(String zoneId, Object typeRaw) {
        if (!(typeRaw instanceof String typeStr)) {
            logger.warning("Zone machine in zone '" + zoneId + "' is missing a 'type' - skipping.");
            return null;
        }
        try {
            return ZoneMachineType.valueOf(typeStr.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Zone machine in zone '" + zoneId + "' has an unknown type '" + typeStr + "' - skipping.");
            return null;
        }
    }

    /** {@code [world, x, y, z, yaw, pitch]} - yaw/pitch optional (default 0), same self-contained shape every other station's own location already uses. */
    private Location parseLocation(String context, Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 4) {
            logger.warning("Zone machine (" + context + ") has an invalid 'location' - skipping.");
            return null;
        }
        if (!(list.get(0) instanceof String worldName)) {
            logger.warning("Zone machine (" + context + ") 'location' must start with a world name - skipping.");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            logger.warning("Zone machine (" + context + ") references unknown world '" + worldName + "' - skipping.");
            return null;
        }
        double x = toDouble(list.get(1));
        double y = toDouble(list.get(2));
        double z = toDouble(list.get(3));
        float yaw = list.size() > 4 ? (float) toDouble(list.get(4)) : 0f;
        float pitch = list.size() > 5 ? (float) toDouble(list.get(5)) : 0f;
        return new Location(world, x, y, z, yaw, pitch);
    }

    private double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
