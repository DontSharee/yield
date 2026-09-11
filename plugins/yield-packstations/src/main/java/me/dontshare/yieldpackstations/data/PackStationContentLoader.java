package me.dontshare.yieldpackstations.data;

import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldpacks.data.PackRegistry;
import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Loads pack-stations.yml's "stations:"/"black-market:" tree - mirrors yield-upgrades' UpgradeContentLoader's style, including its warn-and-skip (never crash) handling of a bad reference. */
public final class PackStationContentLoader {

    public record PackStationContent(List<PackStation> zoneStations, List<PackStation> blackMarketStations,
                                      BlackMarketConfig blackMarketConfig) {
    }

    private static final long DEFAULT_RESET_INTERVAL_MILLIS = 60 * 60 * 1000L;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final Supplier<PackRegistry> packs;

    public PackStationContentLoader(JavaPlugin plugin, Logger logger, Supplier<Map<String, ZoneDefinition>> zones,
                                     Supplier<PackRegistry> packs) {
        this.plugin = plugin;
        this.logger = logger;
        this.zones = zones;
        this.packs = packs;
    }

    public PackStationContent load() {
        plugin.saveResource("pack-stations.yml", false);
        File file = new File(plugin.getDataFolder(), "pack-stations.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        List<PackStation> zoneStations = loadZoneStations(config);
        BlackMarketConfig blackMarketConfig = loadBlackMarketConfig(config);
        List<PackStation> blackMarketStations = loadBlackMarketStations(config);
        return new PackStationContent(zoneStations, blackMarketStations, blackMarketConfig);
    }

    private List<PackStation> loadZoneStations(YamlConfiguration config) {
        List<PackStation> stations = new ArrayList<>();
        Map<String, ZoneDefinition> liveZones = zones.get();
        PackRegistry livePacks = packs.get();
        for (Map<?, ?> entry : config.getMapList("stations")) {
            Object zoneIdRaw = entry.get("zone");
            String zoneId = zoneIdRaw instanceof String s ? s : null;
            if (zoneId == null || !liveZones.containsKey(zoneId)) {
                logger.warning("Pack station references unknown zone '" + zoneIdRaw + "' - skipping.");
                continue;
            }
            Object packIdRaw = entry.get("pack");
            String packId = packIdRaw instanceof String s ? s : null;
            if (packId == null || livePacks.find(packId).isEmpty()) {
                logger.warning("Pack station in zone '" + zoneId + "' references unknown pack '" + packIdRaw + "' - skipping.");
                continue;
            }
            Location location = parseLocation("zone '" + zoneId + "'", entry.get("location"));
            if (location == null) {
                continue;
            }
            stations.add(new PackStation(zoneId, packId, false, location,
                    PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(),
                    PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId()));
        }
        return stations;
    }

    private BlackMarketConfig loadBlackMarketConfig(YamlConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("black-market");
        if (section == null) {
            return new BlackMarketConfig(DEFAULT_RESET_INTERVAL_MILLIS, List.of());
        }
        long resetIntervalMillis = Math.max(60_000L,
                Math.round(section.getDouble("reset-interval-minutes", 60.0) * 60_000L));
        PackRegistry livePacks = packs.get();
        List<String> pool = new ArrayList<>();
        for (String packId : section.getStringList("pool")) {
            if (livePacks.find(packId).isEmpty()) {
                logger.warning("Black market pool references unknown pack '" + packId + "' - skipping.");
                continue;
            }
            pool.add(packId);
        }
        return new BlackMarketConfig(resetIntervalMillis, pool);
    }

    /**
     * One black-market kiosk per zone (all showing the exact same live pack -
     * {@link PackStationService#currentPackId} re-resolves from {@link
     * me.dontshare.yieldpackstations.market.BlackMarketRotationService} the
     * same way for every one of them) rather than a single far-off location,
     * so players don't have to travel back to one spot to check it. Falls
     * back to the legacy singular "station.location" key if "stations" isn't
     * present, so a hand-edited single-kiosk config still loads.
     */
    private List<PackStation> loadBlackMarketStations(YamlConfiguration config) {
        List<PackStation> stations = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("black-market");
        if (section == null) {
            return stations;
        }
        List<Map<?, ?>> entries = section.getMapList("stations");
        if (!entries.isEmpty()) {
            for (Map<?, ?> entry : entries) {
                Location location = parseLocation("the black market", entry.get("location"));
                if (location == null) {
                    continue;
                }
                stations.add(new PackStation(null, null, true, location,
                        PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(),
                        PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId()));
            }
            return stations;
        }
        Location legacyLocation = parseLocation("the black market", section.get("station.location"));
        if (legacyLocation != null) {
            stations.add(new PackStation(null, null, true, legacyLocation,
                    PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(),
                    PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId()));
        }
        return stations;
    }

    /** {@code [world, x, y, z, yaw, pitch]} - yaw/pitch optional (default 0), same self-contained shape UpgradeContentLoader's own station locations use. */
    private Location parseLocation(String context, Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 4) {
            logger.warning("Pack station (" + context + ") has an invalid 'location' - skipping.");
            return null;
        }
        if (!(list.get(0) instanceof String worldName)) {
            logger.warning("Pack station (" + context + ") 'location' must start with a world name - skipping.");
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            logger.warning("Pack station (" + context + ") references unknown world '" + worldName + "' - skipping.");
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
