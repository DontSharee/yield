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

        List<PresentDefinition> presents = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("presents")) {
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
            presents.add(new PresentDefinition(unlockAfter, headId, fallback, Math.max(0, coins), Math.max(0, diamonds)));
        }
        presents.sort((a, b) -> Long.compare(a.unlockAfterMinutes(), b.unlockAfterMinutes()));
        return new PresentsContent(presents);
    }
}
