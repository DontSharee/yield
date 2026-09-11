package me.dontshare.yieldpacks.shop;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The rotating per-player shop. What's currently in stock is entirely
 * <b>derived</b> from the current wall-clock cycle plus the player's own
 * UUID, never persisted - every player's rotation flips over at the exact
 * same instant (same cycle id), but each player independently rolls their
 * own selection from it, and the roll is reproducible on demand rather than
 * needing a scheduled task to compute and broadcast it. The only real
 * mutable state is how much of each pack a player has already bought
 * <b>this cycle</b>, since a derived roll can't remember that on its own -
 * see {@link #resetIfNewCycle}, which lazily clears it the moment a profile
 * notices the cycle id has moved on.
 */
public final class ShopStockService {

    private record CachedStock(long cycleId, List<ShopSlot> slots) {
    }

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final LuckService luckService;
    private final Map<UUID, CachedStock> cache = new ConcurrentHashMap<>();

    public ShopStockService(Supplier<PackContentLoader.ContentSnapshot> content, PlayerDataStore<PackPlayerProfile> store,
                             LuckService luckService) {
        this.content = content;
        this.store = store;
        this.luckService = luckService;
    }

    public long currentCycleId() {
        return System.currentTimeMillis() / content.get().shop().resetIntervalMillis();
    }

    public Duration timeUntilNextReset() {
        long intervalMillis = content.get().shop().resetIntervalMillis();
        long elapsed = System.currentTimeMillis() % intervalMillis;
        return Duration.ofMillis(intervalMillis - elapsed);
    }

    /** This player's rolled stock for the current cycle - cached until the cycle actually changes. */
    public List<ShopSlot> currentStock(Player player) {
        long cycleId = currentCycleId();
        CachedStock cached = cache.get(player.getUniqueId());
        if (cached != null && cached.cycleId() == cycleId) {
            return cached.slots();
        }
        List<ShopSlot> rolled = roll(player, cycleId);
        cache.put(player.getUniqueId(), new CachedStock(cycleId, rolled));
        return rolled;
    }

    /** Remaining purchasable units of {@code packId} this cycle, or 0 if it isn't in this player's current stock at all. */
    public int remainingStock(Player player, String packId) {
        resetIfNewCycle(player);
        int rolled = currentStock(player).stream()
                .filter(slot -> slot.pack().id().equals(packId))
                .mapToInt(ShopSlot::stockCount)
                .findFirst()
                .orElse(0);
        int purchased = store.getOrCreate(player.getUniqueId()).getStockPurchasedThisCycle().getOrDefault(packId, 0);
        return Math.max(0, rolled - purchased);
    }

    /** Records a purchase against this cycle's stock so the same units can't be bought twice. */
    public void recordPurchase(Player player, String packId, int quantity) {
        resetIfNewCycle(player);
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.getStockPurchasedThisCycle().merge(packId, quantity, Integer::sum);
        store.save(player.getUniqueId());
    }

    private void resetIfNewCycle(Player player) {
        long cycleId = currentCycleId();
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (profile.getLastStockCycleId() != cycleId) {
            profile.getStockPurchasedThisCycle().clear();
            profile.setLastStockCycleId(cycleId);
        }
    }

    /**
     * Every shop-eligible pack always gets a slot - weight/luck decides
     * whether THIS cycle restocked it at all, not whether it's shown. Each
     * pack independently rolls its own restock chance (weight treated as a
     * percent-out-of-100 chance, luck-adjusted the same way pet-roll odds
     * are), and only on a hit does it also roll a quantity between its own
     * min/max stock - a miss just means {@code stockCount == 0} (rendered
     * as a barrier, see PackShopGui) rather than the pack disappearing.
     */
    private List<ShopSlot> roll(Player player, long cycleId) {
        PackContentLoader.ContentSnapshot snapshot = content.get();
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        double luckMultiplier = luckService.totalLuckMultiplier(profile);

        // Seeded on (cycle, player) - deterministic and reproducible without
        // ever needing to store the result, which is what lets every
        // player's rotation share the same reset moment for free.
        Random rng = new Random(Objects.hash(cycleId, player.getUniqueId()));

        List<ShopSlot> slots = new ArrayList<>(snapshot.packs().all().size());
        for (PackDefinition pack : snapshot.packs().all()) {
            double restockChance = Math.min(1.0, pack.shopWeight() * Math.pow(luckMultiplier, pack.shopLuckExponent()) / 100.0);
            boolean restocked = rng.nextDouble() < restockChance;
            int stockCount = 0;
            if (restocked) {
                stockCount = pack.minStock() >= pack.maxStock()
                        ? pack.minStock()
                        : pack.minStock() + rng.nextInt(pack.maxStock() - pack.minStock() + 1);
            }
            slots.add(new ShopSlot(pack, stockCount));
        }
        // Heavily-weighted (common) packs cluster toward the front - an
        // emergent "the first few slots are almost always the basics" look
        // without any hardcoded slot/tier rule.
        slots.sort((a, b) -> Double.compare(b.pack().shopWeight(), a.pack().shopWeight()));
        return slots;
    }
}
