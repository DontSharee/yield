package me.dontshare.yieldspawnnpcs.crate;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Loads crates.yml - same shape as yield-lootboxes' own LootboxContentLoader, kept a fully separate config (see CrateRewardEntry's own javadoc on why). */
public final class CrateContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public CrateContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, CrateDefinition> load() {
        plugin.saveResource("crates.yml", false);
        File file = new File(plugin.getDataFolder(), "crates.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, CrateDefinition> crates = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("crates");
        if (section == null) {
            return crates;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            CrateDefinition crate = loadOne(id, entry);
            if (crate != null) {
                crates.put(id, crate);
            }
        }
        return crates;
    }

    private CrateDefinition loadOne(String id, ConfigurationSection section) {
        Material icon = Material.matchMaterial(section.getString("icon", "CHEST"));
        if (icon == null) {
            logger.warning("Crate '" + id + "' has an invalid icon - defaulting to CHEST.");
            icon = Material.CHEST;
        }
        String displayName = section.getString("display-name", id);
        long cost = Math.max(0, section.getLong("cost", 0));

        List<CrateRewardEntry> pool = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("pool")) {
            Object typeValue = raw.get("type");
            Object weightValue = raw.get("weight");
            if (typeValue == null || !(weightValue instanceof Number weightNumber)) {
                logger.warning("Crate '" + id + "' has a malformed pool entry - skipping it.");
                continue;
            }
            CrateRewardType type;
            try {
                type = CrateRewardType.valueOf(String.valueOf(typeValue).toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                logger.warning("Crate '" + id + "' has an unknown reward type '" + typeValue + "' - skipping it.");
                continue;
            }
            long amount = raw.get("amount") instanceof Number n ? n.longValue() : 0;
            String petItemId = raw.get("pet-item") instanceof String s ? s : null;
            @SuppressWarnings("unchecked")
            List<String> commands = raw.get("commands") instanceof List<?> list
                    ? (List<String>) list.stream().map(String::valueOf).toList()
                    : List.of();
            pool.add(new CrateRewardEntry(type, weightNumber.doubleValue(), amount, petItemId, commands));
        }
        if (pool.isEmpty()) {
            logger.warning("Crate '" + id + "' has no valid pool entries - skipping crate.");
            return null;
        }
        return new CrateDefinition(id, displayName, icon, cost, pool);
    }
}
