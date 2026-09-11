package me.dontshare.yieldskilltree.data;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Loads each tree's own yaml file (one per {@link #TREE_IDS} entry) into an in-memory registry - mirrors yield-quests' QuestContentLoader's style. */
public final class SkillTreeContentLoader {

    private static final List<String> TREE_IDS = List.of("upgrades", "prestige_upgrades");

    private final JavaPlugin plugin;
    private final Logger logger;

    public SkillTreeContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, SkillTree> load() {
        Map<String, SkillTree> trees = new LinkedHashMap<>();
        for (String treeId : TREE_IDS) {
            String fileName = treeId + ".yml";
            plugin.saveResource(fileName, false);
            File file = new File(plugin.getDataFolder(), fileName);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            SkillTree tree = loadTree(treeId, config);
            if (tree != null) {
                trees.put(treeId, tree);
            }
        }
        return trees;
    }

    private SkillTree loadTree(String treeId, YamlConfiguration config) {
        String currencyName = config.getString("currency", "COINS");
        Currency currency;
        try {
            currency = Currency.valueOf(currencyName);
        } catch (IllegalArgumentException e) {
            logger.warning("Tree '" + treeId + "' has an invalid currency '" + currencyName + "' - falling back to COINS.");
            currency = Currency.COINS;
        }

        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("nodes");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection nodeSection = section.getConfigurationSection(id);
                if (nodeSection == null) {
                    continue;
                }
                SkillNode node = loadNode(treeId, id, nodeSection);
                if (node != null) {
                    nodes.put(id, node);
                }
            }
        }
        return new SkillTree(treeId, currency, nodes);
    }

    private SkillNode loadNode(String treeId, String id, ConfigurationSection section) {
        String materialName = section.getString("material", "PAPER");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            logger.warning("Tree '" + treeId + "' node '" + id + "' has an invalid material '" + materialName + "' - falling back to PAPER.");
            material = Material.PAPER;
        }
        int slot = section.getInt("slot", -1);
        if (slot < 0 || slot > 53) {
            logger.warning("Tree '" + treeId + "' node '" + id + "' has an invalid slot '" + slot + "' - skipping.");
            return null;
        }
        String typeName = section.getString("type", "");
        NodeType type;
        try {
            type = NodeType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            logger.warning("Tree '" + treeId + "' node '" + id + "' has an invalid type '" + typeName + "' - skipping.");
            return null;
        }
        int maxLevel = Math.max(1, section.getInt("max-level", 1));
        String costFormula = section.getString("cost-formula", "0");
        String valueFormula = section.getString("value-formula", "0");
        return new SkillNode(id, section.getString("display-name", id), material, slot,
                section.getStringList("requires"), maxLevel, costFormula, valueFormula, type);
    }
}
