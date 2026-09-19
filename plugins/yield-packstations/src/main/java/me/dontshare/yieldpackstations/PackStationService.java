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
 * just buys its pack into storage (unlimited supply, no shop-stock cap -
 * see {@code PackRollService#buyStationPack}).
 * <p>
 * A plain smack buys one; smacking while SNEAKING buys up to {@link
 * #BULK_PURCHASE_AMOUNT} at once. That exists because of how the packs are
 * priced: a zone pack costs roughly 1/150th of a minute's income in its own
 * zone, deliberately, so that walking into a new zone and rebuilding a squad
 * from its pack station takes a couple of minutes rather than the better
 * part of an hour (see BALANCE.md). At one pack per click that would be
 * hundreds of clicks, which is not a loop anyone would actually play.
 */
public final class PackStationService {

    public enum Result { SUCCESS, ZONE_LOCKED, CANT_AFFORD, NO_STOCK_CONFIGURED }

    /** How many a sneak-smack buys, at most - fewer if that's all the player can afford. */
    public static final int BULK_PURCHASE_AMOUNT = 10;

    /** How many were actually bought, so the caller can say so. Zero whenever {@code result} isn't SUCCESS. */
    public record Purchase(Result result, int quantity) {
    }

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
                && profile.getDiamonds().compareTo(BigInteger.valueOf(pack.diamondCost())) >= 0;
    }

    public Purchase attemptPurchase(Player player, PackStation station, boolean bulk) {
        if (!station.isBlackMarket() && !zoneUnlocked(player, station)) {
            return new Purchase(Result.ZONE_LOCKED, 0);
        }
        String packId = currentPackId(station);
        PackDefinition pack = resolvePack(station);
        if (packId == null || pack == null) {
            return new Purchase(Result.NO_STOCK_CONFIGURED, 0);
        }
        // Resolved up front rather than handed straight to buyStationPack,
        // which is all-or-nothing on cost - asking it for 10 when the player
        // can only afford 7 would buy nothing at all.
        int quantity = bulk ? affordableCount(player, pack) : 1;
        if (quantity <= 0) {
            return new Purchase(Result.CANT_AFFORD, 0);
        }
        PackRollService.PurchaseResult result = packs.getPackRollService().buyStationPack(player, packId, quantity);
        return result.success() ? new Purchase(Result.SUCCESS, quantity) : new Purchase(Result.CANT_AFFORD, 0);
    }

    /** How many of {@code pack} this player could buy right now, capped at {@link #BULK_PURCHASE_AMOUNT}. */
    private int affordableCount(Player player, PackDefinition pack) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        int affordable = BULK_PURCHASE_AMOUNT;
        if (pack.coinCost() > 0) {
            affordable = (int) Math.min(affordable,
                    profile.getCoins().divide(BigInteger.valueOf(pack.coinCost())).min(BigInteger.valueOf(affordable)).longValue());
        }
        if (pack.diamondCost() > 0) {
            affordable = (int) Math.min(affordable,
                    profile.getDiamonds().divide(BigInteger.valueOf(pack.diamondCost())).min(BigInteger.valueOf(affordable)).longValue());
        }
        return affordable;
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
