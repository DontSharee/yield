package me.dontshare.yieldauctionhouse.store;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import me.dontshare.yieldauctionhouse.data.AuctionCurrency;
import me.dontshare.yieldauctionhouse.data.AuctionListing;
import me.dontshare.yieldauctionhouse.data.ListingStatus;
import me.dontshare.yieldcore.database.DatabaseManager;
import org.bson.Document;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Raw Mongo access for the {@code auctionListings} collection - deliberately
 * NOT built on {@code PlayerDataStore} (that class is per-player-document;
 * this collection is one document per LISTING, queried/filtered across
 * every player at once for the browse screen). Every state transition
 * (SOLD/CANCELLED/EXPIRED) goes through one of the {@code claimFor*} methods
 * below, each a single atomic {@code findOneAndUpdate} keyed on the
 * listing's CURRENT status - this is what makes a listing impossible to
 * sell/cancel/expire twice, no matter how many callers race for it or how
 * the calling packets are timed (see {@code AuctionService}'s own Javadoc
 * for the full anti-dupe design this collection is the foundation of).
 * <p>
 * Creating a listing is a single, session-free insert - unlike selling/
 * cancelling, listing never touches any other document (the item was
 * already physically removed from the seller's inventory before this is
 * ever called), so there's no multi-document consistency need here at all.
 */
public final class AuctionListingStore {

    private static final String COLLECTION = "auctionListings";

    private final MongoCollection<Document> collection;

    public AuctionListingStore(DatabaseManager databaseManager) {
        this.collection = databaseManager.getCollection(COLLECTION);
        // Safe to call every boot - a no-op once the index already exists
        // with this exact spec. Fired once at startup, not awaited.
        databaseManager.supplyAsync(() -> {
            collection.createIndex(Indexes.ascending("status"), new IndexOptions().background(true));
            collection.createIndex(Indexes.ascending("sellerId", "status"), new IndexOptions().background(true));
            return null;
        });
    }

    public void insert(AuctionListing listing) {
        collection.insertOne(toDocument(listing));
    }

    /** Every currently-ACTIVE listing, newest first - the browse screen's shared, periodically-refreshed snapshot (see AuctionService). Read-only, no session needed - browsing doesn't need transactional consistency, only the actual purchase does. */
    public List<AuctionListing> findActive() {
        List<AuctionListing> result = new ArrayList<>();
        try (var cursor = collection.find(Filters.eq("status", ListingStatus.ACTIVE.name()))
                .sort(Sorts.descending("listedAtMillis")).limit(2000).cursor()) {
            while (cursor.hasNext()) {
                result.add(fromDocument(cursor.next()));
            }
        }
        return result;
    }

    /** Every ACTIVE listing owned by {@code sellerId} - for "My Listings". */
    public List<AuctionListing> findActiveBy(UUID sellerId) {
        List<AuctionListing> result = new ArrayList<>();
        try (var cursor = collection.find(Filters.and(Filters.eq("sellerId", sellerId), Filters.eq("status", ListingStatus.ACTIVE.name()))).cursor()) {
            while (cursor.hasNext()) {
                result.add(fromDocument(cursor.next()));
            }
        }
        return result;
    }

    public int countActiveBy(UUID sellerId) {
        return (int) collection.countDocuments(Filters.and(Filters.eq("sellerId", sellerId), Filters.eq("status", ListingStatus.ACTIVE.name())));
    }

    /** Every ACTIVE listing already past its expiry - discovery only for the sweep; the actual transition is still the atomic {@link #claimForExpiry} per listing, so a stale read here can never cause a double-expire. */
    public List<AuctionListing> findExpiredCandidates(long nowMillis) {
        List<AuctionListing> result = new ArrayList<>();
        try (var cursor = collection.find(Filters.and(Filters.eq("status", ListingStatus.ACTIVE.name()), Filters.lte("expiresAtMillis", nowMillis))).cursor()) {
            while (cursor.hasNext()) {
                result.add(fromDocument(cursor.next()));
            }
        }
        return result;
    }

    /** Atomically claims {@code listingId} for purchase - flips ACTIVE -> SOLD and stamps the buyer, but ONLY if it's still ACTIVE. Returns the listing as it was immediately before the update (so the caller has the price/item to act on), or null if someone else already bought/cancelled it first - there is no window where two callers can both receive a non-null result for the same listing. */
    public AuctionListing claimForPurchase(ClientSession session, UUID listingId, UUID buyerId, long nowMillis) {
        Document result = collection.findOneAndUpdate(session,
                Filters.and(Filters.eq("_id", listingId), Filters.eq("status", ListingStatus.ACTIVE.name())),
                Updates.combine(
                        Updates.set("status", ListingStatus.SOLD.name()),
                        Updates.set("buyerId", buyerId),
                        Updates.set("soldAtMillis", nowMillis)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
        return result != null ? fromDocument(result) : null;
    }

    /** Same atomic shape as {@link #claimForPurchase}, but only succeeds if {@code sellerId} actually owns the listing - the "cancel" click. */
    public AuctionListing claimForCancel(ClientSession session, UUID listingId, UUID sellerId) {
        Document result = collection.findOneAndUpdate(session,
                Filters.and(Filters.eq("_id", listingId), Filters.eq("sellerId", sellerId), Filters.eq("status", ListingStatus.ACTIVE.name())),
                Updates.set("status", ListingStatus.CANCELLED.name()),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
        return result != null ? fromDocument(result) : null;
    }

    /** Same atomic shape again, for the expiry sweep - no seller check needed since the sweep already only iterates that seller's own listing id. */
    public AuctionListing claimForExpiry(ClientSession session, UUID listingId) {
        Document result = collection.findOneAndUpdate(session,
                Filters.and(Filters.eq("_id", listingId), Filters.eq("status", ListingStatus.ACTIVE.name())),
                Updates.set("status", ListingStatus.EXPIRED.name()),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
        return result != null ? fromDocument(result) : null;
    }

    private Document toDocument(AuctionListing listing) {
        return new Document("_id", listing.id())
                .append("sellerId", listing.sellerId())
                .append("sellerName", listing.sellerName())
                .append("item", listing.serializedItem())
                .append("currency", listing.currency().name())
                .append("price", listing.price().toString())
                .append("status", listing.status().name())
                .append("listedAtMillis", listing.listedAtMillis())
                .append("expiresAtMillis", listing.expiresAtMillis())
                .append("buyerId", listing.buyerId());
    }

    private AuctionListing fromDocument(Document doc) {
        return new AuctionListing(
                doc.get("_id", UUID.class),
                doc.get("sellerId", UUID.class),
                doc.getString("sellerName"),
                doc.getString("item"),
                AuctionCurrency.fromStored(doc.getString("currency")),
                new BigInteger(doc.getString("price")),
                ListingStatus.valueOf(doc.getString("status")),
                doc.getLong("listedAtMillis"),
                doc.getLong("expiresAtMillis"),
                doc.get("buyerId", UUID.class));
    }
}
