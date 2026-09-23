package me.dontshare.yieldquests.data;

import me.dontshare.yieldcore.config.BundledConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Loads quests.yml's category/tier tree into an in-memory registry - mirrors yield-zones' ZoneContentLoader's style. */
public final class QuestContentLoader {

    public record QuestContent(Map<String, QuestCategory> categories) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    public QuestContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public QuestContent load() {
        BundledConfig.sync(plugin, "quests.yml");
        File file = new File(plugin.getDataFolder(), "quests.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        return new QuestContent(loadCategories(config));
    }

    private Map<String, QuestCategory> loadCategories(YamlConfiguration config) {
        Map<String, QuestCategory> categories = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("categories");
        if (section == null) {
            return categories;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection categorySection = section.getConfigurationSection(id);
            if (categorySection == null) {
                continue;
            }
            QuestCategory category = loadCategory(id, categorySection);
            if (category != null) {
                categories.put(id, category);
            }
        }
        return categories;
    }

    private QuestCategory loadCategory(String id, ConfigurationSection section) {
        String materialName = section.getString("icon", "PAPER");
        Material icon = Material.matchMaterial(materialName);
        if (icon == null) {
            logger.warning("Quest category '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            icon = Material.PAPER;
        }

        Map<QuestDifficulty, QuestDefinition> tiers = new EnumMap<>(QuestDifficulty.class);
        for (QuestDifficulty difficulty : QuestDifficulty.values()) {
            ConfigurationSection tierSection = section.getConfigurationSection(difficulty.name().toLowerCase(java.util.Locale.ROOT));
            if (tierSection == null) {
                continue;
            }
            tiers.put(difficulty, loadQuest(id, difficulty, tierSection));
        }
        if (tiers.isEmpty()) {
            logger.warning("Quest category '" + id + "' has no easy/medium/hard tiers defined - skipping category.");
            return null;
        }

        return new QuestCategory(id, section.getString("display-name", id), icon, tiers);
    }

    private QuestDefinition loadQuest(String categoryId, QuestDifficulty difficulty, ConfigurationSection section) {
        String actionName = section.getString("action", "");
        GameAction action;
        try {
            action = GameAction.valueOf(actionName);
        } catch (IllegalArgumentException e) {
            logger.warning("Quest category '" + categoryId + "' tier '" + difficulty + "' has an invalid action '"
                    + actionName + "' - defaulting to KILL_CUBE.");
            action = GameAction.KILL_CUBE;
        }
        int goal = Math.max(1, section.getInt("goal", 1));
        long rewardCoins = Math.max(0, section.getLong("reward-coins", 0));
        long rewardDiamonds = Math.max(0, section.getLong("reward-diamonds", 0));
        long rewardCredits = Math.max(0, section.getLong("reward-credits", 0));
        String rewardPetId = section.getString("reward-pet", null);
        String displayName = section.getString("display-name", categoryId + " " + difficulty);
        return new QuestDefinition(displayName, action, goal, rewardCoins, rewardDiamonds, rewardCredits, rewardPetId);
    }
}
