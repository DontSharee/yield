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
    /** Mints not yet written - flushed as $inc every few seconds (see {@link #start}). */
    private final ConcurrentHashMap<String, Long> pending = new ConcurrentHashMap<>();
    private static final long FLUSH_INTERVAL_TICKS = 100L;

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
                    // Added, not put: anything minted before this load
                    // finished is already counted in memory.
                    counts.merge(itemId, count, Long::sum);
                }
            }
            return null;
        });
    }

    public long get(String itemId) {
        return counts.getOrDefault(itemId, 0L);
    }

    /**
     * Counts one mint now, persists it with the next flush.
     * <p>
     * This used to write {@code count = newTotal} on every single hatch of a
     * tracked pet - one database write per egg, onto a pool with several
     * threads, where an older, lower total finishing last would set the
     * stored count backwards. Deltas are batched and written as {@code $inc},
     * which doesn't care what order they land in.
     */
    public void increment(String itemId) {
        counts.merge(itemId, 1L, Long::sum);
        pending.merge(itemId, 1L, Long::sum);
    }

    public void start(org.bukkit.plugin.java.JavaPlugin plugin) {
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin,
                () -> databaseManager.supplyAsync(() -> {
                    flush();
                    return null;
                }), FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
    }

    /** Writes every pending delta. Blocking - call it off the main thread, or from onDisable. */
    public void flush() {
        for (String itemId : java.util.List.copyOf(pending.keySet())) {
            Long delta = pending.remove(itemId);
            if (delta == null || delta <= 0) {
                continue;
            }
            try {
                collection.updateOne(Filters.eq("_id", itemId), Updates.inc("count", delta), new UpdateOptions().upsert(true));
            } catch (Exception e) {
                // Put back for the next flush rather than losing the mints.
                pending.merge(itemId, delta, Long::sum);
                logger.log(Level.WARNING, "Failed to persist exists-counter for " + itemId, e);
            }
        }
    }
}
