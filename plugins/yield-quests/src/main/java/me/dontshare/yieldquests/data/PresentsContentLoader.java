package me.dontshare.yieldquests.data;

import me.dontshare.yieldcore.config.BundledConfig;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Loads daily.yml's present chain. */
public final class PresentsContentLoader {

    public record PresentsContent(List<PresentDefinition> presents) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    public PresentsContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public PresentsContent load() {
        BundledConfig.sync(plugin, "daily.yml");
        File file = new File(plugin.getDataFolder(), "daily.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, String[]> rarities = new java.util.LinkedHashMap<>();
        var raritySection = config.getConfigurationSection("gift-rarities");
        if (raritySection != null) {
            for (String key : raritySection.getKeys(false)) {
                rarities.put(key.toLowerCase(java.util.Locale.ROOT), new String[]{
                        raritySection.getString(key + ".name", key), raritySection.getString(key + ".head", "")});
            }
        }

        List<PresentDefinition> presents = new ArrayList<>();
        int position = 0;
        for (Map<?, ?> entry : config.getMapList("presents")) {
            position++;
            long unlockAfter = entry.get("unlock-after-minutes") instanceof Number n ? n.longValue() : -1;
            if (unlockAfter < 0) {
                logger.warning("daily.yml present entry has an invalid 'unlock-after-minutes' - skipping entry.");
                continue;
            }
            String headId = entry.get("head") instanceof String s && !s.isBlank() ? s : null;
            Object materialName = entry.get("fallback-material");
            Material fallback = materialName instanceof String s ? Material.matchMaterial(s) : null;
            if (fallback == null) {
                fallback = Material.CHEST;
            }
            long coins = entry.get("coins") instanceof Number n ? n.longValue() : 0;
            long diamonds = entry.get("diamonds") instanceof Number n ? n.longValue() : 0;
            // No rarity given: the reference layout's own - three common,
            // four rare, three epic, then legendary.
            String rarityKey = entry.get("rarity") instanceof String r ? r.toLowerCase(java.util.Locale.ROOT)
                    : position <= 3 ? "common" : position <= 7 ? "rare" : position <= 10 ? "epic" : "legendary";
            String[] rarity = rarities.get(rarityKey);
            if (rarity == null) {
                logger.warning("daily.yml present " + position + " has an unknown rarity '" + rarityKey + "'.");
            }
            String name = rarity != null ? rarity[0] : Character.toUpperCase(rarityKey.charAt(0)) + rarityKey.substring(1);
            String texture = rarity != null && !rarity[1].isBlank() ? rarity[1] : null;
            presents.add(new PresentDefinition(unlockAfter, headId, fallback, Math.max(0, coins), Math.max(0, diamonds), name, texture));
        }
        presents.sort((a, b) -> Long.compare(a.unlockAfterMinutes(), b.unlockAfterMinutes()));
        return new PresentsContent(presents);
    }
}
