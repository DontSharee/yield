package me.dontshare.yieldpacks.mastery;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Loads masteries.yml - mirrors yield-leveling's PlayerLevelingContentLoader shape, extended with each track's own perk list. */
public final class MasteryContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public MasteryContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public MasteryConfig load() {
        plugin.saveResource("masteries.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "masteries.yml"));

        double base = config.getDouble("level-curve.base", 50);
        double exponent = config.getDouble("level-curve.exponent", 1.3);
        int maxLevel = Math.max(1, config.getInt("max-level", 99));

        Map<MasteryType, List<MasteryPerk>> perksByTrack = new EnumMap<>(MasteryType.class);
        ConfigurationSection tracksSection = config.getConfigurationSection("tracks");
        if (tracksSection != null) {
            for (String trackKey : tracksSection.getKeys(false)) {
                MasteryType type;
                try {
                    type = MasteryType.valueOf(trackKey.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    logger.warning("masteries.yml has an unknown track '" + trackKey + "' - skipping.");
                    continue;
                }
                perksByTrack.put(type, loadPerks(trackKey, tracksSection.getConfigurationSection(trackKey)));
            }
        }
        return new MasteryConfig(base, exponent, maxLevel, perksByTrack);
    }

    private List<MasteryPerk> loadPerks(String trackKey, ConfigurationSection trackSection) {
        List<MasteryPerk> result = new ArrayList<>();
        if (trackSection == null) {
            return result;
        }
        for (Map<?, ?> entry : trackSection.getMapList("perks")) {
            Object idValue = entry.get("id");
            Object statValue = entry.get("stat");
            Object levelValue = entry.get("level");
            Object valueValue = entry.get("value");
            if (!(idValue instanceof String id) || statValue == null || !(levelValue instanceof Number) || !(valueValue instanceof Number)) {
                logger.warning("masteries.yml track '" + trackKey + "' has a malformed perk entry - skipping it.");
                continue;
            }
            MasteryStat stat;
            try {
                stat = MasteryStat.valueOf(String.valueOf(statValue).toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                logger.warning("masteries.yml track '" + trackKey + "' perk '" + id + "' has an unknown stat '" + statValue + "' - skipping it.");
                continue;
            }
            String displayName = entry.get("display-name") instanceof String s ? s : id;
            int level = ((Number) levelValue).intValue();
            double value = ((Number) valueValue).doubleValue();
            @SuppressWarnings("unchecked")
            List<String> description = entry.get("description") instanceof List<?> list
                    ? (List<String>) list.stream().map(String::valueOf).toList()
                    : List.of();
            result.add(new MasteryPerk(id, displayName, level, stat, value, description));
        }
        result.sort((a, b) -> Integer.compare(a.level(), b.level()));
        return result;
    }
}
