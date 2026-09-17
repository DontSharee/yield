package me.dontshare.yieldpacks.roll;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.math.WeightedRandom;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.data.PackPoolEntry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldpacks.pity.PityService;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.shop.ShopStockService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The heart of the game loop, split into two independent steps per the
 * pack-storage redesign: {@link #buyIntoStorage} only ever moves currency
 * into unopened-pack inventory (no rolling at all), and
 * {@link #openOneFromStorage}/{@link #openManyFromStorage} are the only
 * things that actually roll a pet (see
 * {@code me.dontshare.yieldpacks.roll.PackOpenService}, the only caller,
 * which layers the open cooldown/auto-open loop/gamepass gate on top of
 * these).
 */
public final class PackRollService {

    /** One resolved roll: the pet obtained and whether it was newly seen for this pack's collection. */
    public record RollResult(ItemDefinition item, boolean firstTimeCollected) {
    }

    /** One pool entry's actual, luck-adjusted odds for a given roll - see {@link #oddsFor}. */
    public record WeightedOdds(ItemDefinition item, double probability) {
    }

    /** Outcome of a buy-into-storage or open-one-from-storage call. {@code luckMultiplier} is the exact value used for THIS roll (1.0, unused, for a buy-only result) - callers displaying "1 in N" odds after the fact must reuse this rather than recomputing it, since the profile's own rollCount/pity state has already moved on by the time any animation plays. */
    public record PurchaseResult(boolean success, String failureReason, List<RollResult> rolls, double luckMultiplier) {
        public static PurchaseResult failure(String reason) {
            return new PurchaseResult(false, reason, List.of(), 1.0);
        }

        public static PurchaseResult success(List<RollResult> rolls, double luckMultiplier) {
            return new PurchaseResult(true, null, rolls, luckMultiplier);
        }
    }

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final EquipmentService equipmentService;
    private final LuckService luckService;
    private final ExistsCounterStore existsCounterStore;
    private final PetDisplayService petDisplayService;
    private final ShopStockService shopStockService;
    private final PityService pityService;

    /** Keyed, composable "chance to override this roll with a random Exclusive-rarity pet" registry (see yield-blocktree) - same shape as {@code LuckService#extraLuckProviders}, summed additively. */
    private final Map<String, Function<PackPlayerProfile, Double>> exclusiveFindChanceProviders = new ConcurrentHashMap<>();

    public void registerExclusiveFindChanceProvider(String key, Function<PackPlayerProfile, Double> provider) {
        exclusiveFindChanceProviders.put(key, provider);
    }

    public void unregisterExclusiveFindChanceProvider(String key) {
        exclusiveFindChanceProviders.remove(key);
    }

    private double totalExclusiveFindChance(PackPlayerProfile profile) {
        double total = 0.0;
        for (Function<PackPlayerProfile, Double> provider : exclusiveFindChanceProviders.values()) {
            total += provider.apply(profile);
        }
        return total;
    }

    /** A uniformly-random base-form (non-fused) Exclusive-rarity item, or null if none are configured. */
    private ItemDefinition rollRandomExclusive() {
        List<ItemDefinition> exclusives = content.get().items().all().stream()
                .filter(item -> item.fusionTier() == FusionTier.NORMAL)
                .filter(item -> "exclusive".equals(item.rarityId()))
                .toList();
        if (exclusives.isEmpty()) {
            return null;
        }
        return exclusives.get(ThreadLocalRandom.current().nextInt(exclusives.size()));
    }

    public PackRollService(Supplier<PackContentLoader.ContentSnapshot> content, PlayerDataStore<PackPlayerProfile> store,
                            EquipmentService equipmentService, LuckService luckService, ExistsCounterStore existsCounterStore,
                            PetDisplayService petDisplayService, ShopStockService shopStockService, PityService pityService) {
        this.content = content;
        this.store = store;
        this.equipmentService = equipmentService;
        this.luckService = luckService;
        this.existsCounterStore = existsCounterStore;
        this.petDisplayService = petDisplayService;
        this.shopStockService = shopStockService;
        this.pityService = pityService;
    }

    /** The most units of {@code packId} the player can currently afford AND has remaining in shop stock. */
    public int maxAffordable(Player player, String packId, int hardCap) {
        PackDefinition pack = content.get().packs().getOrThrow(packId);
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int byCoins = pack.coinCost() <= 0 ? hardCap : affordableUnits(profile.getCoins(), pack.coinCost(), hardCap);
        int byDiamonds = pack.diamondCost() <= 0 ? hardCap : affordableUnits(profile.getDiamonds(), pack.diamondCost(), hardCap);
        int byStock = shopStockService.remainingStock(player, packId);
        return Math.max(0, Math.min(Math.min(byCoins, byDiamonds), byStock));
    }

    /** {@code balance / unitCost}, clamped to {@code hardCap} BEFORE narrowing to int - never lets the division's own result overflow int on its way down. */
    private int affordableUnits(BigInteger balance, long unitCost, int hardCap) {
        BigInteger units = balance.divide(BigInteger.valueOf(unitCost));
        return units.min(BigInteger.valueOf(hardCap)).max(BigInteger.ZERO).intValue();
    }

    /** Buys {@code quantity} of {@code packId} into unopened storage - deducts currency and shop stock, rolls nothing. */
    public PurchaseResult buyIntoStorage(Player player, String packId, int quantity) {
        return buyIntoStorage(player, packId, quantity, true);
    }

    /** Same as {@link #buyIntoStorage(Player, String, int)} but never consults {@link ShopStockService} - for a physical pack station's own unlimited-supply, cost-only purchase (see yield-packstations), where the pack's own coin/diamond cost is the sole gate. */
    public PurchaseResult buyStationPack(Player player, String packId, int quantity) {
        return buyIntoStorage(player, packId, quantity, false);
    }

    private PurchaseResult buyIntoStorage(Player player, String packId, int quantity, boolean checkStock) {
        if (quantity <= 0) {
            return PurchaseResult.failure("Nothing to buy.");
        }
        PackDefinition pack;
        try {
            pack = content.get().packs().getOrThrow(packId);
        } catch (IllegalArgumentException e) {
            return PurchaseResult.failure("That pack no longer exists.");
        }

        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        BigInteger totalCoinCost = BigInteger.valueOf(pack.coinCost()).multiply(BigInteger.valueOf(quantity));
        BigInteger totalDiamondCost = BigInteger.valueOf(pack.diamondCost()).multiply(BigInteger.valueOf(quantity));
        if (profile.getCoins().compareTo(totalCoinCost) < 0 || profile.getDiamonds().compareTo(totalDiamondCost) < 0) {
            return PurchaseResult.failure("You can't afford " + quantity + "x " + Formatting.stripLeadingColorCodes(pack.displayName()) + ".");
        }
        if (checkStock && shopStockService.remainingStock(player, packId) < quantity) {
            return PurchaseResult.failure("Not enough " + Formatting.stripLeadingColorCodes(pack.displayName()) + " left in stock this cycle.");
        }

        profile.setCoins(profile.getCoins().subtract(totalCoinCost));
        profile.setDiamonds(profile.getDiamonds().subtract(totalDiamondCost));
        profile.getStoredPacks().merge(packId, quantity, Integer::sum);
        store.save(player.getUniqueId());
        if (checkStock) {
            shopStockService.recordPurchase(player, packId, quantity);
        }
        return PurchaseResult.success(List.of(), 1.0);
    }

    /** The most packs {@link #openManyFromStorage} will ever open in one call, regardless of how many are requested or stored. */
    public static final int MULTI_OPEN_CAP = 24;

    /** Opens exactly one stored, already-paid-for pack - the only thing that ever rolls a pet. */
    public PurchaseResult openOneFromStorage(Player player, String packId) {
        PackDefinition pack;
        try {
            pack = content.get().packs().getOrThrow(packId);
        } catch (IllegalArgumentException e) {
            return PurchaseResult.failure("That pack no longer exists.");
        }

        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int stored = profile.getStoredPacks().getOrDefault(packId, 0);
        if (stored <= 0) {
            return PurchaseResult.failure("You don't have any " + Formatting.stripLeadingColorCodes(pack.displayName()) + " to open.");
        }
        profile.getStoredPacks().put(packId, stored - 1);
        advanceActivePackIfExhausted(profile, packId);

        RollOutcome outcome = rollInPlace(player, pack, profile, packId);
        store.save(player.getUniqueId());
        petDisplayService.refresh(player);
        return PurchaseResult.success(List.of(outcome.result()), outcome.luckMultiplier());
    }

    /**
     * Opens up to {@code min(count, stored, MULTI_OPEN_CAP)} stored packs at
     * once - same per-roll logic {@link #openOneFromStorage} uses (luck/
     * pity, exclusive override, first-time tracking, auto-equip, exists
     * counter), repeated in memory against the SAME profile instance, with
     * storage decremented once up front and only ONE {@code store.save}/
     * {@code petDisplayService.refresh} at the end - same "batch the
     * persistence" shape {@code FusionService#cascade} already established
     * for looping a per-unit action N times.
     */
    public PurchaseResult openManyFromStorage(Player player, String packId, int count) {
        PackDefinition pack;
        try {
            pack = content.get().packs().getOrThrow(packId);
        } catch (IllegalArgumentException e) {
            return PurchaseResult.failure("That pack no longer exists.");
        }

        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int stored = profile.getStoredPacks().getOrDefault(packId, 0);
        int actual = Math.min(Math.min(count, stored), MULTI_OPEN_CAP);
        if (actual <= 0) {
            return PurchaseResult.failure("You don't have any " + Formatting.stripLeadingColorCodes(pack.displayName()) + " to open.");
        }
        profile.getStoredPacks().put(packId, stored - actual);
        advanceActivePackIfExhausted(profile, packId);

        List<RollResult> rolls = new ArrayList<>(actual);
        double lastLuckMultiplier = 1.0;
        for (int i = 0; i < actual; i++) {
            RollOutcome outcome = rollInPlace(player, pack, profile, packId);
            rolls.add(outcome.result());
            lastLuckMultiplier = outcome.luckMultiplier();
        }
        store.save(player.getUniqueId());
        petDisplayService.refresh(player);
        return PurchaseResult.success(rolls, lastLuckMultiplier);
    }

    /** One internal roll result and the luck multiplier that produced it - private since a caller only ever needs the public {@link RollResult} half; the multiplier is just plumbing for {@link PurchaseResult}. */
    private record RollOutcome(RollResult result, double luckMultiplier) {
    }

    /** The actual roll + side effects shared by {@link #openOneFromStorage} and {@link #openManyFromStorage} - assumes storage has ALREADY been decremented by the caller; mutates {@code profile} in place (rollCount, exists counter, auto-equip) but never saves/refreshes - callers own that. */
    private RollOutcome rollInPlace(Player player, PackDefinition pack, PackPlayerProfile profile, String packId) {
        double luckMultiplier = luckService.totalLuckMultiplier(profile) * pityService.multiplierFor(profile.getRollCount());
        ItemDefinition rolled = rollOne(pack, luckMultiplier);
        double exclusiveChance = totalExclusiveFindChance(profile);
        if (exclusiveChance > 0 && ThreadLocalRandom.current().nextDouble() < exclusiveChance) {
            ItemDefinition exclusiveOverride = rollRandomExclusive();
            if (exclusiveOverride != null) {
                rolled = exclusiveOverride;
            }
        }
        boolean firstTime = !hasCollected(profile, packId, rolled.id());
        var newPet = profile.addOwnedItem(packId, rolled.id());
        if (equipmentService.autoEquipOnRoll(profile, newPet)) {
            // A tutorial-tracking auto-equip is exactly as real as a manual
            // one from the Bag - fire the same event so its EQUIP_PET step
            // (and anything else listening) doesn't need to know there are
            // two different ways to end up equipped.
            Bukkit.getPluginManager().callEvent(new PetEquippedEvent(player, newPet.getInstanceId()));
        }
        if (rolled.trackExists()) {
            existsCounterStore.increment(rolled.id());
        }
        profile.setRollCount(profile.getRollCount() + 1);
        return new RollOutcome(new RollResult(rolled, firstTime), luckMultiplier);
    }

    /**
     * Once the just-opened pack (if it was the active one) hits zero in
     * storage, silently switches the active pack to whichever OTHER pack
     * this player still has the most of stored, ranked best-to-worst by
     * {@link PackDefinition#sortOrder}, falling back to no selection if
     * nothing's left - so opening never dead-ends on "you don't have any of
     * that to open" while a lesser pack sits unused in storage.
     */
    private void advanceActivePackIfExhausted(PackPlayerProfile profile, String justOpenedPackId) {
        if (!justOpenedPackId.equals(profile.getActivePackId())
                || profile.getStoredPacks().getOrDefault(justOpenedPackId, 0) > 0) {
            return;
        }
        profile.setActivePackId(bestStoredPackId(profile));
    }

    /** Whichever pack this player currently has the most VALUE of stored (ranked by {@link PackDefinition#sortOrder}, not quantity) - null if storage is empty. Used both by the auto-advance above and by a top-level "start auto-opening" action that doesn't require picking a pack first (see yield-packs' PackStorageGui). */
    public String bestStoredPackId(PackPlayerProfile profile) {
        return content.get().packs().all().stream()
                .filter(p -> profile.getStoredPacks().getOrDefault(p.id(), 0) > 0)
                .max(Comparator.comparingInt(PackDefinition::sortOrder))
                .map(PackDefinition::id)
                .orElse(null);
    }

    private boolean hasCollected(PackPlayerProfile profile, String packId, String itemId) {
        Set<String> collected = profile.getPackCollectionProgress().get(packId);
        return collected != null && collected.contains(itemId);
    }

    /** Every pool entry's actual, luck-adjusted probability for this roll (normalized, sums to 1.0) - the same weighting {@link #rollOne} picks from, exposed for callers that need to display real odds (see yield-packs' pack-reveal reel) or sample statistically-plausible decoys. */
    public List<WeightedOdds> oddsFor(PackDefinition pack, double luckMultiplier) {
        PackContentLoader.ContentSnapshot snapshot = content.get();
        List<PackPoolEntry> pool = pack.pool();

        double totalWeight = 0;
        double[] adjustedWeights = new double[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            PackPoolEntry entry = pool.get(i);
            ItemDefinition item = snapshot.items().getOrThrow(entry.itemId());
            Rarity rarity = snapshot.rarities().getOrThrow(item.rarityId());
            double adjusted = entry.weight() * Math.pow(luckMultiplier, rarity.luckExponent());
            adjustedWeights[i] = adjusted;
            totalWeight += adjusted;
        }

        List<WeightedOdds> result = new ArrayList<>(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            ItemDefinition item = snapshot.items().getOrThrow(pool.get(i).itemId());
            result.add(new WeightedOdds(item, totalWeight > 0 ? adjustedWeights[i] / totalWeight : 0));
        }
        return result;
    }

    private ItemDefinition rollOne(PackDefinition pack, double luckMultiplier) {
        List<WeightedOdds> odds = oddsFor(pack, luckMultiplier);
        return WeightedRandom.pick(odds, WeightedOdds::probability).item();
    }
}
