package me.dontshare.yieldachievements.boost;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import me.dontshare.yieldachievements.potion.PotionStat;
import me.dontshare.yieldcore.database.DatabaseManager;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists live server boosts so a restart mid-event doesn't silently
 * swallow the rest of one.
 * <p>
 * A boost is a public promise - it was announced in chat with a duration,
 * and players plan around it - so dropping it on a restart would read as
 * the server taking something back. One document per boost, keyed by the
 * same {@link ServerBoost#key()} that decides stacking, so a restart
 * restores exactly what was running.
 */
public final class ServerBoostStore {

    private static final String COLLECTION = "serverBoosts";

    private final MongoCollection<Document> collection;

    public ServerBoostStore(DatabaseManager databaseManager) {
        this.collection = databaseManager.getCollection(COLLECTION);
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. Already-expired rows are dropped rather than returned. */
    public List<ServerBoost> loadActive() {
        List<ServerBoost> active = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Document doc : collection.find()) {
            long endsAt = doc.getLong("endsAtMillis") != null ? doc.getLong("endsAtMillis") : 0L;
            if (endsAt <= now) {
                continue;
            }
            PotionStat stat;
            try {
                stat = PotionStat.valueOf(doc.getString("stat"));
            } catch (IllegalArgumentException | NullPointerException e) {
                // A stat that no longer exists in the enum - skip it rather
                // than failing the whole restore.
                continue;
            }
            active.add(new ServerBoost(stat, doc.getDouble("multiplier"), endsAt,
                    doc.getLong("startedForSeconds") != null ? doc.getLong("startedForSeconds") : 0L));
        }
        return active;
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public void save(ServerBoost boost) {
        collection.replaceOne(Filters.eq("_id", boost.key()),
                new Document("_id", boost.key())
                        .append("stat", boost.stat().name())
                        .append("multiplier", boost.multiplier())
                        .append("endsAtMillis", boost.endsAtMillis())
                        .append("startedForSeconds", boost.startedForSeconds()),
                new ReplaceOptions().upsert(true));
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public void delete(String key) {
        collection.deleteOne(Filters.eq("_id", key));
    }
}
