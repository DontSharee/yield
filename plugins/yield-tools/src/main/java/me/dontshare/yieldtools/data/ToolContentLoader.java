package me.dontshare.yieldtools.data;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Loads tools.yml in file order - the order IS the path. Same warn-and-skip handling of a bad entry as every other loader here. */
public final class ToolContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public ToolContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public List<ToolDefinition> load() {
        plugin.saveResource("tools.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "tools.yml"));
        ConfigurationSection section = config.getConfigurationSection("tools");
        List<ToolDefinition> tools = new ArrayList<>();
        if (section == null) {
            logger.warning("tools.yml has no 'tools:' section - no tools will be offered.");
            return tools;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Material material = Material.matchMaterial(s.getString("material", ""));
            if (material == null || !material.isItem()) {
                logger.warning("Tool '" + id + "' has an invalid item material '" + s.getString("material")
                        + "' - skipping it. Every tool after it moves up one place on the path.");
                continue;
            }
            tools.add(new ToolDefinition(
                    tools.size(),
                    id,
                    s.getString("display-name", id),
                    material,
                    Math.max(0.0, s.getDouble("power", 0.0)),
                    Math.max(0L, s.getLong("cost-coins", 0L))));
        }
        return tools;
    }
}
