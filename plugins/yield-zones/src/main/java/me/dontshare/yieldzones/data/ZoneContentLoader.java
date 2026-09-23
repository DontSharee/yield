package me.dontshare.yieldzones.data;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Loads zones.yml into an in-memory registry - mirrors yield-packs' PackContentLoader's style. */
public final class ZoneContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public ZoneContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public Map<String, ZoneDefinition> load() {
        plugin.saveResource("zones.yml", false);
        File file = new File(plugin.getDataFolder(), "zones.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, ZoneDefinition> zones = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("zones");
        if (section == null) {
            return zones;
        }
        List<GiantCube> giants = loadGiants(config.getConfigurationSection("giant-cubes"));
        // Very rare on purpose - books used to fall out of 1 hatch in 20,
        // about 68 an hour, which made every one of them junk. See the
        // comment on this key in zones.yml.
        double bookChance = Math.max(0.0, Math.min(1.0, config.getDouble("enchant-book-chance", 0.0004)));
        for (String id : section.getKeys(false)) {
            ConfigurationSection zoneSection = section.getConfigurationSection(id);
            if (zoneSection == null) {
                continue;
            }
            ZoneDefinition zone = loadZone(id, zoneSection, giants, bookChance);
            if (zone != null) {
                zones.put(id, zone);
            }
        }
        return zones;
    }

    private ZoneDefinition loadZone(String id, ConfigurationSection section, List<GiantCube> giants, double bookChance) {
        String worldName = section.getString("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (world == null) {
                logger.warning("Zone '" + id + "' - no worlds loaded at all, skipping.");
                return null;
            }
            logger.warning("Zone '" + id + "' references unknown world '" + worldName
                    + "' - falling back to '" + world.getName() + "'.");
        }

        List<Integer> corner1 = section.getIntegerList("corner-one");
        List<Integer> corner2 = section.getIntegerList("corner-two");
        if (corner1.size() != 3 || corner2.size() != 3) {
            logger.warning("Zone '" + id + "' has an invalid corner-one/corner-two - skipping.");
            return null;
        }
        ZoneRegion region = ZoneRegion.of(world,
                corner1.get(0), corner1.get(1), corner1.get(2),
                corner2.get(0), corner2.get(1), corner2.get(2));

        List<CubeTier> tiers = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("cube-tiers")) {
            Object materialName = entry.get("material");
            Material material = materialName instanceof String s ? Material.matchMaterial(s) : null;
            if (material == null) {
                logger.warning("Zone '" + id + "' has a cube tier with an invalid material '" + materialName + "' - skipping entry.");
                continue;
            }
            long maxHp = entry.get("max-hp") instanceof Number n ? n.longValue() : 20;
            long coinValue = entry.get("coin-value") instanceof Number n ? n.longValue() : 1;
            // Defaults to 1 so a tier written before this field existed keeps
            // paying exactly the single diamond it always did.
            long diamondValue = entry.get("diamond-value") instanceof Number n ? n.longValue() : 1;
            long xpValue = entry.get("xp-value") instanceof Number n ? n.longValue() : 1;
            double weight = entry.get("weight") instanceof Number n ? n.doubleValue() : 1.0;
            tiers.add(new CubeTier(material, Math.max(1L, maxHp), Math.max(0, coinValue), Math.max(1, diamondValue),
                    Math.max(0, xpValue), Math.max(0.01, weight)));
        }
        CubeTier treasure = loadTreasure(section.getConfigurationSection("treasure"), id);
        if (treasure != null) {
            // Appended to the same weighted list the normal tiers live in, so
            // it rolls through rollTier with no special-casing at the spawn
            // site at all - its rarity is just its weight against theirs.
            tiers.add(treasure);
        }
        if (tiers.isEmpty()) {
            logger.warning("Zone '" + id + "' has no valid cube tiers - skipping zone.");
            return null;
        }
        tiers.addAll(deriveGiants(tiers, giants));
        tiers.replaceAll(tier -> tier.withBookChance(bookChance));

        List<CubeBonus> bonuses = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("cube-bonuses")) {
            Object bonusId = entry.get("id");
            if (!(bonusId instanceof String bonusIdStr)) {
                logger.warning("Zone '" + id + "' has a cube bonus with no 'id' - skipping entry.");
                continue;
            }
            NamedTextColor color = entry.get("color") instanceof String s
                    ? NamedTextColor.NAMES.value(s.toLowerCase(Locale.ROOT)) : null;
            if (color == null) {
                logger.warning("Zone '" + id + "' cube bonus '" + bonusIdStr + "' has an invalid 'color' (must be one of vanilla's 16 named colors) - skipping entry.");
                continue;
            }
            double chance = entry.get("chance") instanceof Number n ? n.doubleValue() : 0;
            double multiplier = entry.get("multiplier") instanceof Number n ? n.doubleValue() : 1.0;
            bonuses.add(new CubeBonus(bonusIdStr, Math.max(0, Math.min(1, chance)), Math.max(1.0, multiplier), color));
        }

        long respawnDelayMillis = Math.round(section.getDouble("respawn-delay-seconds", 3.0) * 1000);
        int maxConcurrentCubes = Math.max(1, section.getInt("max-concurrent-cubes", 3));

        ZoneUnlockCost unlockCost = loadUnlockCost(id, section);
        List<ZoneWall> walls = loadWalls(id, section, world);
        Location teleport = loadTeleport(section, world, region);

        return new ZoneDefinition(id, section.getString("display-name", id), region, tiers, bonuses,
                unlockCost, walls, teleport, respawnDelayMillis, maxConcurrentCubes);
    }

    /** Absent "unlock:" section = {@link ZoneUnlockCost#FREE} - the zone is open to everyone, no wall/purchase gate at all. */
    /**
     * One {@code giant-cubes:} entry - a recipe, not a tier. It becomes a
     * real {@link CubeTier} separately in every zone (see
     * {@link #deriveGiants}), priced off that zone's own toughest cube.
     *
     * @param material null for {@code ZONE} - a giant copy of each of the zone's own cubes
     */
    private record GiantCube(String id, String label, Material material, float size, double chance,
                             NamedTextColor glow, double hpMultiplier, double coinMultiplier,
                             double diamondMultiplier, double xpMultiplier, String landingTitle,
                             double bookChance, String bookMinRarity) {
    }

    private List<GiantCube> loadGiants(ConfigurationSection section) {
        List<GiantCube> giants = new ArrayList<>();
        if (section == null) {
            return giants;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            String rawMaterial = s.getString("material", "ZONE");
            Material material = null;
            if (!rawMaterial.equalsIgnoreCase("ZONE")) {
                material = Material.matchMaterial(rawMaterial);
                if (material == null || !material.isBlock()) {
                    logger.warning("Giant cube '" + id + "' has an invalid block material '" + rawMaterial + "' - skipping.");
                    continue;
                }
            }
            NamedTextColor glow = null;
            String rawGlow = s.getString("glow");
            if (rawGlow != null) {
                glow = NamedTextColor.NAMES.value(rawGlow.toLowerCase(Locale.ROOT));
                if (glow == null) {
                    logger.warning("Giant cube '" + id + "' has an invalid 'glow' '" + rawGlow
                            + "' (must be one of vanilla's 16 named colors) - it will not glow.");
                }
            }
            // Capped at 2 blocks. OreCubeService keeps one column clear on
            // every side of a giant, and a 2-block cube reaches exactly to
            // the edge of that column - any bigger and it would render into
            // its neighbours. Pets also ring a target at 1.3 blocks from
            // its centre, which a cube past 2 blocks would swallow.
            float size = (float) Math.max(1.0, Math.min(2.0, s.getDouble("size", 1.5)));
            double chance = Math.max(0.0, Math.min(0.5, s.getDouble("chance", 0.02)));
            giants.add(new GiantCube(id, s.getString("label"), material, size, chance, glow,
                    Math.max(1.0, s.getDouble("hp-multiplier", 1.0)),
                    Math.max(0.0, s.getDouble("coin-multiplier", 1.0)),
                    Math.max(0.0, s.getDouble("diamond-multiplier", 1.0)),
                    Math.max(0.0, s.getDouble("xp-multiplier", 1.0)),
                    s.getString("landing-title"),
                    s.contains("enchant-book-chance")
                            ? Math.max(0.0, Math.min(1.0, s.getDouble("enchant-book-chance")))
                            : CubeTier.UNSET_BOOK_CHANCE,
                    s.getString("enchant-book-min-rarity")));
        }
        return giants;
    }

    /**
     * Turns each giant recipe into real tiers for one zone. Two shapes:
     * <ul>
     *   <li><b>{@code material: ZONE}</b> - a giant copy of EVERY ordinary
     *       tier the zone has, each priced off its own original, weighted so
     *       that each of them comes giant {@code chance} of the time. A
     *       giant stone cube is a stone cube with more HP and more pay, not
     *       a different thing - that is the "variation" half.</li>
     *   <li><b>A fixed material</b> (the big safe) - one tier, priced off
     *       the zone's toughest ordinary cube, {@code chance} of all spawns.
     *       That is the jackpot half.</li>
     * </ul>
     * Priced off the zone's own cubes either way, so a big safe in the
     * Meadow and one in the Genesis Core are both "a lot more than the best
     * thing here" without 21 hand-written entries to keep in step with the
     * ladder - the same idea as Huge pets being derived from normal ones.
     * <p>
     * Treasure chests never come giant: they are already the event of their
     * zone, and a giant one would pay out its egg stack at giant odds.
     */
    private List<CubeTier> deriveGiants(List<CubeTier> tiers, List<GiantCube> giants) {
        if (giants.isEmpty()) {
            return List.of();
        }
        List<CubeTier> ordinary = tiers.stream().filter(tier -> !tier.treasure()).toList();
        if (ordinary.isEmpty()) {
            return List.of();
        }
        CubeTier toughest = ordinary.stream().max((a, b) -> Long.compare(a.maxHp(), b.maxHp())).orElseThrow();
        double existingWeight = tiers.stream().mapToDouble(CubeTier::weight).sum();
        double totalChance = giants.stream().mapToDouble(GiantCube::chance).sum();
        // Dividing by (1 - total) is what makes a 3% giant come out at 3% of
        // ALL spawns once every giant's own weight has been added in too.
        double scale = 1.0 / Math.max(0.01, 1.0 - totalChance);

        List<CubeTier> derived = new ArrayList<>();
        for (GiantCube giant : giants) {
            if (giant.chance() <= 0) {
                continue;
            }
            if (giant.material() == null) {
                for (CubeTier tier : ordinary) {
                    derived.add(giantOf(giant, tier, tier.material(), tier.weight() * giant.chance() * scale));
                }
            } else {
                derived.add(giantOf(giant, toughest, giant.material(), existingWeight * giant.chance() * scale));
            }
        }
        return derived;
    }

    private CubeTier giantOf(GiantCube giant, CubeTier base, Material material, double weight) {
        return new CubeTier(material,
                Math.max(1L, Math.round(base.maxHp() * giant.hpMultiplier())),
                Math.round(base.coinValue() * giant.coinMultiplier()),
                Math.max(1L, Math.round(base.diamondValue() * giant.diamondMultiplier())),
                Math.round(base.xpValue() * giant.xpMultiplier()),
                weight,
                false, null, 0,
                giant.size(), giant.label(), giant.glow(), giant.landingTitle(), giant.bookChance(),
                giant.bookMinRarity());
    }

    /** A zone's optional treasure chest - null when the zone has no {@code treasure:} section. */
    private CubeTier loadTreasure(ConfigurationSection section, String zoneId) {
        if (section == null) {
            return null;
        }
        Material material = Material.matchMaterial(section.getString("material", "CHEST"));
        if (material == null) {
            logger.warning("Zone '" + zoneId + "' has an invalid treasure material - defaulting to CHEST.");
            material = Material.CHEST;
        }
        return new CubeTier(material,
                Math.max(1L, section.getLong("max-hp", 100L)),
                Math.max(0L, section.getLong("coin-value", 0L)),
                Math.max(1L, section.getLong("diamond-value", 1L)),
                Math.max(0L, section.getLong("xp-value", 0L)),
                Math.max(0.001, section.getDouble("weight", 0.6)),
                true,
                section.getString("reward-pack"),
                Math.max(0, section.getInt("reward-pack-amount", 0)));
    }

    private ZoneUnlockCost loadUnlockCost(String id, ConfigurationSection section) {
        ConfigurationSection unlock = section.getConfigurationSection("unlock");
        if (unlock == null) {
            return ZoneUnlockCost.FREE;
        }
        BigInteger coins = parseBigInteger(unlock.get("coins"));
        BigInteger diamonds = parseBigInteger(unlock.get("diamonds"));

        List<ZoneUnlockCost.ItemCost> items = new ArrayList<>();
        for (Map<?, ?> entry : unlock.getMapList("items")) {
            Object materialName = entry.get("material");
            Material material = materialName instanceof String s ? Material.matchMaterial(s) : null;
            if (material == null) {
                logger.warning("Zone '" + id + "' has an unlock item cost with an invalid material '" + materialName + "' - skipping entry.");
                continue;
            }
            int amount = entry.get("amount") instanceof Number n ? n.intValue() : 1;
            items.add(new ZoneUnlockCost.ItemCost(material, Math.max(1, amount)));
        }
        return new ZoneUnlockCost(coins, diamonds, items);
    }

    /** Accepts either a plain number (small costs) or a numeric string (arbitrary precision, matching how currency balances themselves are stored) - never throws, falls back to 0. */
    private BigInteger parseBigInteger(Object value) {
        if (value instanceof Number n) {
            return BigInteger.valueOf(n.longValue());
        }
        if (value instanceof String s) {
            try {
                return new BigInteger(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to ZERO below
            }
        }
        return BigInteger.ZERO;
    }

    private List<ZoneWall> loadWalls(String id, ConfigurationSection section, World world) {
        List<ZoneWall> walls = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("walls")) {
            Object materialName = entry.get("material");
            Material material = materialName instanceof String s ? Material.matchMaterial(s) : Material.TINTED_GLASS;
            if (material == null) {
                material = Material.TINTED_GLASS;
            }
            List<Integer> c1 = toIntList(entry.get("corner-one"));
            List<Integer> c2 = toIntList(entry.get("corner-two"));
            if (c1.size() != 3 || c2.size() != 3) {
                logger.warning("Zone '" + id + "' has a wall with an invalid corner-one/corner-two - skipping entry.");
                continue;
            }
            ZoneRegion region = ZoneRegion.of(world, c1.get(0), c1.get(1), c1.get(2), c2.get(0), c2.get(1), c2.get(2));
            long volume = (long) (region.maxX() - region.minX() + 1) * (region.maxY() - region.minY() + 1) * (region.maxZ() - region.minZ() + 1);
            if (volume > 5000) {
                logger.warning("Zone '" + id + "' has a wall spanning " + volume + " blocks - every locked player within render "
                        + "range gets sent that many individual block-change packets at once (there's no bulk block-change API "
                        + "in use here). Consider a thinner wall (fewer blocks deep) if players notice a stutter.");
            }
            walls.add(new ZoneWall(region, material));
        }
        return walls;
    }

    private List<Integer> toIntList(Object value) {
        List<Integer> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    result.add(n.intValue());
                }
            }
        }
        return result;
    }

    /** Falls back to the region's own horizontal center, standing on the actual terrain there, if "teleport:" isn't configured - so fast travel/an unlock confirm always has somewhere sane to land. */
    private Location loadTeleport(ConfigurationSection section, World world, ZoneRegion region) {
        List<Double> coords = section.getDoubleList("teleport");
        if (coords.size() >= 3) {
            float yaw = coords.size() > 3 ? coords.get(3).floatValue() : 0f;
            float pitch = coords.size() > 4 ? coords.get(4).floatValue() : 0f;
            return new Location(world, coords.get(0), coords.get(1), coords.get(2), yaw, pitch);
        }
        int centerX = (region.minX() + region.maxX()) / 2;
        int centerZ = (region.minZ() + region.maxZ()) / 2;
        int groundY = world.getHighestBlockYAt(centerX, centerZ);
        return new Location(world, centerX + 0.5, groundY + 1, centerZ + 0.5);
    }
}
