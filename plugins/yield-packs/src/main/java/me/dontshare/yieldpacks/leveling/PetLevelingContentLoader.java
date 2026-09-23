package me.dontshare.yieldpacks.leveling;

import me.dontshare.yieldcore.config.BundledConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Loads pet-leveling.yml - mirrors QuestContentLoader's map-list parsing style for the milestone table. */
public final class PetLevelingContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public PetLevelingContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public PetLevelingConfig load() {
        BundledConfig.sync(plugin, "pet-leveling.yml");
        File file = new File(plugin.getDataFolder(), "pet-leveling.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        double base = config.getDouble("level-curve.base", 100);
        double exponent = config.getDouble("level-curve.exponent", 1.4);
        double damagePerLevel = config.getDouble("damage-per-level", 0.05);
        int maxLevel = Math.max(1, config.getInt("max-level", 99));
        int defaultLevelCap = Math.max(1, config.getInt("default-level-cap", 20));

        return new PetLevelingConfig(base, exponent, damagePerLevel, maxLevel, defaultLevelCap, loadMilestones(config));
    }

    private List<PetMilestone> loadMilestones(YamlConfiguration config) {
        List<PetMilestone> milestones = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("milestones")) {
            int level = entry.get("level") instanceof Number n ? n.intValue() : -1;
            if (level <= 0) {
                logger.warning("Pet milestone entry has an invalid 'level' - skipping entry.");
                continue;
            }
            MilestoneEffect effect;
            try {
                effect = MilestoneEffect.valueOf(String.valueOf(entry.get("effect")));
            } catch (IllegalArgumentException e) {
                logger.warning("Pet milestone at level " + level + " has an invalid 'effect' - skipping entry.");
                continue;
            }
            double value = entry.get("value") instanceof Number n ? n.doubleValue() : 0;
            milestones.add(new PetMilestone(level, effect, value));
        }
        milestones.sort(Comparator.comparingInt(PetMilestone::level));
        return milestones;
    }
}
