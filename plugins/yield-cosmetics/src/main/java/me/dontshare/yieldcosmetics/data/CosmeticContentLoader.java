package me.dontshare.yieldcosmetics.data;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Loads cosmetics.yml into one map per category - mirrors QuestContentLoader's style. */
public final class CosmeticContentLoader {

    public record Content(Map<String, Cosmetic> chatColors, Map<String, Cosmetic> nameplates, Map<String, Cosmetic> tags) {
        public Map<String, Cosmetic> byCategory(CosmeticCategory category) {
            return switch (category) {
                case CHAT_COLOR -> chatColors;
                case NAMEPLATE -> nameplates;
                case TAG -> tags;
            };
        }
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    public CosmeticContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Content load() {
        plugin.saveResource("cosmetics.yml", false);
        File file = new File(plugin.getDataFolder(), "cosmetics.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        return new Content(
                loadCategory(config, CosmeticCategory.CHAT_COLOR),
                loadCategory(config, CosmeticCategory.NAMEPLATE),
                loadCategory(config, CosmeticCategory.TAG));
    }

    private Map<String, Cosmetic> loadCategory(YamlConfiguration config, CosmeticCategory category) {
        Map<String, Cosmetic> cosmetics = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection(category.configSection());
        if (section == null) {
            return cosmetics;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection cosmeticSection = section.getConfigurationSection(id);
            if (cosmeticSection == null) {
                continue;
            }
            Cosmetic cosmetic = loadCosmetic(id, category, cosmeticSection);
            if (cosmetic != null) {
                cosmetics.put(id, cosmetic);
            }
        }
        return cosmetics;
    }

    private Cosmetic loadCosmetic(String id, CosmeticCategory category, ConfigurationSection section) {
        String materialName = section.getString("icon", "PAPER");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            logger.warning("Cosmetic '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            material = Material.PAPER;
        }
        String styleKey = category == CosmeticCategory.TAG ? "badge" : "style";
        String style = section.getString(styleKey);
        if (style == null || style.isBlank()) {
            logger.warning("Cosmetic '" + id + "' is missing its '" + styleKey + "' - skipping.");
            return null;
        }
        String permission = section.getString("permission"); // null = free for everyone
        return new Cosmetic(id, category, section.getString("display-name", id), permission, style, material);
    }
}
