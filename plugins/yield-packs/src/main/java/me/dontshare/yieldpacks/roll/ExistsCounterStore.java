package me.dontshare.yieldpacks.roll;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import me.dontshare.yieldcore.database.DatabaseManager;
import org.bson.Document;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Global (not per-player) mint counters for pets flagged track-exists in
 * packs.yml - "259.08K Exists" style scarcity stats. Bulk-loaded into memory
 * at startup for instant GUI reads, incremented locally then persisted async
 * via $inc-style upserts, same "cache now, persist async" shape as
 * PlayerDataStore.
 */
public final class ExistsCounterStore {

    private final DatabaseManager databaseManager;
    private final MongoCollection<Document> collection;
    private final Logger logger;
    private final ConcurrentHashMap<String, Long> counts = new ConcurrentHashMap<>();

    public ExistsCounterStore(DatabaseManager databaseManager, Logger logger) {
        this.databaseManager = databaseManager;
        this.collection = databaseManager.getCollection("packItemCounters");
        this.logger = logger;
    }

    public void loadAll() {
        databaseManager.supplyAsync(() -> {
            for (Document doc : collection.find()) {
                String itemId = doc.getString("_id");
                Long count = doc.getLong("count");
                if (itemId != null && count != null) {
                    counts.put(itemId, count);
                }
            }
            return null;
        });
    }

    public long get(String itemId) {
        return counts.getOrDefault(itemId, 0L);
    }

    public void increment(String itemId) {
        long updated = counts.merge(itemId, 1L, Long::sum);
        databaseManager.supplyAsync(() -> {
            try {
                collection.updateOne(Filters.eq("_id", itemId), Updates.set("count", updated), new UpdateOptions().upsert(true));
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to persist exists-counter for " + itemId, e);
            }
            return null;
        });
    }
}
