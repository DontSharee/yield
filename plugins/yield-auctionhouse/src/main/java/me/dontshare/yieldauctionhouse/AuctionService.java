package me.dontshare.yieldauctionhouse;

import me.dontshare.yieldauctionhouse.data.AuctionClaim;
import me.dontshare.yieldauctionhouse.data.AuctionConfig;
import me.dontshare.yieldauctionhouse.data.AuctionCurrency;
import me.dontshare.yieldauctionhouse.data.AuctionListing;
import me.dontshare.yieldauctionhouse.data.ClaimReason;
import me.dontshare.yieldauctionhouse.data.ListingStatus;
import me.dontshare.yieldauctionhouse.store.AuctionClaimStore;
import me.dontshare.yieldauctionhouse.store.AuctionListingStore;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.item.ItemSerialization;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fixed-price player marketplace - list whatever's in your main hand for
 * coins, diamonds, or credits, first buyer takes it. The auction house never
 * inspects what the item actually IS (a pet, a lootbox, a potion, anything
 * else) - it round-trips the exact {@link ItemStack} byte-for-byte (see
 * {@link ItemSerialization}), tags and all.
 * <p>
 * <b>Anti-dupe design</b> (this is the whole point of this class, not an
 * afterthought):
 * <ol>
 *   <li><b>Concurrent purchase.</b> Buying never reads-then-writes. It's one
 *   atomic {@code findOneAndUpdate} ({@link AuctionListingStore#claimForPurchase})
 *   keyed on the listing still being ACTIVE - two callers racing for the same
 *   listing can never both succeed, regardless of how their click packets are
 *   timed (deliberately including intentionally-delayed/replayed ones).</li>
 *   <li><b>Listing never touches a transaction at all.</b> The item is
 *   already physically removed from the seller's inventory (a plain Bukkit
 *   operation) before the listing document is ever inserted - unlike a v1 of
 *   this design that moved a pet between database documents, there is no
 *   second document that needs to agree with the listing anymore, so a
 *   single ordinary insert is already fully safe.</li>
 *   <li><b>Currency is never mutated inside a retriable transaction body.</b>
 *   The Mongo driver may silently re-run a transaction body on a transient
 *   error - safe for pure database writes (Mongo guarantees the failed
 *   attempt's writes are fully rolled back first) but NOT safe for a
 *   currency deduction sitting in a live Java object, which a retry would
 *   apply twice. So a buyer's payment always happens as its own single,
 *   ordinary main-thread step BEFORE the transaction starts, refunded on the
 *   main thread if the transaction subsequently reports the listing was no
 *   longer available. The transaction itself (claiming the listing + writing
 *   the resulting claims) only ever contains idempotent database operations.</li>
 *   <li><b>Nothing is ever handed over as a side effect of one click.</b> A
 *   sold listing's proceeds, a cancelled/expired listing's returned item, and
 *   even a buyer's own purchased item are ALWAYS delivered via a
 *   {@link AuctionClaim} row, claimed separately (its own atomic
 *   {@code findOneAndDelete}) through the Collection Box. This removes the
 *   entire class of "seller was offline so we wrote directly to their
 *   document, then their stale cached profile's next save silently erased
 *   the credit" bugs, and means a client trying to exploit packet timing
 *   around a single purchase click can, at absolute worst, fail to get
 *   charged for something that also never got claimed - never both.</li>
 *   <li><b>Per-player operation lock.</b> {@link #busyPlayers} rejects a
 *   second list/buy/cancel/claim call from the same player while one is
 *   already in flight, checked synchronously on the main thread before any
 *   I/O starts - closes off rapid-refire/delayed-duplicate click packets at
 *   the source, on top of (not instead of) the database-level guarantees
 *   above.</li>
 * </ol>
 */
public final class AuctionService {

    public enum ListResult {SUCCESS, BUSY, NOTHING_HELD, PRICE_TOO_LOW, LISTING_CAP_REACHED, FAILED}

    public enum PurchaseResult {SUCCESS, BUSY, NO_LONGER_AVAILABLE, INSUFFICIENT_FUNDS, OWN_LISTING, FAILED}

    public enum CancelResult {SUCCESS, BUSY, NOT_FOUND_OR_NOT_YOURS, FAILED}

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final AuctionListingStore listingStore;
    private final AuctionClaimStore claimStore;
    private final YieldPacks packs;
    private final Supplier<AuctionConfig> config;

    /** Players with a list/buy/cancel/claim currently in flight - see class Javadoc point 5. */
    private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();

    public AuctionService(JavaPlugin plugin, DatabaseManager databaseManager, AuctionListingStore listingStore,
                           AuctionClaimStore claimStore, YieldPacks packs, Supplier<AuctionConfig> config) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.listingStore = listingStore;
        this.claimStore = claimStore;
        this.packs = packs;
        this.config = config;
    }

    public boolean isBusy(Player player) {
        return busyPlayers.contains(player.getUniqueId());
    }

    private boolean markBusy(Player player) {
        return busyPlayers.add(player.getUniqueId());
    }

    private void clearBusy(Player player) {
        busyPlayers.remove(player.getUniqueId());
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    // ---------------------------------------------------------------- List

    /** Lists whatever's currently in {@code seller}'s main hand - the entire stack, exactly as it is (tags included). */
    public CompletableFuture<ListResult> listHeldItem(Player seller, AuctionCurrency currency, BigInteger price) {
        if (!markBusy(seller)) {
            return CompletableFuture.completedFuture(ListResult.BUSY);
        }
        AuctionConfig cfg = config.get();
        if (price.compareTo(BigInteger.valueOf(cfg.minimumPrice())) < 0) {
            clearBusy(seller);
            return CompletableFuture.completedFuture(ListResult.PRICE_TOO_LOW);
        }
        ItemStack held = seller.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            clearBusy(seller);
            return CompletableFuture.completedFuture(ListResult.NOTHING_HELD);
        }
        ItemStack toList = held.clone();
        seller.getInventory().setItemInMainHand(null);

        UUID sellerId = seller.getUniqueId();
        String sellerName = seller.getName();
        long now = System.currentTimeMillis();
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), sellerId, sellerName,
                ItemSerialization.serialize(toList), currency, price, ListingStatus.ACTIVE, now, now + cfg.listingDurationMillis(), null);

        return databaseManager.supplyAsync(() -> {
            if (listingStore.countActiveBy(sellerId) >= cfg.maxActiveListingsPerPlayer()) {
                return ListResult.LISTING_CAP_REACHED;
            }
            listingStore.insert(listing);
            return ListResult.SUCCESS;
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to list an item for " + sellerName + ": " + ex.getMessage());
            return ListResult.FAILED;
        }).whenComplete((result, ex) -> runSync(() -> {
            if (result != ListResult.SUCCESS) {
                giveBack(seller, toList);
            }
            clearBusy(seller);
        }));
    }

    private void giveBack(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    // ------------------------------------------------------------ Purchase

    public CompletableFuture<PurchaseResult> purchase(Player buyer, AuctionListing listing) {
        if (!markBusy(buyer)) {
            return CompletableFuture.completedFuture(PurchaseResult.BUSY);
        }
        UUID buyerId = buyer.getUniqueId();
        if (listing.sellerId().equals(buyerId)) {
            clearBusy(buyer);
            return CompletableFuture.completedFuture(PurchaseResult.OWN_LISTING);
        }
        PackPlayerProfile buyerProfile = packs.getPlayerStore().getCached(buyerId);
        if (buyerProfile == null) {
            clearBusy(buyer);
            return CompletableFuture.completedFuture(PurchaseResult.FAILED);
        }
        // Payment happens exactly once, here, on the main thread, BEFORE the
        // transaction starts - see class Javadoc point 3 for why this can
        // never move inside the transaction body.
        if (!deduct(buyerProfile, listing.currency(), listing.price())) {
            clearBusy(buyer);
            return CompletableFuture.completedFuture(PurchaseResult.INSUFFICIENT_FUNDS);
        }
        packs.getPlayerStore().save(buyerId);

        String buyerName = buyer.getName();
        return databaseManager.supplyAsync(() -> databaseManager.withTransaction(session -> {
            AuctionListing claimed = listingStore.claimForPurchase(session, listing.id(), buyerId, System.currentTimeMillis());
            if (claimed == null) {
                return null;
            }
            claimStore.insert(session, new AuctionClaim(UUID.randomUUID(), buyerId, ClaimReason.PURCHASE,
                    null, null, claimed.serializedItem(), System.currentTimeMillis()));
            BigInteger tax = taxOf(claimed.price(), config.get().saleTaxPercent());
            BigInteger net = claimed.price().subtract(tax);
            claimStore.insert(session, new AuctionClaim(UUID.randomUUID(), claimed.sellerId(), ClaimReason.SALE_PROCEEDS,
                    claimed.currency(), net, null, System.currentTimeMillis()));
            return claimed;
        })).exceptionally(ex -> {
            plugin.getLogger().warning("Purchase transaction failed for " + buyerName + " on listing " + listing.id() + ": " + ex.getMessage());
            return null;
        }).thenApply(claimed -> claimed != null ? PurchaseResult.SUCCESS : PurchaseResult.NO_LONGER_AVAILABLE)
        .whenComplete((result, ex) -> runSync(() -> {
            if (result != PurchaseResult.SUCCESS) {
                // Refund - the purchase never actually went through.
                PackPlayerProfile p = packs.getPlayerStore().getCached(buyerId);
                if (p != null) {
                    refund(p, listing.currency(), listing.price());
                    packs.getPlayerStore().save(buyerId);
                }
            }
            clearBusy(buyer);
        }));
    }

    private boolean deduct(PackPlayerProfile profile, AuctionCurrency currency, BigInteger amount) {
        BigInteger balance = balanceOf(profile, currency);
        if (balance.compareTo(amount) < 0) {
            return false;
        }
        setBalance(profile, currency, balance.subtract(amount));
        return true;
    }

    private void refund(PackPlayerProfile profile, AuctionCurrency currency, BigInteger amount) {
        setBalance(profile, currency, balanceOf(profile, currency).add(amount));
    }

    private BigInteger balanceOf(PackPlayerProfile profile, AuctionCurrency currency) {
        return switch (currency) {
            case COINS -> profile.getCoins();
            case DIAMONDS -> profile.getDiamonds();
            case CREDITS -> profile.getCredits();
        };
    }

    private void setBalance(PackPlayerProfile profile, AuctionCurrency currency, BigInteger value) {
        switch (currency) {
            case COINS -> profile.setCoins(value);
            case DIAMONDS -> profile.setDiamonds(value);
            case CREDITS -> profile.setCredits(value);
        }
    }

    private BigInteger taxOf(BigInteger price, double percent) {
        long basisPoints = Math.round(percent * 100);
        return price.multiply(BigInteger.valueOf(basisPoints)).divide(BigInteger.valueOf(10_000));
    }

    // ------------------------------------------------------------- Cancel

    public CompletableFuture<CancelResult> cancel(Player seller, UUID listingId) {
        if (!markBusy(seller)) {
            return CompletableFuture.completedFuture(CancelResult.BUSY);
        }
        UUID sellerId = seller.getUniqueId();
        return databaseManager.supplyAsync(() -> databaseManager.withTransaction(session -> {
            AuctionListing listing = listingStore.claimForCancel(session, listingId, sellerId);
            if (listing == null) {
                return null;
            }
            claimStore.insert(session, new AuctionClaim(UUID.randomUUID(), sellerId, ClaimReason.LISTING_RETURNED,
                    null, null, listing.serializedItem(), System.currentTimeMillis()));
            return listing;
        })).exceptionally(ex -> {
            plugin.getLogger().warning("Cancel transaction failed for " + seller.getName() + " on listing " + listingId + ": " + ex.getMessage());
            return null;
        }).thenApply(listing -> listing != null ? CancelResult.SUCCESS : CancelResult.NOT_FOUND_OR_NOT_YOURS)
        .whenComplete((result, ex) -> runSync(() -> clearBusy(seller)));
    }

    // -------------------------------------------------------------- Claim

    /** Delivers exactly one pending claim - see class Javadoc point 4. Returns false if it no longer exists (already claimed, e.g. by a racing duplicate click). */
    public CompletableFuture<Boolean> claim(Player player, UUID claimId) {
        if (!markBusy(player)) {
            return CompletableFuture.completedFuture(false);
        }
        UUID ownerId = player.getUniqueId();
        return databaseManager.supplyAsync(() -> claimStore.claimOne(claimId, ownerId))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Claim failed for " + player.getName() + " on claim " + claimId + ": " + ex.getMessage());
                    return null;
                })
                .thenApply(claimResult -> {
                    runSync(() -> {
                        if (claimResult != null) {
                            deliver(player, claimResult);
                        }
                        clearBusy(player);
                    });
                    return claimResult != null;
                });
    }

    public CompletableFuture<Integer> claimAll(Player player) {
        UUID ownerId = player.getUniqueId();
        return databaseManager.supplyAsync(() -> claimStore.findFor(ownerId))
                .thenCompose(claims -> {
                    CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
                    for (AuctionClaim pending : claims) {
                        chain = chain.thenCompose(count -> claim(player, pending.id()).thenApply(ok -> ok ? count + 1 : count));
                    }
                    return chain;
                });
    }

    /** Applies one already-atomically-claimed row to the live player - MUST only ever be called on the main thread with a claim {@link AuctionClaimStore#claimOne} has already removed from the database, never speculatively. */
    private void deliver(Player player, AuctionClaim claimed) {
        if (claimed.isCurrency()) {
            PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
            refund(profile, claimed.currency(), claimed.amount());
            packs.getPlayerStore().save(player.getUniqueId());
            return;
        }
        ItemStack item = ItemSerialization.deserialize(claimed.serializedItem());
        if (item != null) {
            giveBack(player, item);
        }
    }

    public List<AuctionClaim> pendingClaimsFor(Player player) {
        return claimStore.findFor(player.getUniqueId());
    }

    // ------------------------------------------------------------- Expiry

    /** Called periodically by {@code YieldAuctionHouse}'s scheduled sweep - discovery (a plain, possibly-stale read) is safe because the actual state change is still the same atomic per-listing claim used everywhere else. */
    public void runExpirySweep() {
        databaseManager.supplyAsync(() -> listingStore.findExpiredCandidates(System.currentTimeMillis()))
                .thenAccept(candidates -> {
                    for (AuctionListing candidate : candidates) {
                        databaseManager.supplyAsync(() -> databaseManager.withTransaction(session -> {
                            AuctionListing listing = listingStore.claimForExpiry(session, candidate.id());
                            if (listing != null) {
                                claimStore.insert(session, new AuctionClaim(UUID.randomUUID(), listing.sellerId(), ClaimReason.LISTING_RETURNED,
                                        null, null, listing.serializedItem(), System.currentTimeMillis()));
                            }
                            return null;
                        })).exceptionally(ex -> {
                            plugin.getLogger().warning("Expiry sweep failed for listing " + candidate.id() + ": " + ex.getMessage());
                            return null;
                        });
                    }
                });
    }
}
