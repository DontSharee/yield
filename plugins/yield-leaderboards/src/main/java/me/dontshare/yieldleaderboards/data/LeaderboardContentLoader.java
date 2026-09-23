package me.dontshare.yieldleaderboards.data;

import me.dontshare.yieldcore.config.BundledConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Loads stats.yml + leaderboards.yml - mirrors yield-quests' QuestContentLoader's style. */
public final class LeaderboardContentLoader {

    public record Content(Map<String, StatDefinition> stats, Map<String, LeaderboardDefinition> leaderboards) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    public LeaderboardContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Content load() {
        Map<String, StatDefinition> stats = loadStats();
        Map<String, LeaderboardDefinition> leaderboards = loadLeaderboards(stats);
        return new Content(stats, leaderboards);
    }

    private Map<String, StatDefinition> loadStats() {
        BundledConfig.sync(plugin, "stats.yml");
        File file = new File(plugin.getDataFolder(), "stats.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, StatDefinition> stats = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("stats");
        if (section == null) {
            return stats;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            String field = s.getString("field");
            String typeName = s.getString("type", "");
            if (field == null) {
                logger.warning("Stat '" + id + "' has no 'field' - skipping.");
                continue;
            }
            StatType type;
            try {
                type = StatType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                logger.warning("Stat '" + id + "' has an invalid type '" + typeName + "' - skipping.");
                continue;
            }
            stats.put(id, new StatDefinition(id, field, type));
        }
        return stats;
    }

    private Map<String, LeaderboardDefinition> loadLeaderboards(Map<String, StatDefinition> stats) {
        BundledConfig.sync(plugin, "leaderboards.yml");
        File file = new File(plugin.getDataFolder(), "leaderboards.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, LeaderboardDefinition> leaderboards = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("leaderboards");
        if (section == null) {
            return leaderboards;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            String statId = s.getString("stat");
            if (statId == null || !stats.containsKey(statId)) {
                logger.warning("Leaderboard '" + id + "' references unknown stat '" + statId + "' - skipping.");
                continue;
            }
            String hologramName = s.getString("hologram", id);
            String title = s.getString("title", id);
            String colorHex = s.getString("color", "#FFFFFF");
            long updateDelayMillis = Math.max(1, s.getInt("update-delay-minutes", 10)) * 60_000L;
            leaderboards.put(id, new LeaderboardDefinition(id, hologramName, title, colorHex, statId, updateDelayMillis));
        }
        return leaderboards;
    }
}
