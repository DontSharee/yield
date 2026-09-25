package me.dontshare.yieldteams;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldteams.data.Team;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every team, fully cached in memory (there are orders of magnitude fewer
 * teams than players, so loading all of them at startup - rather than a
 * per-player lazy load like {@code PlayerDataStore} - is simple and cheap).
 * Backed by its own top-level "teams" MongoDB collection, keyed by the
 * team's own id - not a sub-field on any player's document, since a team
 * isn't owned by a single player.
 */
public final class TeamStore {

    private final DatabaseManager databaseManager;
    private final MongoCollection<Team> collection;
    private final Logger logger;

    private final Map<UUID, Team> cache = new ConcurrentHashMap<>();
    private final Map<String, UUID> idByLowercaseName = new ConcurrentHashMap<>();

    public TeamStore(DatabaseManager databaseManager, Logger logger) {
        this.databaseManager = databaseManager;
        this.collection = databaseManager.getCollection("teams", Team.class);
        this.logger = logger;
    }

    public CompletableFuture<Void> loadAll() {
        return databaseManager.supplyAsync(() -> {
            for (Team team : collection.find()) {
                cache.put(team.getId(), team);
                idByLowercaseName.put(team.getName().toLowerCase(Locale.ROOT), team.getId());
            }
            return null;
        });
    }

    public Team get(UUID teamId) {
        return teamId == null ? null : cache.get(teamId);
    }

    public Team findByName(String name) {
        UUID id = idByLowercaseName.get(name.toLowerCase(Locale.ROOT));
        return id != null ? cache.get(id) : null;
    }

    public boolean nameTaken(String name) {
        return idByLowercaseName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Iterable<Team> all() {
        return cache.values();
    }

    /** Registers a brand-new team in the cache/name-index and persists it. */
    public void create(Team team) {
        cache.put(team.getId(), team);
        idByLowercaseName.put(team.getName().toLowerCase(Locale.ROOT), team.getId());
        save(team);
    }

    public void delete(Team team) {
        cache.remove(team.getId());
        idByLowercaseName.remove(team.getName().toLowerCase(Locale.ROOT));
        UUID id = team.getId();
        enqueue(id, () -> collection.deleteOne(Filters.eq("_id", id)), "delete team " + team.getName());
    }

    /**
     * Call after mutating an already-cached Team object - the cache already
     * reflects the change, this just persists it.
     * <p>
     * The team is serialized HERE, on the caller's (main) thread, and the
     * finished document handed over: encoding the live object on a database
     * thread raced every later mutation of it (a torn or failed save). Each
     * team's writes also run strictly one after another - the pool has
     * several threads, and an older snapshot landing last would overwrite a
     * newer one.
     */
    public void save(Team team) {
        UUID id = team.getId();
        org.bson.BsonDocument snapshot = new org.bson.BsonDocument();
        try (org.bson.BsonDocumentWriter writer = new org.bson.BsonDocumentWriter(snapshot)) {
            collection.getCodecRegistry().get(Team.class).encode(writer, team,
                    org.bson.codecs.EncoderContext.builder().build());
        }
        var raw = collection.withDocumentClass(org.bson.BsonDocument.class);
        enqueue(id, () -> raw.replaceOne(Filters.eq("_id", id), snapshot, new ReplaceOptions().upsert(true)),
                "save team " + team.getName());
    }

    /** Each team's pending database work, so the next write waits for the last. */
    private final Map<UUID, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    private void enqueue(UUID teamId, Runnable work, String what) {
        CompletableFuture<Void> prior = inFlight.get(teamId);
        java.util.function.Supplier<CompletableFuture<Void>> run = () -> databaseManager.supplyAsync(() -> {
            work.run();
            return (Void) null;
        });
        CompletableFuture<Void> future = prior == null ? run.get()
                : prior.handle((ignored, error) -> null).thenCompose(ignored -> run.get());
        inFlight.put(teamId, future);
        future.whenComplete((ignored, error) -> {
            inFlight.remove(teamId, future);
            if (error != null) {
                logger.log(Level.SEVERE, "Failed to " + what, error);
            }
        });
    }
}
