package me.dontshare.yieldachievements.donation;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import me.dontshare.yieldcore.database.DatabaseManager;
import org.bson.Document;

/**
 * The server's lifetime donation progress - one document, because this is
 * one number for the whole server rather than anything per-player.
 * <p>
 * {@code goalsCompleted} is the source of truth for the permanent
 * multiplier, not the multiplier itself. Storing the count and deriving
 * {@code 1.0 + goals * 0.1} means changing the reward per goal later
 * retroactively fixes every server that already banked one, instead of
 * leaving a frozen number nobody can explain.
 */
public final class DonationGoalStore {

    private static final String COLLECTION = "donationGoal";
    private static final String DOCUMENT_ID = "global";

    /** What the store's own prices imply: VIP is 499, Celestial 5,999, gamepasses 1,000 - those are cents, so a credit is a cent. */
    public static final long CREDITS_PER_DOLLAR = 100L;
    /** $100, in credits. */
    public static final long CREDITS_PER_GOAL = 100L * CREDITS_PER_DOLLAR;
    /** What each completed goal permanently adds to the server-wide coin multiplier. */
    public static final double MULTIPLIER_PER_GOAL = 0.1;

    /** {@code creditsTowardGoal} is what is left over after the last goal completed - it is reset, not cumulative. */
    public record Progress(long creditsTowardGoal, long goalsCompleted, long lifetimeCredits) {

        public static final Progress EMPTY = new Progress(0, 0, 0);

        /** The permanent, server-wide coin multiplier this progress has bought - never goes down. */
        public double permanentMultiplier() {
            return 1.0 + goalsCompleted * MULTIPLIER_PER_GOAL;
        }

        public double dollarsTowardGoal() {
            return (double) creditsTowardGoal / CREDITS_PER_DOLLAR;
        }

        public float goalProgress() {
            return Math.max(0f, Math.min(1f, (float) creditsTowardGoal / (float) CREDITS_PER_GOAL));
        }
    }

    private final MongoCollection<Document> collection;

    public DonationGoalStore(DatabaseManager databaseManager) {
        this.collection = databaseManager.getCollection(COLLECTION);
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public Progress load() {
        Document doc = collection.find(Filters.eq("_id", DOCUMENT_ID)).first();
        if (doc == null) {
            return Progress.EMPTY;
        }
        return new Progress(
                doc.getLong("creditsTowardGoal") != null ? doc.getLong("creditsTowardGoal") : 0L,
                doc.getLong("goalsCompleted") != null ? doc.getLong("goalsCompleted") : 0L,
                doc.getLong("lifetimeCredits") != null ? doc.getLong("lifetimeCredits") : 0L);
    }

    /** BLOCKING - call from {@code DatabaseManager#supplyAsync}. */
    public void save(Progress progress) {
        collection.replaceOne(Filters.eq("_id", DOCUMENT_ID),
                new Document("_id", DOCUMENT_ID)
                        .append("creditsTowardGoal", progress.creditsTowardGoal())
                        .append("goalsCompleted", progress.goalsCompleted())
                        .append("lifetimeCredits", progress.lifetimeCredits()),
                new ReplaceOptions().upsert(true));
    }
}
