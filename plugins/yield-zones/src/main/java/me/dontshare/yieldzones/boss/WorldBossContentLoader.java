package me.dontshare.yieldzones.boss;

import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads {@code worldboss.yml} - mirrors {@code ZoneContentLoader}'s style.
 * Takes the already-loaded zone map so each boss's {@code location} can be
 * resolved against its own zone's world without repeating a "world:" key -
 * a boss always lives in the same world as the zone it's configured under.
 */
public final class WorldBossContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public WorldBossContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, WorldBossDefinition> load(Map<String, ZoneDefinition> zones) {
        plugin.saveResource("worldboss.yml", false);
        File file = new File(plugin.getDataFolder(), "worldboss.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, WorldBossDefinition> bosses = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("bosses");
        if (section == null) {
            return bosses;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection boss = section.getConfigurationSection(id);
            if (boss == null) {
                continue;
            }
            WorldBossDefinition definition = loadOne(id, boss, zones);
            if (definition != null) {
                bosses.put(id, definition);
            }
        }
        return bosses;
    }

    private WorldBossDefinition loadOne(String id, ConfigurationSection section, Map<String, ZoneDefinition> zones) {
        String zoneId = section.getString("zone", "");
        ZoneDefinition zone = zones.get(zoneId);
        if (zone == null) {
            logger.warning("World boss '" + id + "' references unknown zone '" + zoneId + "' - skipping.");
            return null;
        }
        World world = zone.region().world();

        String materialName = section.getString("material", "STONE");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            logger.warning("World boss '" + id + "' has an invalid material '" + materialName + "' - falling back to STONE.");
            material = Material.STONE;
        }

        List<?> loc = section.getList("location");
        if (loc == null || loc.size() < 3) {
            logger.warning("World boss '" + id + "' is missing a valid 3-number 'location' - skipping.");
            return null;
        }
        double x = ((Number) loc.get(0)).doubleValue();
        double y = ((Number) loc.get(1)).doubleValue();
        double z = ((Number) loc.get(2)).doubleValue();
        Location location = new Location(world, x, y, z);

        int size = Math.max(1, section.getInt("size", 5));
        long maxHp = Math.max(1, section.getLong("max-hp", 100000));
        long checkIntervalMillis = Math.round(section.getDouble("check-interval-minutes", 10.0) * 60_000);
        double spawnChance = Math.max(0.0, Math.min(1.0, section.getDouble("spawn-chance", 0.5)));
        long rewardCoins = Math.max(0, section.getLong("reward-coins", 0));
        long rewardGems = Math.max(0, section.getLong("reward-gems", 0));
        long despawnAfterMillis = Math.round(section.getDouble("despawn-after-minutes", 30.0) * 60_000);

        return new WorldBossDefinition(id, zoneId, section.getString("display-name", id), material, size,
                location, maxHp, checkIntervalMillis, spawnChance, rewardCoins, rewardGems, despawnAfterMillis);
    }
}
