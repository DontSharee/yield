package me.dontshare.yieldquests.data;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Loads rank-quests.yml's flat pool of quest templates - same warn-and-skip YamlConfiguration shape as QuestContentLoader. */
public final class RankQuestContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public RankQuestContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public List<RankQuestDefinition> load() {
        plugin.saveResource("rank-quests.yml", false);
        File file = new File(plugin.getDataFolder(), "rank-quests.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        List<RankQuestDefinition> pool = new ArrayList<>();

        ConfigurationSection section = config.getConfigurationSection("pool");
        if (section == null) {
            logger.warning("rank-quests.yml has no 'pool' section - the Rank Quest board will always be empty.");
            return pool;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            RankQuestDefinition definition = loadEntry(id, entry);
            if (definition != null) {
                pool.add(definition);
            }
        }
        return pool;
    }

    private RankQuestDefinition loadEntry(String id, ConfigurationSection section) {
        String actionName = section.getString("action", "");
        GameAction action;
        try {
            action = GameAction.valueOf(actionName);
        } catch (IllegalArgumentException e) {
            logger.warning("Rank Quest '" + id + "' has an invalid action '" + actionName + "' - skipping.");
            return null;
        }

        String materialName = section.getString("icon", "PAPER");
        Material icon = Material.matchMaterial(materialName);
        if (icon == null) {
            logger.warning("Rank Quest '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            icon = Material.PAPER;
        }

        int goal = Math.max(1, section.getInt("goal", 1));
        long rewardStars = Math.max(0, section.getLong("reward-stars", 0));
        String displayName = section.getString("display-name", id);
        return new RankQuestDefinition(id, displayName, icon, action, goal, rewardStars);
    }
}
