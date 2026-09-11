package me.dontshare.yieldranks.data;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Loads ranks.yml - mirrors yield-achievements' StoreContentLoader's shape. */
public final class RankContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public RankContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, DonorRank> load() {
        plugin.saveResource("ranks.yml", false);
        File file = new File(plugin.getDataFolder(), "ranks.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, DonorRank> ranks = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("ranks");
        if (section == null) {
            return ranks;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            ranks.put(id, loadOne(id, entry));
        }
        return ranks;
    }

    private DonorRank loadOne(String id, ConfigurationSection section) {
        String materialName = section.getString("icon", "PAPER");
        Material icon = Material.matchMaterial(materialName);
        if (icon == null) {
            logger.warning("Rank '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            icon = Material.PAPER;
        }
        return new DonorRank(
                id,
                section.getString("display-name", id),
                section.getInt("sort-order", 0),
                icon,
                section.getDouble("coin-multiplier", 1.0),
                section.getDouble("gem-multiplier", 1.0),
                section.getDouble("xp-multiplier", 1.0),
                section.getDouble("luck-bonus", 0.0),
                section.getInt("bonus-pet-slots", 0),
                section.getInt("bonus-enchant-slots", 0),
                section.getString("chat-color"),
                section.getString("tag")
        );
    }
}
