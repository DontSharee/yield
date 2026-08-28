package me.dontshare.yieldpacks.roll;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.data.PackPoolEntry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The heart of the game loop: validates a purchase, rolls weighted-random
 * pets from a pack's pool (luck-adjusted), applies them to the player's bag
 * and equip slots, and tracks collection progress + global exists counters.
 */
public final class PackRollService {

    /** One resolved roll: the pet obtained and whether it was newly seen for this pack's collection. */
    public record RollResult(ItemDefinition item, boolean firstTimeCollected) {
    }

    /** Outcome of a purchase-and-open call. */
    public record PurchaseResult(boolean success, String failureReason, List<RollResult> rolls) {
        public static PurchaseResult failure(String reason) {
            return new PurchaseResult(false, reason, List.of());
        }

        public static PurchaseResult success(List<RollResult> rolls) {
            return new PurchaseResult(true, null, rolls);
        }
    }

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final EquipmentService equipmentService;
    private final LuckService luckService;
    private final ExistsCounterStore existsCounterStore;
    private final PetDisplayService petDisplayService;

    public PackRollService(Supplier<PackContentLoader.ContentSnapshot> content, PlayerDataStore<PackPlayerProfile> store,
                            EquipmentService equipmentService, LuckService luckService, ExistsCounterStore existsCounterStore,
                            PetDisplayService petDisplayService) {
        this.content = content;
        this.store = store;
        this.equipmentService = equipmentService;
        this.luckService = luckService;
        this.existsCounterStore = existsCounterStore;
        this.petDisplayService = petDisplayService;
    }

    /** The most opens of {@code packId} the player can currently afford, capped at {@code hardCap} (e.g. for "Buy Max"). */
    public int maxAffordable(Player player, String packId, int hardCap) {
        PackDefinition pack = content.get().packs().getOrThrow(packId);
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int byCoins = pack.coinCost() <= 0 ? hardCap : (int) Math.min(hardCap, profile.getCoins() / pack.coinCost());
        int byGems = pack.gemCost() <= 0 ? hardCap : (int) Math.min(hardCap, profile.getGems() / pack.gemCost());
        return Math.max(0, Math.min(byCoins, byGems));
    }

    public PurchaseResult buyAndOpen(Player player, String packId, int quantity) {
        if (quantity <= 0) {
            return PurchaseResult.failure("Nothing to open.");
        }
        PackDefinition pack;
        try {
            pack = content.get().packs().getOrThrow(packId);
        } catch (IllegalArgumentException e) {
            return PurchaseResult.failure("That pack no longer exists.");
        }

        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        long totalCoinCost = pack.coinCost() * quantity;
        long totalGemCost = pack.gemCost() * quantity;
        if (profile.getCoins() < totalCoinCost || profile.getGems() < totalGemCost) {
            return PurchaseResult.failure("You can't afford " + quantity + "x " + pack.displayName() + ".");
        }

        profile.setCoins(profile.getCoins() - totalCoinCost);
        profile.setGems(profile.getGems() - totalGemCost);

        double luckMultiplier = luckService.totalLuckMultiplier(profile);
        List<RollResult> rolls = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            ItemDefinition rolled = rollOne(pack, luckMultiplier);
            boolean firstTime = !hasCollected(profile, packId, rolled.id());
            profile.addOwnedItem(packId, rolled.id());
            equipmentService.autoEquipOnRoll(profile, rolled.id());
            if (rolled.trackExists()) {
                existsCounterStore.increment(rolled.id());
            }
            rolls.add(new RollResult(rolled, firstTime));
        }
        store.save(player.getUniqueId());
        // Once for the whole batch, not per-roll - a "Buy 10K" shouldn't rebuild
        // (and re-spawn, for every nearby viewer) the pet display thousands of
        // times in one instant just because auto-equip may have swapped mid-loop.
        petDisplayService.refresh(player);
        return PurchaseResult.success(rolls);
    }

    private boolean hasCollected(PackPlayerProfile profile, String packId, String itemId) {
        Set<String> collected = profile.getPackCollectionProgress().get(packId);
        return collected != null && collected.contains(itemId);
    }

    private ItemDefinition rollOne(PackDefinition pack, double luckMultiplier) {
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

        double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0;
        for (int i = 0; i < pool.size(); i++) {
            cumulative += adjustedWeights[i];
            if (roll < cumulative) {
                return snapshot.items().getOrThrow(pool.get(i).itemId());
            }
        }
        // Floating-point rounding fallback - land on the last entry rather than throwing.
        return snapshot.items().getOrThrow(pool.get(pool.size() - 1).itemId());
    }
}
