package me.dontshare.yieldpackstations;

import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackRollService;
import me.dontshare.yieldpackstations.data.PackStation;
import me.dontshare.yieldpackstations.market.BlackMarketRotationService;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.zone.ZoneLockService;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The purchase flow for a physical pack station - mirrors yield-upgrades'
 * own {@code UpgradeService}, except a station never levels anything up, it
 * just buys one unit of its pack into storage (unlimited supply, no shop-
 * stock cap - see {@code PackRollService#buyStationPack}).
 */
public final class PackStationService {

    public enum Result { SUCCESS, ZONE_LOCKED, CANT_AFFORD, NO_STOCK_CONFIGURED }

    private final YieldPacks packs;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final ZoneLockService zoneLockService;
    private final BlackMarketRotationService blackMarket;

    public PackStationService(YieldPacks packs, Supplier<Map<String, ZoneDefinition>> zones,
                               ZoneLockService zoneLockService, BlackMarketRotationService blackMarket) {
        this.packs = packs;
        this.zones = zones;
        this.zoneLockService = zoneLockService;
        this.blackMarket = blackMarket;
    }

    /** The pack this station is currently selling - fixed for a zone station, live-rotated for the black market. */
    public String currentPackId(PackStation station) {
        return station.isBlackMarket() ? blackMarket.currentPackId() : station.fixedPackId();
    }

    public boolean canAfford(Player player, PackStation station) {
        if (!station.isBlackMarket() && !zoneUnlocked(player, station)) {
            return false;
        }
        PackDefinition pack = resolvePack(station);
        if (pack == null) {
            return false;
        }
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        return profile.getCoins().compareTo(BigInteger.valueOf(pack.coinCost())) >= 0
                && profile.getGems().compareTo(BigInteger.valueOf(pack.gemCost())) >= 0;
    }

    public Result attemptPurchase(Player player, PackStation station) {
        if (!station.isBlackMarket() && !zoneUnlocked(player, station)) {
            return Result.ZONE_LOCKED;
        }
        String packId = currentPackId(station);
        if (packId == null) {
            return Result.NO_STOCK_CONFIGURED;
        }
        PackRollService.PurchaseResult result = packs.getPackRollService().buyStationPack(player, packId, 1);
        return result.success() ? Result.SUCCESS : Result.CANT_AFFORD;
    }

    private boolean zoneUnlocked(Player player, PackStation station) {
        ZoneDefinition zone = zones.get().get(station.zoneId());
        return zone != null && zoneLockService.isUnlocked(player, zone);
    }

    private PackDefinition resolvePack(PackStation station) {
        String packId = currentPackId(station);
        return packId != null ? packs.getPackRegistry().find(packId).orElse(null) : null;
    }
}
