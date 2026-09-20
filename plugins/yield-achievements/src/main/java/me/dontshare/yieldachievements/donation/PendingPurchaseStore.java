package me.dontshare.yieldachievements.donation;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import me.dontshare.yieldcore.database.DatabaseManager;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Credits bought while the buyer was offline, waiting for them to log in.
 * <p>
 * This exists because of how webstores actually work: most Tebex purchases
 * happen on the website, not in game, so the player very often is not
 * online when the command fires. Granting into a profile that isn't loaded
 * would be silently erased by that profile's next save - the same trap
 * {@code TradeClaimStore} and the Auction House's Collection Box already
 * document - and the money is real, so losing one is not an option.
 * <p>
 * Keyed by lowercased NAME rather than UUID on purpose: resolving a name to
 * a UUID for an offline player means either a blocking Mojang lookup on the
 * main thread or a cache that may not have them, and the only thing Tebex
 * reliably hands over is the name they typed at checkout. Delivery is an
 * atomic {@code findOneAndDelete} per row, so a row is handed over exactly
 * once no matter how many join events race.
 */
public final class PendingPurchaseStore {

    private static final String COLLECTION = "pendingStorePurchases";

    public record Pending(long credits, String packageName) {
    }

    private final MongoCollection<Document> collection;

    public PendingPurchaseStore(DatabaseManager databaseManager) {
        this.collection = databaseManager.getCollection(COLLECTION);
        databaseManager.supplyAsync(() -> {
            collection.createIndex(Indexes.ascending("playerName"), new IndexOptions().background(true));
            return null;
        });
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public void queue(String playerName, long credits, String packageName) {
        collection.insertOne(new Document("_id", UUID.randomUUID())
                .append("playerName", playerName.toLowerCase(Locale.ROOT))
                .append("credits", credits)
                .append("packageName", packageName)
                .append("queuedAt", System.currentTimeMillis()));
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. Each row is removed as it is read, so it can only ever be delivered once. */
    public List<Pending> claimAll(String playerName) {
        List<Pending> claimed = new ArrayList<>();
        Document row;
        while ((row = collection.findOneAndDelete(Filters.eq("playerName", playerName.toLowerCase(Locale.ROOT)))) != null) {
            Long credits = row.getLong("credits");
            claimed.add(new Pending(credits != null ? credits : 0L, row.getString("packageName")));
        }
        return claimed;
    }
}
