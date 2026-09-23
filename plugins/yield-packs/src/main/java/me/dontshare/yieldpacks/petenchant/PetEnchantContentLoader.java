package me.dontshare.yieldpacks.petenchant;

import me.dontshare.yieldcore.config.BundledConfig;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Loads pet-enchants.yml - same warn-and-skip, {@code getConfigurationSection(...).getKeys(false)} shape {@code PackContentLoader} already established. */
public final class PetEnchantContentLoader {

    /** {@code commons}: one weighted I-V (or fewer) level ladder per {@link PetEnchantType}. {@code uniques}: every {@link PetUniqueDefinition}, in file order. {@code uniqueWeight}: each Unique's own flat roll weight (see the yml's own comment on the ~1%-each math). {@code diamondCost}: per attempt. {@code tables}: every clickable Enchanting Table location. */
    public record PetEnchantContent(Map<PetEnchantType, CommonLadder> commons, List<PetUniqueDefinition> uniques,
                                     double uniqueWeight, long diamondCost, List<PetEnchantTable> tables) {
    }

    /** Parallel level lists, index 0 = level I. */
    public record CommonLadder(List<Double> values, List<Double> weights) {
    }

    private static final double DEFAULT_UNIQUE_WEIGHT = 9.0;
    private static final long DEFAULT_DIAMOND_COST = 500L;

    private final JavaPlugin plugin;
    private final Logger logger;

    public PetEnchantContentLoader(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public PetEnchantContent load() {
        BundledConfig.sync(plugin, "pet-enchants.yml");
        File file = new File(plugin.getDataFolder(), "pet-enchants.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<PetEnchantType, CommonLadder> commons = loadCommons(config);
        List<PetUniqueDefinition> uniques = loadUniques(config);
        double uniqueWeight = config.getDouble("economy.unique-weight-each", DEFAULT_UNIQUE_WEIGHT);
        long diamondCost = Math.max(0, config.getLong("economy.diamond-cost", DEFAULT_DIAMOND_COST));
        List<PetEnchantTable> tables = loadTables(config);
        return new PetEnchantContent(commons, uniques, uniqueWeight, diamondCost, tables);
    }

    private Map<PetEnchantType, CommonLadder> loadCommons(YamlConfiguration config) {
        Map<PetEnchantType, CommonLadder> result = new EnumMap<>(PetEnchantType.class);
        ConfigurationSection section = config.getConfigurationSection("commons");
        if (section == null) {
            logger.warning("pet-enchants.yml has no 'commons' section - no Common enchants will ever roll.");
            return result;
        }
        for (String key : section.getKeys(false)) {
            PetEnchantType type;
            try {
                type = PetEnchantType.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.warning("pet-enchants.yml 'commons." + key + "' is not a known PetEnchantType - skipping.");
                continue;
            }
            List<Double> values = section.getDoubleList(key + ".values");
            List<Double> weights = section.getDoubleList(key + ".weights");
            if (values.isEmpty() || values.size() != weights.size()) {
                logger.warning("pet-enchants.yml 'commons." + key + "' has mismatched/empty values-weights - skipping.");
                continue;
            }
            result.put(type, new CommonLadder(values, weights));
        }
        return result;
    }

    private List<PetUniqueDefinition> loadUniques(YamlConfiguration config) {
        List<PetUniqueDefinition> result = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("uniques");
        if (section == null) {
            return result;
        }
        for (String id : section.getKeys(false)) {
            String displayName = section.getString(id + ".display-name", id);
            String colorHex = section.getString(id + ".color", "#FFFFFF");
            String specialEffect = section.getString(id + ".special-effect", null);
            Map<PetEnchantType, Double> statBonuses = new EnumMap<>(PetEnchantType.class);
            ConfigurationSection statsSection = section.getConfigurationSection(id + ".stat-bonuses");
            if (statsSection != null) {
                for (String statKey : statsSection.getKeys(false)) {
                    try {
                        statBonuses.put(PetEnchantType.valueOf(statKey.toUpperCase(Locale.ROOT)), statsSection.getDouble(statKey));
                    } catch (IllegalArgumentException e) {
                        logger.warning("pet-enchants.yml 'uniques." + id + ".stat-bonuses." + statKey + "' is not a known PetEnchantType - skipping that entry.");
                    }
                }
            }
            result.add(new PetUniqueDefinition(id, displayName, colorHex, statBonuses, specialEffect));
        }
        return result;
    }

    private List<PetEnchantTable> loadTables(YamlConfiguration config) {
        List<PetEnchantTable> result = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("tables")) {
            Location location = parseLocation(entry.get("location"));
            if (location != null) {
                result.add(new PetEnchantTable(location, PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(),
                        PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId()));
            }
        }
        return result;
    }

    /** {@code [world, x, y, z, yaw, pitch]} - yaw/pitch optional (default 0), same shape every other station's own location already uses (see yield-zonemachines' ZoneMachineContentLoader). */
    private Location parseLocation(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 4) {
            logger.warning("pet-enchants.yml has a 'tables' entry with an invalid 'location' - skipping.");
            return null;
        }
        if (!(list.get(0) instanceof String worldName)) {
            logger.warning("pet-enchants.yml 'tables' entry's 'location' must start with a world name - skipping.");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            logger.warning("pet-enchants.yml 'tables' entry references unknown world '" + worldName + "' - skipping.");
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
