package me.dontshare.yieldpackstations;

import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackRollService;
import me.dontshare.yieldpackstations.data.PackStation;
import me.dontshare.yieldpacks.gui.PackOddsLore;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.zone.ZoneLockService;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * How a station's egg is paid for when coins and diamonds are not the
     * answer - registered per egg id by whoever owns that currency.
     * <p>
     * A seasonal event's egg costs Candy, which yield-packstations has no
     * concept of and should not grow one: the event plugin owns the
     * currency, so it owns the charging. The station just asks.
     */
    public interface AlternateCharge {

        /** How many this player could pay for right now, capped at {@code cap}. */
        int affordable(Player player, int cap);

        /** Takes payment for exactly {@code count}. False if it could not, in which case nothing is charged and nothing hatches. */
        boolean charge(Player player, int count);

        /** Hands payment back when the hatch was refused after it was taken - see {@link #attemptHatch}. */
        void refund(Player player, int count);

        /** What one costs, legacy-coded, for the station's own sign. */
        String priceLabel();
    }

    /** Whoever owns a dynamic station's contents - see {@link PackStation#dynamicPackKey()}. */
    private record DynamicPack(Supplier<String> packId, Supplier<String> label) {
    }

    /** How many were actually hatched, so the caller can say so. Zero whenever {@code result} isn't SUCCESS. */
    public record Purchase(Result result, int quantity) {
    }

    private final YieldPacks packs;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final ZoneLockService zoneLockService;
    private final Map<String, DynamicPack> dynamicPacks = new ConcurrentHashMap<>();
    private final Map<String, AlternateCharge> alternateCharges = new ConcurrentHashMap<>();

    public PackStationService(YieldPacks packs, Supplier<Map<String, ZoneDefinition>> zones,
                               ZoneLockService zoneLockService) {
        this.packs = packs;
        this.zones = zones;
        this.zoneLockService = zoneLockService;
    }

    /**
     * Hands a station kind over to whoever owns its contents - the black
     * market to its own rotation, {@code "event"} to yield-events. Both
     * suppliers are re-read on every render, so a station whose egg changes
     * (or stops existing between events) needs no reload.
     */
    public void registerDynamicPack(String key, Supplier<String> packId, Supplier<String> label) {
        dynamicPacks.put(key, new DynamicPack(packId, label));
    }

    /** See {@link AlternateCharge}. */
    public void registerAlternateCharge(String packId, AlternateCharge charge) {
        alternateCharges.put(packId, charge);
    }

    /** The egg this station is currently offering - fixed for a zone station, asked of its owner for a dynamic one, and null when that owner has nothing to offer. */
    public String currentPackId(PackStation station) {
        if (!station.isDynamic()) {
            return station.fixedPackId();
        }
        DynamicPack owner = dynamicPacks.get(station.dynamicPackKey());
        return owner != null ? owner.packId().get() : null;
    }

    /** The banner line a dynamic station wears, e.g. "Black Market" - empty for a zone station. */
    public String dynamicLabel(PackStation station) {
        if (!station.isDynamic()) {
            return "";
        }
        DynamicPack owner = dynamicPacks.get(station.dynamicPackKey());
        String label = owner != null ? owner.label().get() : null;
        return label != null ? label : "";
    }

    /** What one of this station's eggs costs, in words - its own currency if something else owns the price. */
    public String priceLabel(PackStation station) {
        String packId = currentPackId(station);
        AlternateCharge charge = packId != null ? alternateCharges.get(packId) : null;
        if (charge != null) {
            return charge.priceLabel();
        }
        PackDefinition pack = resolvePack(station);
        return pack != null ? PackOddsLore.costLine(pack, 1) : "";
    }

    public boolean canAfford(Player player, PackStation station) {
        if (!station.isDynamic() && !zoneUnlocked(player, station)) {
            return false;
        }
        PackDefinition pack = resolvePack(station);
        if (pack == null) {
            return false;
        }
        AlternateCharge charge = alternateCharges.get(pack.id());
        if (charge != null) {
            return charge.affordable(player, 1) >= 1;
        }
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        return profile.getCoins().compareTo(BigInteger.valueOf(pack.coinCost())) >= 0
                && profile.getDiamonds().compareTo(BigInteger.valueOf(pack.diamondCost())) >= 0
                && profile.getCredits().compareTo(BigInteger.valueOf(pack.creditCost())) >= 0;
    }

    public Purchase attemptHatch(Player player, PackStation station, boolean bulk) {
        if (!station.isDynamic() && !zoneUnlocked(player, station)) {
            return new Purchase(Result.ZONE_LOCKED, 0);
        }
        String packId = currentPackId(station);
        PackDefinition pack = resolvePack(station);
        if (packId == null || pack == null) {
            return new Purchase(Result.NO_STOCK_CONFIGURED, 0);
        }
        int tier = packs.getPackOpenService().maxTierFor(player);
        AlternateCharge charge = alternateCharges.get(packId);

        // Resolved up front rather than handed straight to the hatch, which
        // is all-or-nothing on cost - asking it for ten when the player can
        // afford seven would hatch nothing at all.
        int quantity = bulk
                ? (charge != null ? charge.affordable(player, tier)
                                  : packs.getPackRollService().affordableHatches(player, packId, tier))
                : 1;
        if (quantity <= 0) {
            return new Purchase(Result.CANT_AFFORD, 0);
        }

        if (charge == null) {
            PackRollService.PurchaseResult result = packs.getPackOpenService().tryHatch(player, packId, quantity);
            return toPurchase(result);
        }
        // Someone else's currency. Check the hatch would actually happen
        // BEFORE taking payment - the cooldown refuses silently and often,
        // and charging for a hatch that then does not occur is the one
        // failure mode a currency must never have.
        if (!packs.getPackOpenService().readyToHatch(player)) {
            return new Purchase(Result.BUSY, 0);
        }
        if (!charge.charge(player, quantity)) {
            return new Purchase(Result.CANT_AFFORD, 0);
        }
        PackRollService.PurchaseResult result = packs.getPackOpenService().tryHatch(player, packId, quantity, false);
        if (!result.success()) {
            // Paid for, and then refused anyway - hand it back rather than
            // pocketing it.
            charge.refund(player, quantity);
        }
        return toPurchase(result);
    }

    private Purchase toPurchase(PackRollService.PurchaseResult result) {
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
