package me.dontshare.yieldupgrades.data;

import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Loads upgrades.yml's "types:"/"stations:" tree - mirrors yield-zones' ZoneContentLoader's style, including its warn-and-skip (never crash) handling of a bad reference. */
public final class UpgradeContentLoader {

    public record UpgradeContent(Map<String, UpgradeType> types, List<UpgradeStation> stations) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Supplier<Map<String, ZoneDefinition>> zones;

    public UpgradeContentLoader(JavaPlugin plugin, Logger logger, Supplier<Map<String, ZoneDefinition>> zones) {
        this.plugin = plugin;
        this.logger = logger;
        this.zones = zones;
    }

    public UpgradeContent load() {
        plugin.saveResource("upgrades.yml", false);
        File file = new File(plugin.getDataFolder(), "upgrades.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, UpgradeType> types = loadTypes(config);
        List<UpgradeStation> stations = loadStations(config, types);
        return new UpgradeContent(types, stations);
    }

    private Map<String, UpgradeType> loadTypes(YamlConfiguration config) {
        Map<String, UpgradeType> types = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("types");
        if (section == null) {
            return types;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection typeSection = section.getConfigurationSection(id);
            if (typeSection == null) {
                continue;
            }
            UpgradeType type = loadType(id, typeSection);
            if (type != null) {
                types.put(id, type);
            }
        }
        return types;
    }

    private UpgradeType loadType(String id, ConfigurationSection section) {
        String effectName = section.getString("effect", "");
        UpgradeEffect effect;
        try {
            effect = UpgradeEffect.valueOf(effectName);
        } catch (IllegalArgumentException e) {
            logger.warning("Upgrade type '" + id + "' has an invalid effect '" + effectName + "' - skipping.");
            return null;
        }
        String materialName = section.getString("icon", "PAPER");
        Material icon = Material.matchMaterial(materialName);
        if (icon == null) {
            logger.warning("Upgrade type '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            icon = Material.PAPER;
        }
        double valuePerLevel = section.getDouble("value-per-level", 0);
        double chancePerLevel = section.getDouble("chance-per-level", 0);
        double flatPerLevel = section.getDouble("flat-per-level", 0);
        int maxLevel = Math.max(1, section.getInt("max-level", 10));
        double costBase = Math.max(0, section.getDouble("cost-base", 100));
        double costGrowth = Math.max(1.0, section.getDouble("cost-growth", 1.1));
        String displayName = section.getString("display-name", id);
        return new UpgradeType(id, displayName, icon, effect, valuePerLevel, chancePerLevel, flatPerLevel,
                maxLevel, costBase, costGrowth);
    }

    private List<UpgradeStation> loadStations(YamlConfiguration config, Map<String, UpgradeType> types) {
        List<UpgradeStation> stations = new ArrayList<>();
        Map<String, ZoneDefinition> liveZones = zones.get();
        for (Map<?, ?> entry : config.getMapList("stations")) {
            Object zoneIdRaw = entry.get("zone");
            String zoneId = zoneIdRaw instanceof String s ? s : null;
            if (zoneId == null || !liveZones.containsKey(zoneId)) {
                logger.warning("Upgrade station references unknown zone '" + zoneIdRaw + "' - skipping.");
                continue;
            }
            Object typeIdRaw = entry.get("type");
            UpgradeType type = typeIdRaw instanceof String s ? types.get(s) : null;
            if (type == null) {
                logger.warning("Upgrade station in zone '" + zoneId + "' references unknown upgrade type '" + typeIdRaw + "' - skipping.");
                continue;
            }
            int cap = entry.get("cap") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            Location location = parseLocation(zoneId, type.id(), entry.get("location"));
            if (location == null) {
                continue;
            }
            stations.add(new UpgradeStation(zoneId, type, cap, location,
                    PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(),
                    PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId()));
        }
        return stations;
    }

    /** {@code [world, x, y, z, yaw, pitch]} - yaw/pitch optional (default 0), self-contained rather than derived from the zone's own region, so a station's real placement never depends on how ZoneRegion happens to be shaped. */
    private Location parseLocation(String zoneId, String typeId, Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 4) {
            logger.warning("Upgrade station (zone '" + zoneId + "', type '" + typeId + "') has an invalid 'location' - skipping.");
            return null;
        }
        if (!(list.get(0) instanceof String worldName)) {
            logger.warning("Upgrade station (zone '" + zoneId + "', type '" + typeId + "') 'location' must start with a world name - skipping.");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            logger.warning("Upgrade station (zone '" + zoneId + "', type '" + typeId + "') references unknown world '" + worldName + "' - skipping.");
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
