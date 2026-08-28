package me.dontshare.yieldpacks.data;

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

/**
 * Loads packs.yml (rarities -&gt; items -&gt; packs, in that dependency
 * order) into fresh in-memory registries. Content is fully data-driven since
 * the pet catalog is expected to change often - adding/renaming pets or
 * packs never needs a code change, just an edit to packs.yml and a
 * /packsadmin reload.
 */
public final class PackContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public PackContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /** An immutable, atomically-swappable bundle of the three registries. */
    public record ContentSnapshot(RarityRegistry rarities, ItemRegistry items, PackRegistry packs) {
    }

    public ContentSnapshot load() {
        plugin.saveResource("packs.yml", false);
        File file = new File(plugin.getDataFolder(), "packs.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, Rarity> rarities = loadRarities(config.getConfigurationSection("rarities"));
        Map<String, ItemDefinition> items = loadItems(config.getConfigurationSection("items"), rarities);
        Map<String, PackDefinition> packs = loadPacks(config.getConfigurationSection("packs"), items);

        return new ContentSnapshot(new RarityRegistry(rarities), new ItemRegistry(items), new PackRegistry(packs));
    }

    private Map<String, Rarity> loadRarities(ConfigurationSection section) {
        Map<String, Rarity> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            result.put(id, new Rarity(
                    id,
                    s.getString("display-name", id),
                    s.getString("color", "#FFFFFF"),
                    s.getInt("sort", 0),
                    s.getDouble("luck-exponent", 0.0)));
        }
        return result;
    }

    private Map<String, ItemDefinition> loadItems(ConfigurationSection section, Map<String, Rarity> rarities) {
        Map<String, ItemDefinition> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            String rarityId = s.getString("rarity");
            if (rarityId == null || !rarities.containsKey(rarityId)) {
                logger.warning("Pet '" + id + "' references unknown rarity '" + rarityId + "' - skipping.");
                continue;
            }
            Material material = Material.matchMaterial(s.getString("material", "PLAYER_HEAD"));
            if (material == null) {
                logger.warning("Pet '" + id + "' has an invalid material - defaulting to PLAYER_HEAD.");
                material = Material.PLAYER_HEAD;
            }
            Integer modelData = s.contains("custom-model-data") ? s.getInt("custom-model-data") : null;
            String headDatabaseId = s.getString("head-database-id");
            result.put(id, new ItemDefinition(
                    id,
                    s.getString("display-name", id),
                    material,
                    modelData,
                    headDatabaseId,
                    rarityId,
                    s.getDouble("value-per-second", 0.0),
                    s.getBoolean("track-exists", false),
                    s.getStringList("lore")));
        }
        return result;
    }

    private Map<String, PackDefinition> loadPacks(ConfigurationSection section, Map<String, ItemDefinition> items) {
        Map<String, PackDefinition> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        int autoSort = 0;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            List<PackPoolEntry> pool = new ArrayList<>();
            List<Map<?, ?>> poolSection = s.getMapList("pool");
            for (Map<?, ?> entry : poolSection) {
                Object itemId = entry.get("item");
                Object weight = entry.get("weight");
                if (!(itemId instanceof String itemIdStr) || !items.containsKey(itemIdStr)) {
                    logger.warning("Pack '" + id + "' references unknown pet '" + itemId + "' - skipping entry.");
                    continue;
                }
                double weightValue = weight instanceof Number number ? number.doubleValue() : 1.0;
                pool.add(new PackPoolEntry(itemIdStr, weightValue));
            }
            if (pool.isEmpty()) {
                logger.warning("Pack '" + id + "' has no valid pool entries - skipping pack.");
                continue;
            }
            Material material = Material.matchMaterial(s.getString("material", "CHEST"));
            if (material == null) {
                material = Material.CHEST;
            }
            Integer modelData = s.contains("custom-model-data") ? s.getInt("custom-model-data") : null;
            result.put(id, new PackDefinition(
                    id,
                    s.getString("display-name", id),
                    material,
                    modelData,
                    s.getLong("coin-cost", 0L),
                    s.getLong("gem-cost", 0L),
                    s.contains("sort") ? s.getInt("sort") : autoSort,
                    pool));
            autoSort++;
        }
        return result;
    }
}
