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

/** Loads milestones.yml - one category per top-level entry, each with its own ordered tier ladder. */
public final class MilestoneContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public MilestoneContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, MilestoneCategory> load() {
        plugin.saveResource("milestones.yml", false);
        File file = new File(plugin.getDataFolder(), "milestones.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, MilestoneCategory> categories = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("categories");
        if (section == null) {
            return categories;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            MilestoneCategory category = loadOne(id, entry);
            if (category != null) {
                categories.put(id, category);
            }
        }
        return categories;
    }

    private MilestoneCategory loadOne(String id, ConfigurationSection section) {
        String materialName = section.getString("icon", "PAPER");
        Material icon = Material.matchMaterial(materialName);
        if (icon == null) {
            logger.warning("Milestone category '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            icon = Material.PAPER;
        }
        String triggerName = section.getString("trigger", "");
        GameAction trigger;
        try {
            trigger = GameAction.valueOf(triggerName);
        } catch (IllegalArgumentException e) {
            logger.warning("Milestone category '" + id + "' has an invalid trigger '" + triggerName + "' - skipping.");
            return null;
        }

        List<MilestoneTier> tiers = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("tiers")) {
            Object goalValue = raw.get("goal");
            if (!(goalValue instanceof Number goalNumber)) {
                logger.warning("Milestone category '" + id + "' has a tier missing a numeric 'goal' - skipping that tier.");
                continue;
            }
            long rewardCoins = raw.get("reward-coins") instanceof Number n ? n.longValue() : 0;
            long rewardDiamonds = raw.get("reward-diamonds") instanceof Number n ? n.longValue() : 0;
            long rewardCredits = raw.get("reward-credits") instanceof Number n ? n.longValue() : 0;
            Object potionValue = raw.get("reward-potion");
            String rewardPotionId = potionValue != null ? String.valueOf(potionValue) : null;
            tiers.add(new MilestoneTier(Math.max(1, goalNumber.longValue()), BigInteger.valueOf(Math.max(0, rewardCoins)),
                    BigInteger.valueOf(Math.max(0, rewardDiamonds)), BigInteger.valueOf(Math.max(0, rewardCredits)), rewardPotionId));
        }
        tiers.sort((a, b) -> Long.compare(a.goal(), b.goal()));
        return new MilestoneCategory(id, section.getString("display-name", id), icon, trigger, tiers);
    }
}
