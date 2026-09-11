package me.dontshare.yieldmining.data;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads two files: mining.yml (hand-authored, static ore-type table - the
 * classic saveResource-once-then-never-overwrite pattern every other plugin
 * uses) and mining-spots.yml (admin-placed at runtime via
 * {@link #saveSpot}/{@link #removeSpot}, so it deliberately carries no
 * comments to preserve).
 */
public final class MiningContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public MiningContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public MiningContent load() {
        plugin.saveResource("mining.yml", false);
        File oreFile = new File(plugin.getDataFolder(), "mining.yml");
        Map<Material, OreDefinition> ores = loadOres(YamlConfiguration.loadConfiguration(oreFile));

        List<MiningSpot> spots = loadSpots(YamlConfiguration.loadConfiguration(spotsFile()));
        return new MiningContent(ores, spots);
    }

    private Map<Material, OreDefinition> loadOres(YamlConfiguration config) {
        Map<Material, OreDefinition> ores = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("ores");
        if (section == null) {
            return ores;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection oreSection = section.getConfigurationSection(id);
            if (oreSection == null) {
                continue;
            }
            Material material = Material.matchMaterial(id.toUpperCase(Locale.ROOT));
            if (material == null) {
                logger.warning("mining.yml ore entry '" + id + "' is not a valid material - skipping.");
                continue;
            }
            long coinReward = Math.max(0, oreSection.getLong("coin-reward", 0));
            int xp = Math.max(0, oreSection.getInt("xp", 0));
            int regenTicks = Math.max(1, oreSection.getInt("regen-seconds", 30)) * 20;

            Material dropMaterial = null;
            String dropMaterialName = oreSection.getString("drop-material", "");
            if (dropMaterialName != null && !dropMaterialName.isBlank()) {
                dropMaterial = Material.matchMaterial(dropMaterialName.toUpperCase(Locale.ROOT));
                if (dropMaterial == null) {
                    logger.warning("Ore '" + id + "' has an invalid 'drop-material' '" + dropMaterialName + "' - it'll pay coins/xp but no item.");
                }
            }
            int dropMin = Math.max(1, oreSection.getInt("drop-min", 1));
            int dropMax = Math.max(dropMin, oreSection.getInt("drop-max", dropMin));

            ores.put(material, new OreDefinition(material, coinReward, xp, regenTicks, dropMaterial, dropMin, dropMax));
        }
        return ores;
    }

    private List<MiningSpot> loadSpots(YamlConfiguration config) {
        List<MiningSpot> spots = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("spots")) {
            if (!(entry.get("world") instanceof String worldName)) {
                logger.warning("mining-spots.yml has a spot entry with no 'world' - skipping.");
                continue;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                logger.warning("Mining spot references unknown world '" + worldName + "' - skipping.");
                continue;
            }
            if (!(entry.get("material") instanceof String materialName)) {
                logger.warning("Mining spot in world '" + worldName + "' has no 'material' - skipping.");
                continue;
            }
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                logger.warning("Mining spot in world '" + worldName + "' has an invalid material '" + materialName + "' - skipping.");
                continue;
            }
            int x = toInt(entry.get("x"));
            int y = toInt(entry.get("y"));
            int z = toInt(entry.get("z"));
            spots.add(new MiningSpot(world, x, y, z, material));
        }
        return spots;
    }

    private int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** Read-modify-write: appends one new spot to mining-spots.yml. */
    public void saveSpot(MiningSpot spot) {
        File file = spotsFile();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> existing = new ArrayList<>(config.getMapList("spots"));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("world", spot.world().getName());
        entry.put("x", spot.x());
        entry.put("y", spot.y());
        entry.put("z", spot.z());
        entry.put("material", spot.material().name());
        existing.add(entry);

        config.set("spots", existing);
        save(config, file);
    }

    /** @return true if a spot existed at that exact position and was removed. */
    public boolean removeSpot(World world, int x, int y, int z) {
        File file = spotsFile();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> existing = new ArrayList<>(config.getMapList("spots"));
        boolean removed = existing.removeIf(entry ->
                world.getName().equals(entry.get("world"))
                        && x == toInt(entry.get("x"))
                        && y == toInt(entry.get("y"))
                        && z == toInt(entry.get("z")));
        if (removed) {
            config.set("spots", existing);
            save(config, file);
        }
        return removed;
    }

    private File spotsFile() {
        File file = new File(plugin.getDataFolder(), "mining-spots.yml");
        if (!file.exists()) {
            plugin.saveResource("mining-spots.yml", false);
        }
        return file;
    }

    private void save(YamlConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException e) {
            logger.warning("Failed to save mining-spots.yml: " + e.getMessage());
        }
    }
}
