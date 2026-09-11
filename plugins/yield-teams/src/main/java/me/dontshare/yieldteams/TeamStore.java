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
        databaseManager.supplyAsync(() -> {
            collection.deleteOne(Filters.eq("_id", team.getId()));
            return null;
        }).exceptionally(error -> {
            logger.log(Level.SEVERE, "Failed to delete team " + team.getName(), error);
            return null;
        });
    }

    /** Call after mutating an already-cached Team object - the cache already reflects the change, this just persists it. */
    public void save(Team team) {
        databaseManager.supplyAsync(() -> {
            collection.replaceOne(Filters.eq("_id", team.getId()), team, new ReplaceOptions().upsert(true));
            return null;
        }).exceptionally(error -> {
            logger.log(Level.SEVERE, "Failed to save team " + team.getName(), error);
            return null;
        });
    }
}
