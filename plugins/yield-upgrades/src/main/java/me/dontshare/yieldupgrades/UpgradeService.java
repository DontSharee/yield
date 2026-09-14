package me.dontshare.yieldupgrades;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldupgrades.data.UpgradeContentLoader.UpgradeContent;
import me.dontshare.yieldupgrades.data.UpgradeEffect;
import me.dontshare.yieldupgrades.data.UpgradeStation;
import me.dontshare.yieldupgrades.data.UpgradeType;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.zone.ZoneLockService;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Level/cost math and the actual purchase flow for a physical upgrade
 * station, plus the per-effect query methods every other plugin's own
 * composable provider registry calls into (see {@code YieldUpgrades#onEnable}).
 * A player's level in an upgrade type is global (see
 * {@code PackPlayerProfile#getUpgradeLevels}) - a station's own {@code cap}
 * only limits how far THAT station lets them push it, checked fresh on
 * every attempt rather than baked into anything stored.
 */
public final class UpgradeService {

    /** The display layer turns each of these into a distinct sound/message rather than one generic "can't do that." */
    public enum Result {
        SUCCESS, ALREADY_AT_CAP, ZONE_LOCKED, CANT_AFFORD
    }

    private final Supplier<UpgradeContent> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final ZoneLockService zoneLockService;

    public UpgradeService(Supplier<UpgradeContent> content, PlayerDataStore<PackPlayerProfile> store,
                           Supplier<Map<String, ZoneDefinition>> zones, ZoneLockService zoneLockService) {
        this.content = content;
        this.store = store;
        this.zones = zones;
        this.zoneLockService = zoneLockService;
    }

    public int levelOf(PackPlayerProfile profile, String typeId) {
        return profile.getUpgradeLevels().getOrDefault(typeId, 0);
    }

    /** {@code costBase * costGrowth^level} - an exponential per-level curve, deliberately different from the power-curve player/pet leveling uses for its rarer, bigger milestone jumps. */
    public long costFor(UpgradeType type, int level) {
        return Math.round(type.costBase() * Math.pow(type.costGrowth(), level));
    }

    public Result attemptUpgrade(Player player, UpgradeStation station) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        ZoneDefinition zone = zones.get().get(station.zoneId());
        if (zone == null || !zoneLockService.isUnlocked(player, zone)) {
            return Result.ZONE_LOCKED;
        }
        int level = levelOf(profile, station.type().id());
        int effectiveCap = Math.min(station.cap(), station.type().maxLevel());
        if (level >= effectiveCap) {
            return Result.ALREADY_AT_CAP;
        }
        BigInteger cost = BigInteger.valueOf(costFor(station.type(), level));
        if (profile.getCoins().compareTo(cost) < 0) {
            return Result.CANT_AFFORD;
        }
        profile.setCoins(profile.getCoins().subtract(cost));
        profile.getUpgradeLevels().put(station.type().id(), level + 1);
        store.save(player.getUniqueId());
        return Result.SUCCESS;
    }

    /** Read-only version of {@link #attemptUpgrade}'s three checks (zone/cap/balance) - purely for the button's own live green/red color, never mutates anything. */
    public boolean canAfford(Player player, UpgradeStation station) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        ZoneDefinition zone = zones.get().get(station.zoneId());
        if (zone == null || !zoneLockService.isUnlocked(player, zone)) {
            return false;
        }
        int level = levelOf(profile, station.type().id());
        int effectiveCap = Math.min(station.cap(), station.type().maxLevel());
        if (level >= effectiveCap) {
            return false;
        }
        BigInteger cost = BigInteger.valueOf(costFor(station.type(), level));
        return profile.getCoins().compareTo(cost) >= 0;
    }

    // --- Effect queries, each consumed by another plugin's own composable registry (see YieldUpgrades#onEnable) ---

    public double coinMultiplierBonus(PackPlayerProfile profile) {
        return multiplierFor(profile, UpgradeEffect.COIN_MULTIPLIER);
    }

    public double damageMultiplierBonus(PackPlayerProfile profile) {
        return multiplierFor(profile, UpgradeEffect.DAMAGE_MULTIPLIER);
    }

    public double rebirthGrantMultiplier(PackPlayerProfile profile) {
        return multiplierFor(profile, UpgradeEffect.REBIRTH_GRANT_MULTIPLIER);
    }

    /** Additive chance boost - matches OreCubeService's own diamondChanceBoostSum composition (sum, not product). Sums across every DIAMOND_BOOST type configured, in case more than one exists. */
    public double diamondChanceBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.DIAMOND_BOOST) {
                total += levelOf(profile, type.id()) * type.chancePerLevel();
            }
        }
        return total;
    }

    public long flatDiamondBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.DIAMOND_BOOST) {
                total += levelOf(profile, type.id()) * type.flatPerLevel();
            }
        }
        return Math.round(total);
    }

    /** Added directly to the vanilla base walk speed (0.2) - see YieldUpgrades' join listener. */
    public double speedBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.PLAYER_SPEED) {
                total += levelOf(profile, type.id()) * type.valuePerLevel();
            }
        }
        return total;
    }

    /** Extra concurrent-cube slots on top of a zone's own configured base - see OreCubeService#topUpCubes. Whole slots, so this rounds rather than truncating a fractional value-per-level. */
    public int cubeCapBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.CUBE_CAP_BONUS) {
                total += levelOf(profile, type.id()) * type.valuePerLevel();
            }
        }
        return (int) Math.round(total);
    }

    /** Additive boost to every configured cube-bonus's own chance (golden/diamond) - see OreCubeService#rollBonus. */
    public double cubeBonusChanceBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.CUBE_BONUS_CHANCE) {
                total += levelOf(profile, type.id()) * type.valuePerLevel();
            }
        }
        return total;
    }

    /** Speeds up Auto Mode's own target-switch cooldown (see PetCombatController) - {@code 1.0 + Σ(level * valuePerLevel)}, same shape as every other multiplier here. */
    public double autoSwitchSpeedMultiplier(PackPlayerProfile profile) {
        return multiplierFor(profile, UpgradeEffect.AUTO_SWITCH_SPEED);
    }

    /** {@code 1.0 + Σ(level * valuePerLevel)} across every type configured with this effect - matches RebirthService.coinMultiplier's own existing formula shape. */
    private double multiplierFor(PackPlayerProfile profile, UpgradeEffect effect) {
        double total = 1.0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == effect) {
                total += levelOf(profile, type.id()) * type.valuePerLevel();
            }
        }
        return total;
    }
}
