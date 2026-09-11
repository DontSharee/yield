package me.dontshare.yieldcore.database;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.UpdateOptions;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic, per-plugin player-data cache. Every plugin shares one physical
 * collection (typically "playerData"), but each store only ever reads and
 * {@code $set}s its own top-level field ({@code fieldKey}) within a
 * player's document - never a whole-document replace. That's what makes
 * sharing one collection across plugins safe: two plugins saving the same
 * player at the same time touch disjoint fields, so neither can clobber
 * the other's data.
 * <p>
 * Usage pattern (see {@code me.dontshare.yieldcore.player.PlayerProfileManager}
 * for a full reference implementation):
 * <ol>
 *   <li>On {@code AsyncPlayerPreLoginEvent} (already off the main thread),
 *       call {@link #loadBlocking} and deny login if it throws - never let
 *       a player join on default data, since that default data would then
 *       get saved over their real data.</li>
 *   <li>While online, read/write via {@link #getCached}/{@link #getOrCreate} -
 *       both are instant in-memory operations, no Mongo round-trip.</li>
 *   <li>On {@code PlayerQuitEvent}, call {@link #save} then {@link #unload}.</li>
 *   <li>Call {@link #startAutoSave} once at plugin startup to bound data
 *       loss from a hard crash to one autosave interval.</li>
 *   <li>On the plugin's {@code onDisable}, call {@link #saveAllSync} - async
 *       tasks are not guaranteed to finish during shutdown, so the final
 *       flush must block.</li>
 * </ol>
 */
public final class PlayerDataStore<T extends PlayerRecord> {

    private final DatabaseManager databaseManager;
    private final MongoCollection<Document> collection;
    private final CodecRegistry codecRegistry;
    private final String fieldKey;
    private final Class<T> type;
    private final Function<UUID, T> defaultFactory;
    private final Logger logger;

    private final Map<UUID, T> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();
    private Function<Document, Document> rawMigration = document -> document;

    /**
     * @param collectionName shared collection name, e.g. "playerData" - the same value across plugins
     * @param fieldKey       this plugin's own top-level field within each player's document, e.g. "fishing"
     */
    public PlayerDataStore(DatabaseManager databaseManager, String collectionName, String fieldKey, Class<T> type,
                            Function<UUID, T> defaultFactory, Logger logger) {
        this.databaseManager = databaseManager;
        this.collection = databaseManager.getCollection(collectionName);
        this.codecRegistry = databaseManager.getDatabase().getCodecRegistry();
        this.fieldKey = fieldKey;
        this.type = type;
        this.defaultFactory = defaultFactory;
        this.logger = logger;
    }

    /**
     * Runs once, on the raw sub-document, before every decode - lets a
     * plugin migrate an old field shape into a new one (e.g. renaming/
     * restructuring a field after a breaking data-model change) without a
     * permanent dual-field shim in the actual POJO. A no-op by default.
     * Since {@link #save} always {@code $set}s the whole re-serialized
     * object (never a partial merge), any stale key this migration doesn't
     * touch simply disappears on that player's very next save - there's
     * nothing to clean up here beyond adding whatever the new shape needs.
     */
    public void setRawMigration(Function<Document, Document> rawMigration) {
        this.rawMigration = rawMigration != null ? rawMigration : (document -> document);
    }

    /** In-memory read of an already-loaded player. Null if not currently cached. */
    public T getCached(UUID playerId) {
        return cache.get(playerId);
    }

    /** Like {@link #getCached}, but falls back to an in-memory default rather than returning null. */
    public T getOrCreate(UUID playerId) {
        return cache.computeIfAbsent(playerId, defaultFactory);
    }

    /**
     * Loads a player's data, waiting for any still-in-flight save for that
     * same player to finish first (so a quick rejoin can't load stale data
     * out from under a save that hasn't landed yet). Caches the result.
     */
    public CompletableFuture<T> load(UUID playerId) {
        CompletableFuture<Void> pendingSave = pendingSaves.get(playerId);
        CompletableFuture<Void> after = pendingSave != null ? pendingSave : CompletableFuture.completedFuture(null);

        return after.thenComposeAsync(ignored -> databaseManager.supplyAsync(() -> fetchOrDefault(playerId)))
                .thenApply(record -> {
                    cache.put(playerId, record);
                    return record;
                });
    }

    /**
     * Blocking load for use in {@code AsyncPlayerPreLoginEvent}, which
     * already runs off the main thread and is specifically designed for
     * blocking pre-join checks. Throws on failure - callers must deny
     * login rather than let the player join on default data.
     */
    public T loadBlocking(UUID playerId) {
        CompletableFuture<Void> pendingSave = pendingSaves.get(playerId);
        if (pendingSave != null) {
            pendingSave.join();
        }
        T record = fetchOrDefault(playerId);
        cache.put(playerId, record);
        return record;
    }

    /** Async save of the currently cached value. No-op if nothing is cached for this player. */
    public CompletableFuture<Void> save(UUID playerId) {
        T record = cache.get(playerId);
        if (record == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = databaseManager.supplyAsync(() -> {
            collection.updateOne(Filters.eq("_id", playerId), Updates.set(fieldKey, record), new UpdateOptions().upsert(true));
            return null;
        });

        pendingSaves.put(playerId, future);
        future.whenComplete((ignored, error) -> {
            pendingSaves.remove(playerId, future);
            if (error != null) {
                logger.log(Level.SEVERE, "Failed to save player data for " + playerId, error);
            }
        });
        return future;
    }

    /**
     * Same write {@link #save} performs, but participates in the caller's
     * {@link ClientSession}/transaction instead of committing on its own -
     * see {@link DatabaseManager#withTransaction}. BLOCKING, and must only
     * ever be called from inside that transaction's own work function (never
     * the main thread, never outside a transaction - use {@link #save} for
     * a normal, single-document write). A no-op if nothing is cached for
     * this player.
     */
    public void saveWithSession(UUID playerId, ClientSession session) {
        T record = cache.get(playerId);
        if (record == null) {
            return;
        }
        collection.updateOne(session, Filters.eq("_id", playerId), Updates.set(fieldKey, record), new UpdateOptions().upsert(true));
    }

    /**
     * Blocking save, for use during {@code onDisable} only - async tasks
     * are not guaranteed to run to completion while the server is
     * shutting down, so the final save has to block instead.
     */
    public void saveSync(UUID playerId) {
        T record = cache.get(playerId);
        if (record == null) {
            return;
        }
        try {
            collection.updateOne(Filters.eq("_id", playerId), Updates.set(fieldKey, record), new UpdateOptions().upsert(true));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to save player data for " + playerId + " during shutdown", e);
        }
    }

    /** Blocking save of every currently cached player. Call this from {@code onDisable}. */
    public void saveAllSync() {
        for (UUID playerId : cache.keySet()) {
            saveSync(playerId);
        }
    }

    /** Drops a player from the in-memory cache. Call after {@link #save} on quit. */
    public void unload(UUID playerId) {
        cache.remove(playerId);
    }

    /**
     * Periodically saves every cached player, bounding data loss on a hard
     * crash (killed process, OOM, host outage) to at most one interval's
     * worth of changes - a hard crash fires no events at all, so this is
     * the only thing that protects against it.
     * <p>
     * The initial delay is jittered up to {@code intervalTicks} so that
     * multiple stores (one per plugin, all typically started around server
     * boot) don't all land on the same tick every cycle - that would turn a
     * routine autosave into a periodic burst of every plugin's saves at once.
     */
    public void startAutoSave(JavaPlugin plugin, long intervalTicks) {
        long initialDelay = ThreadLocalRandom.current().nextLong(intervalTicks);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (UUID playerId : cache.keySet()) {
                save(playerId);
            }
        }, initialDelay, intervalTicks);
    }

    private T fetchOrDefault(UUID playerId) {
        Document doc = collection.find(Filters.eq("_id", playerId))
                .projection(Projections.include(fieldKey))
                .first();

        Document sub = doc != null ? doc.get(fieldKey, Document.class) : null;
        T decoded = sub != null ? decode(rawMigration.apply(sub)) : null;
        return decoded != null ? decoded : defaultFactory.apply(playerId);
    }

    private T decode(Document subDocument) {
        Codec<T> codec = codecRegistry.get(type);
        BsonDocument bson = subDocument.toBsonDocument(Document.class, codecRegistry);
        return codec.decode(new BsonDocumentReader(bson), DecoderContext.builder().build());
    }
}
