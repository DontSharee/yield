package me.dontshare.yieldachievements.data;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Loads achievements.yml - mirrors yield-quests' QuestContentLoader's style. */
public final class AchievementContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public AchievementContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, AchievementDefinition> load() {
        plugin.saveResource("achievements.yml", false);
        File file = new File(plugin.getDataFolder(), "achievements.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, AchievementDefinition> achievements = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("achievements");
        if (section == null) {
            return achievements;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            AchievementDefinition def = loadOne(id, entry);
            if (def != null) {
                achievements.put(id, def);
            }
        }
        return achievements;
    }

    private AchievementDefinition loadOne(String id, ConfigurationSection section) {
        String materialName = section.getString("icon", "PAPER");
        Material icon = Material.matchMaterial(materialName);
        if (icon == null) {
            logger.warning("Achievement '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            icon = Material.PAPER;
        }
        String triggerName = section.getString("trigger", "");
        GameAction trigger;
        Long impliedGoal = null;
        try {
            trigger = GameAction.valueOf(triggerName);
        } catch (IllegalArgumentException e) {
            // Also accept "<ACTION>_<N>" (e.g. "PRESTIGE_100", "UNLOCK_ZONE_5") -
            // sugar for "trigger: <ACTION>" with goal implied by the suffix, so
            // a specific numbered milestone can be its own self-documenting
            // achievement without a separate "goal:" key. The plain base action
            // (e.g. "PRESTIGE") still works too, firing on every occurrence.
            int split = triggerName.lastIndexOf('_');
            GameAction parsedAction = null;
            Long parsedGoal = null;
            if (split > 0) {
                try {
                    parsedAction = GameAction.valueOf(triggerName.substring(0, split));
                    parsedGoal = Long.parseLong(triggerName.substring(split + 1));
                } catch (IllegalArgumentException ignored) {
                    // neither the base action nor the numeric suffix parsed - fall through to the warning below
                }
            }
            if (parsedAction == null) {
                logger.warning("Achievement '" + id + "' has an invalid trigger '" + triggerName + "' - skipping.");
                return null;
            }
            trigger = parsedAction;
            impliedGoal = parsedGoal;
        }
        long goal = impliedGoal != null ? impliedGoal : Math.max(1, section.getLong("goal", 1));
        BigInteger rewardCredits = new BigInteger(String.valueOf(section.getLong("reward-credits", 0)));
        List<String> description = new ArrayList<>(section.getStringList("description"));
        return new AchievementDefinition(id, section.getString("display-name", id), description, icon, trigger, goal, rewardCredits);
    }
}
