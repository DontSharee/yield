package me.dontshare.yieldteams.data;

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

/** Loads teams.yml - trophy drop config, name length rules, and the team-upgrade tree. */
public final class TeamsContentLoader {

    public record TeamsContent(TrophyConfig trophy, int minNameLength, int maxNameLength, Map<String, TeamUpgrade> upgrades) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    public TeamsContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public TeamsContent load() {
        plugin.saveResource("teams.yml", false);
        File file = new File(plugin.getDataFolder(), "teams.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        TrophyConfig trophy = loadTrophyConfig(config);
        int minLength = Math.max(1, config.getInt("name.min-length", 3));
        int maxLength = Math.max(minLength, config.getInt("name.max-length", 16));
        Map<String, TeamUpgrade> upgrades = loadUpgrades(config);

        return new TeamsContent(trophy, minLength, maxLength, upgrades);
    }

    private TrophyConfig loadTrophyConfig(YamlConfiguration config) {
        double dropChance = Math.max(0, Math.min(1, config.getDouble("trophy.drop-chance", 0.01)));
        String materialName = config.getString("trophy.material", "NETHER_STAR");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            logger.warning("teams.yml trophy has an invalid material '" + materialName + "' - falling back to NETHER_STAR.");
            material = Material.NETHER_STAR;
        }
        String displayName = config.getString("trophy.display-name", "&6Team Trophy");
        return new TrophyConfig(dropChance, material, displayName);
    }

    private Map<String, TeamUpgrade> loadUpgrades(YamlConfiguration config) {
        Map<String, TeamUpgrade> upgrades = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("upgrades");
        if (section == null) {
            return upgrades;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection upgradeSection = section.getConfigurationSection(id);
            if (upgradeSection == null) {
                continue;
            }
            TeamUpgrade upgrade = loadUpgrade(id, upgradeSection);
            if (upgrade != null) {
                upgrades.put(id, upgrade);
            }
        }
        return upgrades;
    }

    private TeamUpgrade loadUpgrade(String id, ConfigurationSection section) {
        String materialName = section.getString("icon", "PAPER");
        Material icon = Material.matchMaterial(materialName);
        if (icon == null) {
            logger.warning("Team upgrade '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            icon = Material.PAPER;
        }
        String typeName = section.getString("type", "");
        TeamUpgradeType type;
        try {
            type = TeamUpgradeType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            logger.warning("Team upgrade '" + id + "' has an invalid type '" + typeName + "' - skipping.");
            return null;
        }
        double valuePerLevel = section.getDouble("value-per-level", 0);
        List<BigInteger> costs = new ArrayList<>();
        for (Object raw : section.getList("costs", List.of())) {
            if (raw instanceof Number n) {
                costs.add(BigInteger.valueOf(n.longValue()));
            }
        }
        if (costs.isEmpty()) {
            logger.warning("Team upgrade '" + id + "' has no valid costs - skipping.");
            return null;
        }
        return new TeamUpgrade(id, section.getString("display-name", id), icon, type, valuePerLevel, costs);
    }
}
