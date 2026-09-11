package me.dontshare.yieldpacks.mastery;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Loads masteries.yml - mirrors yield-leveling's PlayerLevelingContentLoader shape. */
public final class MasteryContentLoader {

    private final JavaPlugin plugin;

    public MasteryContentLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public MasteryConfig load() {
        plugin.saveResource("masteries.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "masteries.yml"));

        double base = config.getDouble("level-curve.base", 50);
        double exponent = config.getDouble("level-curve.exponent", 1.3);
        int maxLevel = Math.max(1, config.getInt("max-level", 100));
        double bonusPerLevel = config.getDouble("bonus-per-level", 0.0005);
        return new MasteryConfig(base, exponent, maxLevel, bonusPerLevel);
    }
}
