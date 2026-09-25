package me.dontshare.yieldupgrades;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldupgrades.data.UpgradeProfile;
import me.dontshare.yieldupgrades.data.UpgradeContentLoader.UpgradeContent;
import me.dontshare.yieldupgrades.data.UpgradeEffect;
import me.dontshare.yieldupgrades.data.UpgradeStation;
import me.dontshare.yieldupgrades.data.UpgradeType;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.zone.ZoneLockService;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
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
    /** This plugin's own levels. The pack store above stays for the coin balance a purchase spends. */
    private final PlayerDataStore<UpgradeProfile> upgradeStore;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final ZoneLockService zoneLockService;

    public UpgradeService(Supplier<UpgradeContent> content, PlayerDataStore<PackPlayerProfile> store,
                           PlayerDataStore<UpgradeProfile> upgradeStore,
                           Supplier<Map<String, ZoneDefinition>> zones, ZoneLockService zoneLockService) {
        this.content = content;
        this.store = store;
        this.upgradeStore = upgradeStore;
        this.zones = zones;
        this.zoneLockService = zoneLockService;
    }

    public int levelOf(UUID playerId, String typeId) {
        return upgradeStore.getOrCreate(playerId).getLevels().getOrDefault(typeId, 0);
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
        int level = levelOf(player.getUniqueId(), station.type().id());
        int effectiveCap = Math.min(station.cap(), station.type().maxLevel());
        if (level >= effectiveCap) {
            return Result.ALREADY_AT_CAP;
        }
        BigInteger cost = BigInteger.valueOf(costFor(station.type(), level));
        // Paid in diamonds, not coins - see upgrades.yml.
        if (profile.getDiamonds().compareTo(cost) < 0) {
            return Result.CANT_AFFORD;
        }
        profile.setDiamonds(profile.getDiamonds().subtract(cost));
        upgradeStore.getOrCreate(player.getUniqueId()).getLevels().put(station.type().id(), level + 1);
        store.save(player.getUniqueId());
        upgradeStore.save(player.getUniqueId());
        return Result.SUCCESS;
    }

    /** Read-only version of {@link #attemptUpgrade}'s three checks (zone/cap/balance) - purely for the button's own live green/red color, never mutates anything. */
    public boolean canAfford(Player player, UpgradeStation station) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        ZoneDefinition zone = zones.get().get(station.zoneId());
        if (zone == null || !zoneLockService.isUnlocked(player, zone)) {
            return false;
        }
        int level = levelOf(player.getUniqueId(), station.type().id());
        int effectiveCap = Math.min(station.cap(), station.type().maxLevel());
        if (level >= effectiveCap) {
            return false;
        }
        BigInteger cost = BigInteger.valueOf(costFor(station.type(), level));
        return profile.getDiamonds().compareTo(cost) >= 0;
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
                total += levelOf(profile.getPlayerId(), type.id()) * type.chancePerLevel();
            }
        }
        return total;
    }

    public long flatDiamondBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.DIAMOND_BOOST) {
                total += levelOf(profile.getPlayerId(), type.id()) * type.flatPerLevel();
            }
        }
        return Math.round(total);
    }

    /** Added directly to the vanilla base walk speed (0.2) - see YieldUpgrades' join listener. */
    public double speedBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.PLAYER_SPEED) {
                total += levelOf(profile.getPlayerId(), type.id()) * type.valuePerLevel();
            }
        }
        return total;
    }

    /** Extra concurrent-cube slots on top of a zone's own configured base - see OreCubeService#topUpCubes. Whole slots, so this rounds rather than truncating a fractional value-per-level. */
    public int cubeCapBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.CUBE_CAP_BONUS) {
                total += levelOf(profile.getPlayerId(), type.id()) * type.valuePerLevel();
            }
        }
        return (int) Math.round(total);
    }

    /** Additive boost to every configured cube-bonus's own chance (golden/diamond) - see OreCubeService#rollBonus. */
    public double cubeBonusChanceBonus(PackPlayerProfile profile) {
        double total = 0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == UpgradeEffect.CUBE_BONUS_CHANCE) {
                total += levelOf(profile.getPlayerId(), type.id()) * type.valuePerLevel();
            }
        }
        return total;
    }

    /** Speeds up Auto Mode's own target-switch cooldown (see PetCombatController) - {@code 1.0 + Σ(level * valuePerLevel)}, same shape as every other multiplier here. */
    public double autoSwitchSpeedMultiplier(PackPlayerProfile profile) {
        return multiplierFor(profile, UpgradeEffect.AUTO_SWITCH_SPEED);
    }

    public double tapMultiplier(PackPlayerProfile profile) {
        return multiplierFor(profile, UpgradeEffect.TAP_DAMAGE);
    }

    public double critChanceBonus(PackPlayerProfile profile) {
        return sumFor(profile, UpgradeEffect.CRIT_CHANCE);
    }

    public double diamondMultiplierBonus(PackPlayerProfile profile) {
        return multiplierFor(profile, UpgradeEffect.DIAMOND_MULTIPLIER);
    }

    public double attackSpeedMultiplier(PackPlayerProfile profile) {
        return multiplierFor(profile, UpgradeEffect.ATTACK_SPEED);
    }

    public double hatchLuckBonus(PackPlayerProfile profile) {
        return sumFor(profile, UpgradeEffect.HATCH_LUCK);
    }

    /** A factor on the respawn delay: 30 levels at 1% is a delay divided by 1.3. */
    public double respawnDelayFactor(PackPlayerProfile profile) {
        return 1.0 / multiplierFor(profile, UpgradeEffect.RESPAWN_SPEED);
    }

    public double doubleHitBonus(PackPlayerProfile profile) {
        return sumFor(profile, UpgradeEffect.DOUBLE_HIT);
    }

    public double magnetRangeBonus(PackPlayerProfile profile) {
        return sumFor(profile, UpgradeEffect.MAGNET_RANGE);
    }

    public int petSlotBonus(PackPlayerProfile profile) {
        return (int) Math.round(sumFor(profile, UpgradeEffect.PET_SLOTS));
    }

    public double tripleHitBonus(PackPlayerProfile profile) {
        return sumFor(profile, UpgradeEffect.TRIPLE_HIT);
    }

    /** A factor on the hatch cooldown, same shape as {@link #respawnDelayFactor}. */
    public double hatchCooldownFactor(PackPlayerProfile profile) {
        return 1.0 / multiplierFor(profile, UpgradeEffect.HATCH_SPEED);
    }

    /** Spawn-weight factor for one cube tier: only giants and treasure chests are boosted. */
    public double rareFindWeight(PackPlayerProfile profile, me.dontshare.yieldzones.data.CubeTier tier) {
        return tier.giant() || tier.treasure() ? multiplierFor(profile, UpgradeEffect.RARE_FINDS) : 1.0;
    }

    /** {@code Σ(level * valuePerLevel)} across every type with this effect - for the additive chance/luck slots. */
    private double sumFor(PackPlayerProfile profile, UpgradeEffect effect) {
        return multiplierFor(profile, effect) - 1.0;
    }

    /** {@code 1.0 + Σ(level * valuePerLevel)} across every type configured with this effect - matches RebirthService.coinMultiplier's own existing formula shape. */
    private double multiplierFor(PackPlayerProfile profile, UpgradeEffect effect) {
        double total = 1.0;
        for (UpgradeType type : content.get().types().values()) {
            if (type.effect() == effect) {
                total += levelOf(profile.getPlayerId(), type.id()) * type.valuePerLevel();
            }
        }
        return total;
    }
}
