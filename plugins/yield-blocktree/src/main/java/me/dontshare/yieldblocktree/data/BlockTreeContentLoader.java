package me.dontshare.yieldblocktree.data;

import me.dontshare.yieldcore.config.BundledConfig;
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
        BundledConfig.sync(plugin, "blocktree.yml");
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
        return new BlockTreeDefinition(material, displayName, icon, tiers, perkTitle(section, tiers));
    }

    /** A {@code perk} effect anywhere on the tree names it; otherwise the block's own {@code perk-name}, or none. */
    private static String perkTitle(ConfigurationSection section, List<BlockTreeTier> tiers) {
        for (int i = tiers.size() - 1; i >= 0; i--) {
            for (BlockTreeEffect effect : tiers.get(i).effects()) {
                if (effect.type() == BlockTreeEffectType.PERK) {
                    return BlockPerk.parse(effect.data()).title();
                }
            }
        }
        return section.getString("perk-name");
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
            BlockTreeEffectType type;
            try {
                type = BlockTreeEffectType.parse(String.valueOf(typeValue));
            } catch (IllegalArgumentException e) {
                logger.warning("Blocktree entry '" + id + "' has an unknown effect type '" + typeValue + "' - skipping it.");
                continue;
            }
            String data = dataValue != null ? String.valueOf(dataValue) : null;
            if (type == BlockTreeEffectType.PERK) {
                // Stored by enum name, so the service can compare it straight against BlockPerk.name().
                try {
                    data = BlockPerk.parse(String.valueOf(data)).name();
                } catch (IllegalArgumentException e) {
                    logger.warning("Blocktree entry '" + id + "' has an unknown perk '" + data + "' - skipping it.");
                    continue;
                }
            }
            effects.add(new BlockTreeEffect(type, amountNumber.doubleValue(), data));
        }
        return effects;
    }
}
