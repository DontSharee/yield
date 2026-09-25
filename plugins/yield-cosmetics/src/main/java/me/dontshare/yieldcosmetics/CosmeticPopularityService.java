package me.dontshare.yieldcosmetics;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcosmetics.data.CosmeticCategory;
import me.dontshare.yieldcosmetics.data.CosmeticContentLoader.Content;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Background "X players have this" counts for every configured cosmetic -
 * one cheap async Mongo {@code countDocuments} per (category, cosmetic id)
 * on the same off-thread idiom {@code yield-leaderboards}' LeaderboardService
 * already establishes, refreshed on a timer rather than per-GUI-open.
 */
public final class CosmeticPopularityService {

    private static final long REFRESH_INTERVAL_TICKS = 20L * 60 * 2; // 2 minutes

    private final JavaPlugin plugin;
    private final Supplier<Content> content;
    private final DatabaseManager databaseManager;
    private volatile Map<String, Integer> counts = new ConcurrentHashMap<>();

    public CosmeticPopularityService(JavaPlugin plugin, Supplier<Content> content, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.content = content;
        this.databaseManager = databaseManager;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, me.dontshare.yieldcore.perf.PerfTracker.timed("cosmetics.popularity", this::refreshAll), 0L, REFRESH_INTERVAL_TICKS);
    }

    public int countFor(CosmeticCategory category, String cosmeticId) {
        return counts.getOrDefault(key(category, cosmeticId), 0);
    }

    /**
     * One grouped pass per category, rather than one count per cosmetic.
     * <p>
     * Counting each cosmetic separately meant a query per configured
     * cosmetic - well over a hundred - and none of the fields involved is
     * indexed, so every one of them was a full scan of a collection that
     * grows with the server's lifetime player count. All of it landed on the
     * database pool that player saves and logins share, every two minutes.
     * A {@code $group} answers the whole category in a single pass instead.
     */
    private void refreshAll() {
        databaseManager.supplyAsync(this::countAll)
                .thenAccept(fresh -> {
                    // Swapped wholesale rather than cleared and refilled, so a
                    // reader never sees a half-populated map.
                    counts = fresh;
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to refresh cosmetic popularity: " + ex.getMessage());
                    return null;
                });
    }

    /** Runs off the main thread - see DatabaseManager's own Javadoc on why this must never run inline on a tick. */
    private Map<String, Integer> countAll() {
        MongoCollection<Document> collection = databaseManager.getCollection("playerData");
        Map<String, Integer> fresh = new ConcurrentHashMap<>();
        for (CosmeticCategory category : CosmeticCategory.values()) {
            for (Document row : collection.aggregate(List.of(
                    Aggregates.match(Filters.ne(category.mongoField(), null)),
                    Aggregates.group("$" + category.mongoField(), Accumulators.sum("count", 1))))) {
                if (row.get("_id") instanceof String cosmeticId) {
                    fresh.put(key(category, cosmeticId), row.getInteger("count", 0));
                }
            }
        }
        return fresh;
    }

    private static String key(CosmeticCategory category, String id) {
        return category.name() + ":" + id;
    }
}
