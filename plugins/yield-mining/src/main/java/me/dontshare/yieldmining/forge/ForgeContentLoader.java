package me.dontshare.yieldmining.forge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Loads special-ore-tiers.yml - hand-authored, like mining.yml. */
public final class ForgeContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public ForgeContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /** Sorted rarest-first (lowest odds of hitting = highest one-in), so a roll can check tiers in that order and let the rarest one that hits win. */
    public List<SpecialOreTier> load() {
        plugin.saveResource("special-ore-tiers.yml", false);
        File file = new File(plugin.getDataFolder(), "special-ore-tiers.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        List<SpecialOreTier> tiers = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("tiers");
        if (section == null) {
            return tiers;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection tierSection = section.getConfigurationSection(id);
            if (tierSection == null) {
                continue;
            }
            int oneIn = tierSection.getInt("one-in", 0);
            double min = tierSection.getDouble("multiplier-min", 0);
            double max = tierSection.getDouble("multiplier-max", min);
            if (oneIn <= 0 || max < min) {
                logger.warning("Special ore tier '" + id + "' has an invalid 'one-in' or multiplier range - skipping.");
                continue;
            }
            tiers.add(new SpecialOreTier(id, tierSection.getString("display-name", id), oneIn, min, max));
        }
        tiers.sort((a, b) -> Integer.compare(b.oneIn(), a.oneIn()));
        return tiers;
    }
}
