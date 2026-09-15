package me.dontshare.yieldtrade.currency;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import me.dontshare.yieldcore.database.DatabaseManager;
import org.bson.Document;

import java.math.BigInteger;
import java.util.UUID;

/**
 * The ledger of every currency note currently in circulation, keyed by the
 * note's own random id.
 * <p>
 * This exists because a bearer note is a far juicier duplication target than
 * any single item: it's fungible, and a duplicated one mints currency out of
 * nothing. yield-packs' own withdrawn-pet items trust their
 * PersistentDataContainer tags outright - whatever the item claims it is, it
 * is - so anything that manages to copy such an item (a duplication bug
 * elsewhere, a rollback, a mistaken {@code /give} of a copied stack) would be
 * credited twice. A note is instead only worth something while a matching row
 * exists here, and {@link #redeem} consumes that row with an atomic
 * {@code findOneAndDelete}: the second attempt against a copied note finds
 * nothing and is refused, no matter how the copy was made or how two redeem
 * packets are timed against each other.
 */
public final class CurrencyNoteStore {

    private static final String COLLECTION = "tradeCurrencyNotes";

    private final MongoCollection<Document> collection;

    public CurrencyNoteStore(DatabaseManager databaseManager) {
        this.collection = databaseManager.getCollection(COLLECTION);
        databaseManager.supplyAsync(() -> {
            collection.createIndex(Indexes.ascending("issuedTo"), new IndexOptions().background(true));
            return null;
        });
    }

    /** Records a freshly minted note as live. BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public void issue(UUID noteId, UUID issuedTo, TradeCurrency currency, BigInteger amount) {
        collection.insertOne(new Document("_id", noteId)
                .append("issuedTo", issuedTo)
                .append("currency", currency.name())
                .append("amount", amount.toString())
                .append("issuedAtMillis", System.currentTimeMillis()));
    }

    /**
     * Atomically consumes the note, returning what it was worth, or null if
     * no live row exists for it (already redeemed, or a copy of one that was).
     * BLOCKING - call from {@code DatabaseManager#supplyAsync}.
     */
    public Redeemed redeem(UUID noteId) {
        Document row = collection.findOneAndDelete(Filters.eq("_id", noteId));
        if (row == null) {
            return null;
        }
        return new Redeemed(
                TradeCurrency.valueOf(row.getString("currency")),
                new BigInteger(row.getString("amount")));
    }

    /**
     * Puts a consumed note back into circulation, for the one case where a
     * redemption is consumed but then can't be paid out (the player left
     * between the two steps) - without this the note's value would simply
     * vanish. BLOCKING - call from {@code DatabaseManager#supplyAsync}.
     */
    public void restore(UUID noteId, UUID issuedTo, TradeCurrency currency, BigInteger amount) {
        issue(noteId, issuedTo, currency, amount);
    }

    public record Redeemed(TradeCurrency currency, BigInteger amount) {
    }
}
