package me.dontshare.yieldpacks.display;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Loads pet-display.yml into a {@link PetDisplayConfig} - mirrors {@code PackContentLoader}'s load-from-yml shape. */
public final class PetDisplayConfigLoader {

    private final JavaPlugin plugin;

    public PetDisplayConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public PetDisplayConfig load() {
        plugin.saveResource("pet-display.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "pet-display.yml"));

        return new PetDisplayConfig(
                config.getInt("grid.columns", 3),
                config.getDouble("grid.column-spacing", 0.9),
                config.getDouble("grid.row-spacing", 0.9),
                config.getDouble("grid.start-distance", 1.5),
                config.getDouble("grid.height-offset", 0.2),
                config.getDouble("hover.amplitude", 0.15),
                config.getInt("hover.period-ticks", 40),
                config.getDouble("hover.movement-threshold", 0.05),
                config.getInt("update-interval-ticks", 4),
                config.getDouble("view-distance", 48),
                (float) config.getDouble("item.scale", 0.85),
                (float) config.getDouble("item.pitch-degrees", -5),
                (float) config.getDouble("item.yaw-degrees", 180));
    }
}
