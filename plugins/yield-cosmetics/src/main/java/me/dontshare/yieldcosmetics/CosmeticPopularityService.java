package me.dontshare.yieldcosmetics;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcosmetics.data.CosmeticCategory;
import me.dontshare.yieldcosmetics.data.CosmeticContentLoader.Content;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

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
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    public CosmeticPopularityService(JavaPlugin plugin, Supplier<Content> content, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.content = content;
        this.databaseManager = databaseManager;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 0L, REFRESH_INTERVAL_TICKS);
    }

    public int countFor(CosmeticCategory category, String cosmeticId) {
        return counts.getOrDefault(key(category, cosmeticId), 0);
    }

    private void refreshAll() {
        for (CosmeticCategory category : CosmeticCategory.values()) {
            for (String id : content.get().byCategory(category).keySet()) {
                databaseManager.supplyAsync(() -> count(category, id))
                        .thenAccept(result -> counts.put(key(category, id), result))
                        .exceptionally(ex -> {
                            plugin.getLogger().warning("Failed to refresh popularity for " + key(category, id) + ": " + ex.getMessage());
                            return null;
                        });
            }
        }
    }

    /** Runs off the main thread - see DatabaseManager's own Javadoc on why this must never run inline on a tick. */
    private int count(CosmeticCategory category, String id) {
        MongoCollection<Document> collection = databaseManager.getCollection("playerData");
        return (int) collection.countDocuments(Filters.eq(category.mongoField(), id));
    }

    private static String key(CosmeticCategory category, String id) {
        return category.name() + ":" + id;
    }
}
