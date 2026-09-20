package me.dontshare.yieldpacks.roll;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.math.WeightedRandom;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.data.PackPoolEntry;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.VariantConfig;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldpacks.pet.PetInstance;
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

    /**
     * One resolved roll: the pet obtained, whether it was newly seen for this
     * pack's collection, the owned instance itself (so a caller can read its
     * Shiny flag), and the "1 in N" this exact pull actually beat.
     */
    public record RollResult(ItemDefinition item, boolean firstTimeCollected, PetInstance pet, long oneIn, boolean huge) {
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

    /** A Huge secret's true odds run past a trillion-to-one; clamping keeps "1 in N" a number a person can read. */
    private static final long MAX_DISPLAYED_ONE_IN = 1_000_000_000_000L;

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

    /** One pack's Huge-eligible entries and their weights, renormalized among themselves - see {@link #rollHuge}. */
    private record HugeCandidate(ItemDefinition huge, double probability) {
    }

    /**
     * Which Huge a proc turns into, picked from this pack's own pool by the
     * SAME weights the normal table uses, restricted to the pets that have a
     * Huge version at all. Null if the pack contains nothing eligible, in
     * which case the proc is simply dropped and the normal roll stands.
     * <p>
     * Deliberately independent of what the normal roll came up with. The
     * obvious alternative - "upgrade the pet you just rolled" - sounds
     * tidier but makes the odds of any particular Huge impossible to state
     * honestly, because an ineligible roll would have to fall back to
     * something and that redistribution is invisible to the player. Picking
     * fresh from the eligible weights means a given Huge's odds are exactly
     * {@code huge.chance x its share of the eligible weight}, which is a
     * number the reveal can show and a player can trust.
     * <p>
     * Weighting by the pool rather than uniformly is what keeps the rarity
     * ladder intact inside the Huge chase: a Huge of this pack's common-ish
     * Rare shows up reasonably often, a Huge of its Secret is a
     * once-on-the-whole-server event.
     */
    private List<HugeCandidate> hugeCandidatesFor(PackDefinition pack) {
        ItemRegistry items = content.get().items();
        List<PackPoolEntry> eligible = pack.pool().stream()
                .filter(entry -> items.find(entry.itemId() + "_huge").isPresent())
                .toList();
        double total = eligible.stream().mapToDouble(PackPoolEntry::weight).sum();
        if (total <= 0) {
            return List.of();
        }
        List<HugeCandidate> candidates = new ArrayList<>(eligible.size());
        for (PackPoolEntry entry : eligible) {
            candidates.add(new HugeCandidate(items.getOrThrow(entry.itemId() + "_huge"), entry.weight() / total));
        }
        return candidates;
    }

    /**
     * The three "chase" chances as they apply to ONE player looking at ONE
     * pack right now - everything the odds display needs that isn't in the
     * normal pool table (see yield-packs' PackOddsLore, the only caller).
     * <p>
     * Each is zero when the mechanic genuinely doesn't apply, so a caller
     * can simply omit the line: {@code hugeChance} for a pack with no
     * Huge-eligible pets in its pool, {@code exclusiveFindChance} for a
     * player who hasn't earned that perk.
     */
    public record ChaseOdds(double hugeChance, double shinyChance, double exclusiveFindChance) {
    }

    /**
     * The luck multiplier to SHOW this player - their standing luck, without
     * {@link PityService}'s milestone bonus folded in.
     * <p>
     * Pity is left out on purpose: it applies to exactly one roll every
     * {@code n}, so folding it into a pack's lore would show odds that are
     * wrong for all but that single open. The pity bar (see
     * PackActionBarService) is where that bonus is communicated, and it
     * shows when it's about to land rather than pretending it's always on.
     */
    public double displayLuckFor(PackPlayerProfile profile) {
        return luckService.totalLuckMultiplier(profile);
    }

    public double displayLuckFor(Player player) {
        return displayLuckFor(store.getOrCreate(player.getUniqueId()));
    }

    /** {@link ChaseOdds} for this player and pack - luck-scaled exactly the way {@link #rollInPlace} scales the real rolls. */
    public ChaseOdds chaseOddsFor(PackDefinition pack, Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        VariantConfig variants = content.get().variants();
        double luck = displayLuckFor(profile);
        double hugeChance = hugeCandidatesFor(pack).isEmpty()
                ? 0.0
                : Math.min(1.0, variants.hugeChance() * luck);
        double exclusiveChance = baseExclusives().isEmpty()
                ? 0.0
                : Math.min(1.0, totalExclusiveFindChance(profile));
        return new ChaseOdds(hugeChance, variants.shinyChance(), exclusiveChance);
    }

    private ItemDefinition rollHuge(PackDefinition pack) {
        List<HugeCandidate> candidates = hugeCandidatesFor(pack);
        if (candidates.isEmpty()) {
            return null;
        }
        return WeightedRandom.pick(candidates, HugeCandidate::probability).huge();
    }

    /** Every base-form (non-fused, non-Huge) Exclusive-rarity item - the pool the blocktree find-perk picks uniformly from. */
    private List<ItemDefinition> baseExclusives() {
        return content.get().items().all().stream()
                .filter(item -> item.fusionTier() == FusionTier.NORMAL)
                .filter(item -> !item.huge())
                .filter(item -> "exclusive".equals(item.rarityId()))
                .toList();
    }

    /** A uniformly-random base-form Exclusive-rarity item, or null if none are configured. */
    private ItemDefinition rollRandomExclusive() {
        List<ItemDefinition> exclusives = baseExclusives();
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
        boolean fromExclusiveFind = false;
        if (exclusiveChance > 0 && ThreadLocalRandom.current().nextDouble() < exclusiveChance) {
            ItemDefinition exclusiveOverride = rollRandomExclusive();
            if (exclusiveOverride != null) {
                rolled = exclusiveOverride;
                fromExclusiveFind = true;
            }
        }
        // Huge replaces whatever came up, and is luck-scaled: investing in
        // luck should move the thing players actually chase. Rolled AFTER the
        // Exclusive override so the two can't both rewrite the same pull -
        // whichever lands last is what you get, and Huge is the rarer of the
        // two by orders of magnitude.
        VariantConfig variants = content.get().variants();
        boolean huge = false;
        double hugeChance = variants.hugeChance() * luckMultiplier;
        if (hugeChance > 0 && ThreadLocalRandom.current().nextDouble() < hugeChance) {
            ItemDefinition hugeOverride = rollHuge(pack);
            if (hugeOverride != null) {
                rolled = hugeOverride;
                huge = true;
            }
        }
        // What this pull was worth beating, for the reveal and for the
        // player's own Best Luck record. A Huge's odds are its own chance
        // times the odds of the pet it landed on, because you had to clear
        // both - which is what makes a Huge secret a genuinely absurd number.
        long oneIn = oneInFor(pack, luckMultiplier, rolled, huge, fromExclusiveFind, exclusiveChance, variants);

        boolean firstTime = !hasCollected(profile, packId, rolled.id());
        var newPet = profile.addOwnedItem(packId, rolled.id());
        maybeRollShiny(newPet);
        recordBestLuck(profile, rolled, oneIn);
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
        return new RollOutcome(new RollResult(rolled, firstTime, newPet, oneIn, huge), luckMultiplier);
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

    /**
     * The "1 in N" this pull beat. For a normal pet that is just its own
     * slot in the pack's odds table; for a Huge it also has to clear the
     * Huge roll itself, so the two chances multiply.
     * <p>
     * Capped at {@link #MAX_DISPLAYED_ONE_IN} purely so an absurd product
     * (a Huge secret runs to the billions) stays a readable number rather
     * than overflowing a leaderboard column.
     */
    private long oneInFor(PackDefinition pack, double luckMultiplier, ItemDefinition rolled,
                           boolean huge, boolean fromExclusiveFind, double exclusiveChance, VariantConfig variants) {
        double probability;
        if (fromExclusiveFind && !huge) {
            // An Exclusive handed over by yield-blocktree's find-perk is NOT
            // in this pack's pool, so looking it up there would find nothing
            // and report "1 in 1". Its real odds are the perk's own chance
            // divided by how many Exclusives it picks uniformly between.
            int exclusives = baseExclusives().size();
            probability = exclusives > 0 ? exclusiveChance / exclusives : 0.0;
        } else if (huge) {
            // Two independent gates: the Huge proc itself, then which Huge it
            // landed on among this pack's eligible weights.
            double share = hugeCandidatesFor(pack).stream()
                    .filter(candidate -> candidate.huge().id().equals(rolled.id()))
                    .mapToDouble(HugeCandidate::probability)
                    .findFirst()
                    .orElse(0.0);
            probability = variants.hugeChance() * luckMultiplier * share;
        } else {
            probability = oddsFor(pack, luckMultiplier).stream()
                    .filter(odds -> odds.item().id().equals(rolled.id()))
                    .mapToDouble(WeightedOdds::probability)
                    .findFirst()
                    .orElse(1.0);
        }
        if (probability <= 0) {
            return MAX_DISPLAYED_ONE_IN;
        }
        return (long) Math.min(MAX_DISPLAYED_ONE_IN, Math.round(1.0 / probability));
    }

    /**
     * Rolls the flat Shiny chance for a pet that has just been obtained,
     * and returns whether it landed.
     * <p>
     * Public because a pack open is not the only way to get a pet - crates
     * (yield-spawnnpcs) and lootboxes (yield-lootboxes) grant them too, and
     * a Shiny that could only ever come from a pack would make a liar of
     * the thing every odds screen says about it: flat chance, any pet, no
     * luck scaling (see VariantConfig for why it is deliberately the one
     * roll luck doesn't touch).
     */
    public boolean maybeRollShiny(PetInstance pet) {
        double chance = content.get().variants().shinyChance();
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return false;
        }
        pet.setShiny(true);
        return true;
    }

    /** Never decreases - a player's Best Luck is the rarest thing they have ever landed, not their most recent. */
    private void recordBestLuck(PackPlayerProfile profile, ItemDefinition rolled, long oneIn) {
        if (oneIn > profile.getBestLuckOneIn()) {
            profile.setBestLuckOneIn(oneIn);
            profile.setBestLuckItemId(rolled.id());
        }
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
