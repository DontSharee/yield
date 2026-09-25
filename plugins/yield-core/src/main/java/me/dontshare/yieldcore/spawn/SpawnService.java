package me.dontshare.yieldcore.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

/** Loads/saves {@code spawn.yml} - the single server-wide spawn point {@code /spawn} teleports to and {@code /setspawn} overwrites. */
public final class SpawnService {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final File file;
    private volatile Location location;

    public SpawnService(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.file = new File(plugin.getDataFolder(), "spawn.yml");
        if (!file.exists()) {
            plugin.saveResource("spawn.yml", false);
        }
        this.location = load();
    }

    public Location get() {
        return location.clone();
    }

    /** Overwrites spawn.yml and takes effect immediately - no reload needed. */
    public void set(Location newLocation) {
        this.location = newLocation.clone();
        YamlConfiguration config = new YamlConfiguration();
        config.set("world", newLocation.getWorld().getName());
        config.set("x", newLocation.getX());
        config.set("y", newLocation.getY());
        config.set("z", newLocation.getZ());
        config.set("yaw", (double) newLocation.getYaw());
        config.set("pitch", (double) newLocation.getPitch());
        try {
            config.save(file);
        } catch (Exception e) {
            logger.warning("Failed to save spawn.yml: " + e.getMessage());
        }
    }

    private Location load() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String worldName = config.getString("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (world == null) {
                throw new IllegalStateException("spawn.yml references unknown world '" + worldName + "' and no worlds are loaded at all.");
            }
            logger.warning("spawn.yml references unknown world '" + worldName + "' - falling back to '" + world.getName() + "'.");
        }
        double x = config.getDouble("x", 0.5);
        double y = config.getDouble("y", 65.0);
        double z = config.getDouble("z", 0.5);
        float yaw = (float) config.getDouble("yaw", 0.0);
        float pitch = (float) config.getDouble("pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }
}
