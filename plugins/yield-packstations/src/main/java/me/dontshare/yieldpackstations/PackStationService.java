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
 * The hatch flow for a physical egg station - mirrors yield-upgrades' own
 * {@code UpgradeService}, except a station never levels anything up: it
 * takes the egg's own coin/diamond cost and hatches it on the spot, with no
 * shop-stock cap and no storage in between.
 * <p>
 * A plain smack hatches one; smacking while SNEAKING hatches as many as the
 * player's tier allows and can afford. That bulk path is not a convenience,
 * it is what keeps the loop playable: an egg costs roughly 1/150th of a
 * minute's income in its own zone, deliberately, so walking into a new zone
 * and rebuilding a squad takes a couple of minutes rather than the better
 * part of an hour (see BALANCE.md). At one egg per click - and now one egg
 * per cooldown, since paying and hatching are the same act - that would be
 * hundreds of clicks and several minutes of waiting.
 */
public final class PackStationService {

    public enum Result { SUCCESS, ZONE_LOCKED, CANT_AFFORD, NO_STOCK_CONFIGURED, BUSY }

    /** How many were actually hatched, so the caller can say so. Zero whenever {@code result} isn't SUCCESS. */
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

    public Purchase attemptHatch(Player player, PackStation station, boolean bulk) {
        if (!station.isBlackMarket() && !zoneUnlocked(player, station)) {
            return new Purchase(Result.ZONE_LOCKED, 0);
        }
        String packId = currentPackId(station);
        PackDefinition pack = resolvePack(station);
        if (packId == null || pack == null) {
            return new Purchase(Result.NO_STOCK_CONFIGURED, 0);
        }
        // Resolved up front rather than handed straight to the hatch, which
        // is all-or-nothing on cost - asking it for ten when the player can
        // afford seven would hatch nothing at all.
        int tier = packs.getPackOpenService().maxTierFor(player);
        int quantity = bulk ? packs.getPackRollService().affordableHatches(player, packId, tier) : 1;
        if (quantity <= 0) {
            return new Purchase(Result.CANT_AFFORD, 0);
        }
        PackRollService.PurchaseResult result = packs.getPackOpenService().tryHatch(player, packId, quantity);
        if (result.success()) {
            return new Purchase(Result.SUCCESS, result.rolls().size());
        }
        // A null reason is the deliberate silent refusal - cooldown, or a
        // hatch still playing out. Smacking is a hold-down action, so those
        // land several times a second and must never produce a message.
        return new Purchase(result.failureReason() == null ? Result.BUSY : Result.CANT_AFFORD, 0);
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
