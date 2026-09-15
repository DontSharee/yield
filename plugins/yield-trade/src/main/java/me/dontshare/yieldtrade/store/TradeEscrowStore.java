package me.dontshare.yieldtrade.store;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.item.ItemSerialization;
import org.bson.Document;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A live mirror of what each side of each in-progress trade is holding.
 * <p>
 * An offered item has already left its owner's inventory, so between being
 * offered and the trade ending it exists only in the server's memory. That's
 * fine for anything that fires an event - a disconnect, a close, a shutdown
 * all get a chance to hand it back - but a hard crash fires nothing at all,
 * and would take both offers with it. Mirroring here means
 * {@link #recoverOrphans} can find them on the next boot and turn them into
 * {@link TradeClaimStore} rows instead.
 * <p>
 * The mirror is written asynchronously, so a crash in the few milliseconds
 * between an item being offered and the write landing would still lose it.
 * That window is far smaller than the one this codebase already accepts
 * elsewhere (yield-core's player data autosaves on a two-minute timer), and
 * closing it completely would mean blocking the main thread on Mongo for
 * every single click.
 */
public final class TradeEscrowStore {

    private static final String COLLECTION = "tradeEscrow";

    private final MongoCollection<Document> collection;

    public TradeEscrowStore(DatabaseManager databaseManager) {
        this.collection = databaseManager.getCollection(COLLECTION);
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public void save(UUID sessionId, UUID firstId, List<ItemStack> firstOffer,
                     UUID secondId, List<ItemStack> secondOffer) {
        Document doc = new Document("_id", sessionId)
                .append("firstId", firstId)
                .append("firstOffer", serialize(firstOffer))
                .append("secondId", secondId)
                .append("secondOffer", serialize(secondOffer))
                .append("updatedAtMillis", System.currentTimeMillis());
        collection.replaceOne(Filters.eq("_id", sessionId), doc, new ReplaceOptions().upsert(true));
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public void delete(UUID sessionId) {
        collection.deleteOne(Filters.eq("_id", sessionId));
    }

    /**
     * Turns every escrow row left over from a previous run into claim rows
     * for both sides, then clears them. BLOCKING - call from
     * {@code DatabaseManager#supplyAsync} during startup.
     *
     * @return how many sessions were recovered
     */
    public int recoverOrphans(TradeClaimStore claimStore) {
        int recovered = 0;
        List<Document> rows = collection.find().into(new ArrayList<>());
        for (Document row : rows) {
            UUID sessionId = row.get("_id", UUID.class);
            claimStore.insert(row.get("firstId", UUID.class),
                    deserialize(row.getList("firstOffer", String.class)), "interrupted-trade");
            claimStore.insert(row.get("secondId", UUID.class),
                    deserialize(row.getList("secondOffer", String.class)), "interrupted-trade");
            collection.deleteOne(Filters.eq("_id", sessionId));
            recovered++;
        }
        return recovered;
    }

    private List<String> serialize(List<ItemStack> items) {
        List<String> out = new ArrayList<>();
        items.forEach(item -> out.add(ItemSerialization.serialize(item)));
        return out;
    }

    private List<ItemStack> deserialize(List<String> raw) {
        List<ItemStack> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String encoded : raw) {
            ItemStack item = ItemSerialization.deserialize(encoded);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }
}
