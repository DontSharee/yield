package me.dontshare.yieldlootboxes.data;

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

/** Loads lootboxes.yml - one box per top-level entry, each with its own weighted reward pool. */
public final class LootboxContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public LootboxContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, LootboxDefinition> load() {
        plugin.saveResource("lootboxes.yml", false);
        File file = new File(plugin.getDataFolder(), "lootboxes.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, LootboxDefinition> boxes = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("boxes");
        if (section == null) {
            return boxes;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            LootboxDefinition box = loadOne(id, entry);
            if (box != null) {
                boxes.put(id, box);
            }
        }
        return boxes;
    }

    private LootboxDefinition loadOne(String id, ConfigurationSection section) {
        Material icon = Material.matchMaterial(section.getString("icon", "CHEST"));
        if (icon == null) {
            logger.warning("Lootbox '" + id + "' has an invalid icon - defaulting to CHEST.");
            icon = Material.CHEST;
        }
        String displayName = section.getString("display-name", id);

        List<LootboxRewardEntry> pool = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("pool")) {
            Object typeValue = raw.get("type");
            Object weightValue = raw.get("weight");
            if (typeValue == null || !(weightValue instanceof Number weightNumber)) {
                logger.warning("Lootbox '" + id + "' has a malformed pool entry - skipping it.");
                continue;
            }
            LootboxRewardType type;
            try {
                type = LootboxRewardType.parse(String.valueOf(typeValue));
            } catch (IllegalArgumentException e) {
                logger.warning("Lootbox '" + id + "' has an unknown reward type '" + typeValue + "' - skipping it.");
                continue;
            }
            long amount = raw.get("amount") instanceof Number n ? n.longValue() : 0;
            String petItemId = raw.get("pet-item") instanceof String s ? s : null;
            @SuppressWarnings("unchecked")
            List<String> commands = raw.get("commands") instanceof List<?> list
                    ? (List<String>) list.stream().map(String::valueOf).toList()
                    : List.of();
            pool.add(new LootboxRewardEntry(type, weightNumber.doubleValue(), amount, petItemId, commands));
        }
        if (pool.isEmpty()) {
            logger.warning("Lootbox '" + id + "' has no valid pool entries - skipping box.");
            return null;
        }
        return new LootboxDefinition(id, displayName, icon, pool);
    }
}
