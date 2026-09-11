package me.dontshare.yieldauctionhouse.store;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import me.dontshare.yieldauctionhouse.data.AuctionClaim;
import me.dontshare.yieldauctionhouse.data.AuctionCurrency;
import me.dontshare.yieldauctionhouse.data.ClaimReason;
import me.dontshare.yieldcore.database.DatabaseManager;
import org.bson.Document;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Raw Mongo access for the {@code auctionClaims} collection - the Collection
 * Box's own backing store. Every delivery this system ever makes (sale
 * proceeds, a returned listing, a bought item) is inserted here as part of
 * the SAME transaction that changed the triggering listing's status, and is
 * only ever consumed via {@link #claimOne}'s atomic
 * {@code findOneAndDelete} - a claim can be collected exactly once, and (since
 * it's a delete, not a status flip) can never be "seen" twice even by a
 * client racing repeated click packets against itself.
 */
public final class AuctionClaimStore {

    private static final String COLLECTION = "auctionClaims";

    private final MongoCollection<Document> collection;

    public AuctionClaimStore(DatabaseManager databaseManager) {
        this.collection = databaseManager.getCollection(COLLECTION);
        databaseManager.supplyAsync(() -> {
            collection.createIndex(Indexes.ascending("ownerId"), new IndexOptions().background(true));
            return null;
        });
    }

    public void insert(ClientSession session, AuctionClaim claim) {
        collection.insertOne(session, toDocument(claim));
    }

    public List<AuctionClaim> findFor(UUID ownerId) {
        List<AuctionClaim> result = new ArrayList<>();
        try (var cursor = collection.find(Filters.eq("ownerId", ownerId)).cursor()) {
            while (cursor.hasNext()) {
                result.add(fromDocument(cursor.next()));
            }
        }
        return result;
    }

    /** Atomically removes exactly one claim, but only if it's still owned by {@code ownerId} - returns it (for the caller to actually deliver), or null if it was already claimed (e.g. a second, racing click packet) or never belonged to this player. */
    public AuctionClaim claimOne(UUID claimId, UUID ownerId) {
        Document result = collection.findOneAndDelete(Filters.and(Filters.eq("_id", claimId), Filters.eq("ownerId", ownerId)));
        return result != null ? fromDocument(result) : null;
    }

    private Document toDocument(AuctionClaim claim) {
        Document doc = new Document("_id", claim.id())
                .append("ownerId", claim.ownerId())
                .append("reason", claim.reason().name())
                .append("createdAtMillis", claim.createdAtMillis());
        if (claim.isCurrency()) {
            doc.append("currency", claim.currency().name()).append("amount", claim.amount().toString());
        } else {
            doc.append("item", claim.serializedItem());
        }
        return doc;
    }

    private AuctionClaim fromDocument(Document doc) {
        String currencyRaw = doc.getString("currency");
        return new AuctionClaim(
                doc.get("_id", UUID.class),
                doc.get("ownerId", UUID.class),
                ClaimReason.valueOf(doc.getString("reason")),
                currencyRaw != null ? AuctionCurrency.valueOf(currencyRaw) : null,
                doc.getString("amount") != null ? new BigInteger(doc.getString("amount")) : null,
                doc.getString("item"),
                doc.getLong("createdAtMillis"));
    }
}
