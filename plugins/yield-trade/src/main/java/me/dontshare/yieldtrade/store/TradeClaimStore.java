package me.dontshare.yieldtrade.store;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.item.ItemSerialization;
import org.bson.Document;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Items owed back to a player who wasn't around to receive them - a trade
 * that ended while they were offline, or one the server was still holding
 * when it stopped.
 * <p>
 * Modelled on yield-auctionhouse's Collection Box for the same reason it
 * exists there: the alternative, writing into a cached profile belonging to
 * an offline player, can be silently erased by that profile's next save.
 * Delivery is an atomic {@code findOneAndDelete} per row, so a row is handed
 * over exactly once no matter how many join events or racing clicks arrive.
 */
public final class TradeClaimStore {

    private static final String COLLECTION = "tradeClaims";

    private final MongoCollection<Document> collection;

    public TradeClaimStore(DatabaseManager databaseManager) {
        this.collection = databaseManager.getCollection(COLLECTION);
        databaseManager.supplyAsync(() -> {
            collection.createIndex(Indexes.ascending("ownerId"), new IndexOptions().background(true));
            return null;
        });
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public void insert(UUID ownerId, List<ItemStack> items, String reason) {
        List<Document> rows = new ArrayList<>();
        for (ItemStack item : items) {
            rows.add(new Document("_id", UUID.randomUUID())
                    .append("ownerId", ownerId)
                    .append("item", ItemSerialization.serialize(item))
                    .append("reason", reason)
                    .append("createdAtMillis", System.currentTimeMillis()));
        }
        if (!rows.isEmpty()) {
            collection.insertMany(rows);
        }
    }

    /** Every row id currently owed to this player. BLOCKING. */
    public List<UUID> findIdsFor(UUID ownerId) {
        List<UUID> ids = new ArrayList<>();
        try (var cursor = collection.find(Filters.eq("ownerId", ownerId)).cursor()) {
            while (cursor.hasNext()) {
                ids.add(cursor.next().get("_id", UUID.class));
            }
        }
        return ids;
    }

    /** Atomically takes one row, returning its item, or null if it was already taken. BLOCKING. */
    public ItemStack claimOne(UUID claimId, UUID ownerId) {
        Document row = collection.findOneAndDelete(
                Filters.and(Filters.eq("_id", claimId), Filters.eq("ownerId", ownerId)));
        return row != null ? ItemSerialization.deserialize(row.getString("item")) : null;
    }
}
