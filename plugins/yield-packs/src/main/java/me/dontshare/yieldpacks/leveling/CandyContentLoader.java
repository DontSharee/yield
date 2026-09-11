package me.dontshare.yieldpacks.leveling;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Loads candy.yml - mirrors PackContentLoader's section-of-named-objects style. */
public final class CandyContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public CandyContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, Candy> load() {
        plugin.saveResource("candy.yml", false);
        File file = new File(plugin.getDataFolder(), "candy.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, Candy> candies = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("candy");
        if (section == null) {
            return candies;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection candySection = section.getConfigurationSection(id);
            if (candySection == null) {
                continue;
            }
            String materialName = candySection.getString("material", "COOKIE");
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                logger.warning("Candy '" + id + "' has an invalid material '" + materialName + "' - falling back to COOKIE.");
                material = Material.COOKIE;
            }
            int levelCapBonus = Math.max(1, candySection.getInt("level-cap-bonus", 5));
            double dropChance = Math.max(0, candySection.getDouble("drop-chance", 0.02));
            candies.put(id, new Candy(id, candySection.getString("display-name", id), material, levelCapBonus, dropChance));
        }
        return candies;
    }
}
