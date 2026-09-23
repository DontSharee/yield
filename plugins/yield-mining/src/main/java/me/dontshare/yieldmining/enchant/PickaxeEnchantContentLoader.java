package me.dontshare.yieldmining.enchant;

import me.dontshare.yieldcore.config.BundledConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Loads pickaxe-enchants.yml - hand-authored and admin-edited directly (like mining.yml), never written back to by the plugin itself. */
public final class PickaxeEnchantContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public PickaxeEnchantContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, PickaxeEnchantDefinition> load() {
        BundledConfig.sync(plugin, "pickaxe-enchants.yml");
        File file = new File(plugin.getDataFolder(), "pickaxe-enchants.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, PickaxeEnchantDefinition> enchants = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("enchants");
        if (section == null) {
            return enchants;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection enchantSection = section.getConfigurationSection(id);
            if (enchantSection == null) {
                continue;
            }
            PickaxeEnchantDefinition definition = loadOne(id, enchantSection);
            if (definition != null) {
                enchants.put(id.toLowerCase(Locale.ROOT), definition);
            }
        }
        return enchants;
    }

    private PickaxeEnchantDefinition loadOne(String id, ConfigurationSection section) {
        String typeRaw = section.getString("type", "");
        MiningRewardType type;
        try {
            type = MiningRewardType.valueOf(typeRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Pickaxe enchant '" + id + "' has an invalid 'type' '" + typeRaw + "' - skipping.");
            return null;
        }

        String currencyRaw = section.getString("cost-currency", "DIAMONDS");
        CostCurrency currency;
        try {
            currency = CostCurrency.valueOf(currencyRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Pickaxe enchant '" + id + "' has an invalid 'cost-currency' '" + currencyRaw + "' - defaulting to DIAMONDS.");
            currency = CostCurrency.DIAMONDS;
        }

        String itemName = section.getString("display-item", "STONE_PICKAXE");
        Material displayItem = Material.matchMaterial(itemName);
        if (displayItem == null) {
            logger.warning("Pickaxe enchant '" + id + "' has an invalid 'display-item' '" + itemName + "' - falling back to STONE_PICKAXE.");
            displayItem = Material.STONE_PICKAXE;
        }

        List<String> colors = section.getStringList("colors");
        String primary = colors.size() > 0 ? colors.get(0) : "<white>";
        String secondary = colors.size() > 1 ? colors.get(1) : primary;

        String description = section.getString("description", "");
        List<String> descriptionLines = Arrays.asList(description.split("\\|"));

        String costFormula = section.getString("cost-formula", "");
        String boostFormula = section.getString("boost-formula", "");
        String chanceFormula = section.getString("chance-formula", "0");
        if (costFormula.isBlank() || boostFormula.isBlank()) {
            logger.warning("Pickaxe enchant '" + id + "' is missing 'cost-formula' or 'boost-formula' - skipping.");
            return null;
        }

        return new PickaxeEnchantDefinition(
                id.toLowerCase(Locale.ROOT),
                section.getInt("id", 0),
                primary,
                secondary,
                section.getString("display-name", id),
                displayItem,
                descriptionLines,
                type,
                Math.max(1, section.getInt("max-level", 1)),
                Math.max(0, section.getInt("rebirth-requirement", 0)),
                currency,
                costFormula,
                boostFormula,
                chanceFormula,
                section.getBoolean("mastery", false)
        );
    }
}
