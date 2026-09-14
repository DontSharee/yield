package me.dontshare.yieldspawnnpcs.crate;

import me.dontshare.yieldcore.packet.PacketEntityManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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

/** Loads crates.yml - same reward-pool shape as yield-lootboxes' own LootboxContentLoader (kept a fully separate config, see CrateRewardEntry's own javadoc), plus each crate's own Key drop chance and physical station location. */
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
        double keyDropChance = Math.max(0, section.getDouble("key-drop-chance", 0));

        Location location = parseLocation(id, section.get("location"));
        if (location == null) {
            logger.warning("Crate '" + id + "' has no valid 'location' - skipping crate entirely (no physical station means it can never be opened).");
            return null;
        }

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
        return new CrateDefinition(id, displayName, icon, keyDropChance, location,
                PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(),
                PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(), pool);
    }

    /** {@code [world, x, y, z, yaw, pitch]} - yaw/pitch optional (default 0), same shape every other station's own location already uses (see yield-packs' PetEnchantContentLoader). */
    private Location parseLocation(String crateId, Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 4) {
            logger.warning("Crate '" + crateId + "' has a missing/invalid 'location'.");
            return null;
        }
        if (!(list.get(0) instanceof String worldName)) {
            logger.warning("Crate '" + crateId + "' location must start with a world name.");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            logger.warning("Crate '" + crateId + "' references unknown world '" + worldName + "'.");
            return null;
        }
        double x = toDouble(list.get(1));
        double y = toDouble(list.get(2));
        double z = toDouble(list.get(3));
        float yaw = list.size() > 4 ? (float) toDouble(list.get(4)) : 0f;
        float pitch = list.size() > 5 ? (float) toDouble(list.get(5)) : 0f;
        return new Location(world, x, y, z, yaw, pitch);
    }

    private double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
