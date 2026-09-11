package me.dontshare.yieldpacks.data;

import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldpacks.pity.PityTier;
import me.dontshare.yieldpacks.shop.ShopConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads packs.yml (rarities -&gt; items -&gt; packs, in that dependency
 * order) into fresh in-memory registries. Content is fully data-driven since
 * the pet catalog is expected to change often - adding/renaming pets or
 * packs never needs a code change, just an edit to packs.yml and a
 * /admin packs reload.
 */
public final class PackContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public PackContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /** An immutable, atomically-swappable bundle of the registries plus the shop's own settings. */
    public record ContentSnapshot(RarityRegistry rarities, ItemRegistry items, PackRegistry packs, ShopConfig shop,
                                   List<PityTier> pityTiers) {
    }

    public ContentSnapshot load() {
        plugin.saveResource("packs.yml", false);
        File file = new File(plugin.getDataFolder(), "packs.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, Rarity> rarities = loadRarities(config.getConfigurationSection("rarities"));
        Map<String, ItemDefinition> items = loadItems(config.getConfigurationSection("items"), rarities);
        Map<String, PackDefinition> packs = loadPacks(config.getConfigurationSection("packs"), items);
        ShopConfig shop = loadShopConfig(config);
        List<PityTier> pityTiers = loadPityTiers(config);

        return new ContentSnapshot(new RarityRegistry(rarities), new ItemRegistry(items), new PackRegistry(packs),
                shop, pityTiers);
    }

    private List<PityTier> loadPityTiers(YamlConfiguration config) {
        List<PityTier> tiers = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("pity")) {
            Object rolls = entry.get("rolls");
            Object multiplier = entry.get("multiplier");
            if (!(rolls instanceof Number rollsNumber) || !(multiplier instanceof Number multiplierNumber)) {
                logger.warning("Skipping invalid pity tier entry: " + entry);
                continue;
            }
            tiers.add(new PityTier(rollsNumber.intValue(), multiplierNumber.doubleValue()));
        }
        tiers.sort(Comparator.comparingInt(PityTier::rolls));
        return tiers;
    }

    private ShopConfig loadShopConfig(YamlConfiguration config) {
        long resetIntervalMillis = config.getLong("shop.reset-interval-minutes", 5) * 60_000L;
        long openCooldownMillis = Math.round(config.getDouble("open-cooldown-seconds", 1.0) * 1000);
        return new ShopConfig(resetIntervalMillis, openCooldownMillis);
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
                    s.getDouble("damage", 1.0),
                    s.getBoolean("track-exists", false),
                    s.getStringList("lore"),
                    FusionTier.NORMAL,
                    id,
                    s.getBoolean("huge", false),
                    s.getDouble("huge-damage-percent", 0.0)));
        }
        addFusionTiers(result);
        return result;
    }

    /**
     * Every base pet automatically gets Golden/Rainbow/Dark Matter variants
     * for free - no packs.yml entry needed per tier. These are crafted (see
     * {@code FusionService}), never rolled, so they're never referenced by a
     * pack's {@code pool:} and never touch the exists-counter/pity/luck
     * machinery that only cares about what a pack can actually roll.
     */
    private void addFusionTiers(Map<String, ItemDefinition> items) {
        List<ItemDefinition> baseItems = List.copyOf(items.values());
        for (ItemDefinition base : baseItems) {
            for (FusionTier tier : FusionTier.values()) {
                if (tier == FusionTier.NORMAL) {
                    continue;
                }
                items.put(tier.idFor(base.id()), new ItemDefinition(
                        tier.idFor(base.id()),
                        // Deliberately identical to the base's own plain name
                        // across every tier - the tier's gradient lives only
                        // in FusionTier#tag(), never baked into the name
                        // itself (see that class's own Javadoc for why).
                        base.displayName(),
                        base.material(),
                        base.customModelData(),
                        base.headDatabaseId(),
                        base.rarityId(),
                        base.damage() * tier.damageMultiplier(),
                        false,
                        base.lore(),
                        tier,
                        base.id(),
                        base.huge(),
                        base.hugeDamagePercent()));
            }
        }
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
                    pool,
                    s.getDouble("shop-weight", 1.0),
                    s.getDouble("shop-luck-exponent", 0.0),
                    Math.max(1, s.getInt("min-stock", 1)),
                    Math.max(1, s.getInt("max-stock", 1))));
            autoSort++;
        }
        return result;
    }
}
