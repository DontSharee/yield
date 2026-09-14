package me.dontshare.yieldachievements.store;

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

/** Loads store.yml - the /buy screen's product catalog. */
public final class StoreContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public StoreContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, StoreProduct> load() {
        plugin.saveResource("store.yml", false);
        File file = new File(plugin.getDataFolder(), "store.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, StoreProduct> products = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("products");
        if (section == null) {
            return products;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            StoreProduct product = loadOne(id, entry);
            if (product != null) {
                products.put(id, product);
            }
        }
        return products;
    }

    private StoreProduct loadOne(String id, ConfigurationSection section) {
        String materialName = section.getString("icon", "PAPER");
        Material icon = Material.matchMaterial(materialName);
        if (icon == null) {
            logger.warning("Store product '" + id + "' has an invalid icon '" + materialName + "' - falling back to PAPER.");
            icon = Material.PAPER;
        }
        long cost = Math.max(0, section.getLong("cost-credits", 0));
        List<String> description = new ArrayList<>(section.getStringList("description"));
        List<String> commands = new ArrayList<>(section.getStringList("commands"));
        String categoryRaw = section.getString("category", "GAMEPASS").toUpperCase(java.util.Locale.ROOT);
        StoreProductCategory category;
        try {
            category = StoreProductCategory.valueOf(categoryRaw);
        } catch (IllegalArgumentException e) {
            logger.warning("Store product '" + id + "' has an unknown category '" + categoryRaw + "' - falling back to GAMEPASS.");
            category = StoreProductCategory.GAMEPASS;
        }
        return new StoreProduct(id, section.getString("display-name", id), description, icon, BigInteger.valueOf(cost), commands, category);
    }
}
