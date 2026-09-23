package me.dontshare.yieldleveling.data;

import me.dontshare.yieldcore.config.BundledConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Loads player-leveling.yml - mirrors yield-packs' PetLevelingContentLoader's shape. */
public final class PlayerLevelingContentLoader {

    private final JavaPlugin plugin;

    public PlayerLevelingContentLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public PlayerLevelingConfig load() {
        BundledConfig.sync(plugin, "player-leveling.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "player-leveling.yml"));

        double base = config.getDouble("level-curve.base", 100);
        double exponent = config.getDouble("level-curve.exponent", 1.4);
        int maxLevel = Math.max(1, config.getInt("max-level", 500));
        return new PlayerLevelingConfig(base, exponent, maxLevel);
    }
}
