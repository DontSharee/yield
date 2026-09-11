package me.dontshare.yieldblocktree.data;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Loads blocktree.yml - one block per top-level entry, each with its own ordered 7-tier ladder. Returned map is keyed by {@code Material}, not the config's own section id (which is only a human-readable label). */
public final class BlockTreeContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public BlockTreeContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<Material, BlockTreeDefinition> load() {
        plugin.saveResource("blocktree.yml", false);
        File file = new File(plugin.getDataFolder(), "blocktree.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<Material, BlockTreeDefinition> definitions = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("blocks");
        if (section == null) {
            return definitions;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            BlockTreeDefinition definition = loadOne(id, entry);
            if (definition != null) {
                definitions.put(definition.material(), definition);
            }
        }
        return definitions;
    }

    private BlockTreeDefinition loadOne(String id, ConfigurationSection section) {
        String materialName = section.getString("material", "");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            logger.warning("Blocktree entry '" + id + "' has an invalid material '" + materialName + "' - skipping.");
            return null;
        }
        Material icon = Material.matchMaterial(section.getString("icon", materialName));
        if (icon == null) {
            icon = material;
        }
        String displayName = section.getString("display-name", id);

        List<BlockTreeTier> tiers = new ArrayList<>();
        for (Map<?, ?> rawTier : section.getMapList("tiers")) {
            Object goalValue = rawTier.get("goal");
            if (!(goalValue instanceof Number goalNumber)) {
                logger.warning("Blocktree entry '" + id + "' has a tier missing a numeric 'goal' - skipping that tier.");
                continue;
            }
            tiers.add(new BlockTreeTier(Math.max(1, goalNumber.longValue()), loadEffects(id, rawTier.get("effects"))));
        }
        tiers.sort((a, b) -> Long.compare(a.goal(), b.goal()));
        if (tiers.size() != 7) {
            logger.warning("Blocktree entry '" + id + "' has " + tiers.size() + " tier(s), expected 7.");
        }
        return new BlockTreeDefinition(material, displayName, icon, tiers);
    }

    private List<BlockTreeEffect> loadEffects(String id, Object rawEffects) {
        List<BlockTreeEffect> effects = new ArrayList<>();
        if (!(rawEffects instanceof List<?> effectList)) {
            return effects;
        }
        for (Object rawEffect : effectList) {
            if (!(rawEffect instanceof Map<?, ?> effectMap)) {
                continue;
            }
            Object typeValue = effectMap.get("type");
            Object amountValue = effectMap.get("value");
            if (typeValue == null || !(amountValue instanceof Number amountNumber)) {
                logger.warning("Blocktree entry '" + id + "' has a malformed effect - skipping it.");
                continue;
            }
            Object dataValue = effectMap.get("data");
            try {
                effects.add(new BlockTreeEffect(BlockTreeEffectType.parse(String.valueOf(typeValue)), amountNumber.doubleValue(),
                        dataValue != null ? String.valueOf(dataValue) : null));
            } catch (IllegalArgumentException e) {
                logger.warning("Blocktree entry '" + id + "' has an unknown effect type '" + typeValue + "' - skipping it.");
            }
        }
        return effects;
    }
}
