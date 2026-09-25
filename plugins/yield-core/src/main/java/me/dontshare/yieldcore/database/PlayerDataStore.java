package me.dontshare.yieldcore.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.UpdateOptions;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /** Every store created, for the status readout - they all live as long as the server does. */
    private static final List<PlayerDataStore<?>> ALL = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** One store's load right now: players held in memory, writes in flight, and saves waiting for the end of the tick. */
    public record StoreStats(String field, int cached, int writesInFlight, int coalesced) {
    }

    public static List<StoreStats> allStats() {
        List<StoreStats> stats = new ArrayList<>();
        for (PlayerDataStore<?> store : ALL) {
            stats.add(new StoreStats(store.fieldKey, store.cache.size(), store.pendingSaves.size(), store.coalesced.size()));
        }
        return stats;
    }

    private final Map<UUID, T> cache = new ConcurrentHashMap<>();
    /**
     * Each player's current session - started by a login load, ended by
     * {@link #unload}. A record is only ever cached for a player with one;
     * see {@link #getOrCreate}.
     */
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong sessionCounter = new java.util.concurrent.atomic.AtomicLong();
    /**
     * The session each online connection joined with. A quit ends that one,
     * not whatever is current: on a duplicate login the new connection's
     * pre-login has already started the next session by the time the old
     * one is kicked.
     */
    private final Map<UUID, Long> joinedSessions = new ConcurrentHashMap<>();
    /** Guards every change to which players have a session together with their cache entry. */
    private final Object sessionLock = new Object();
    private final Map<UUID, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();
    /** save() calls waiting for this tick's single write - see {@link #save}. */
    private final Map<UUID, CompletableFuture<Void>> coalesced = new ConcurrentHashMap<>();
    private volatile boolean flushScheduled;
    /** Set by {@link #startAutoSave}; schedules the coalesced flush. */
    private volatile JavaPlugin owner;
    /** Per-top-level-field hashes of each player's last successfully written subdocument - see {@link #write} and {@link #deepHash}. */
    private final Map<UUID, Map<String, Long>> lastFieldHashes = new ConcurrentHashMap<>();
    /** Players still to be visited in the current autosave sweep - see {@link #startAutoSave}. */
    private final ArrayDeque<UUID> autoSaveQueue = new ArrayDeque<>();
    private int autoSaveBudget = 1;
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
        ALL.add(this);
    }

    /**
     * Runs once, on the raw sub-document, before every decode - lets a
     * plugin migrate an old field shape into a new one (e.g. renaming/
     * restructuring a field after a breaking data-model change) without a
     * permanent dual-field shim in the actual POJO. A no-op by default.
     * Any stale key this migration doesn't touch simply disappears on that
     * player's first save after loading, which always writes the whole
     * re-serialized subdocument rather than a partial merge (see
     * {@link #write}) - there's nothing to clean up here beyond adding
     * whatever the new shape needs.
     */
    public void setRawMigration(Function<Document, Document> rawMigration) {
        this.rawMigration = rawMigration != null ? rawMigration : (document -> document);
    }

    /** In-memory read of an already-loaded player. Null if not currently cached. */
    public T getCached(UUID playerId) {
        return cache.get(playerId);
    }

    /**
     * Like {@link #getCached}, but falls back to an in-memory default rather than returning null.
     * <p>
     * For a player with no session (logged off, or never here) the default
     * is handed back detached - not cached, so never saved. Cached, it would
     * be picked up by the next autosave, and a first write is always the
     * whole subdocument: a late callback touching someone who had just left
     * would have written a blank record over everything they owned.
     */
    public T getOrCreate(UUID playerId) {
        T record = cache.get(playerId);
        if (record != null) {
            return record;
        }
        synchronized (sessionLock) {
            if (!sessions.containsKey(playerId)) {
                return defaultFactory.apply(playerId);
            }
            return cache.computeIfAbsent(playerId, defaultFactory);
        }
    }

    /** This player's current session token, or -1 without one. */
    public long sessionOf(UUID playerId) {
        return sessions.getOrDefault(playerId, -1L);
    }

    /** Call on join: ties the connection to the session its pre-login started. */
    public void markJoined(UUID playerId) {
        long session = sessionOf(playerId);
        if (session >= 0) {
            joinedSessions.put(playerId, session);
        }
    }

    /** Call on quit: the session this connection joined with, to pass to {@link #unload(UUID, long)}. */
    public long takeJoinedSession(UUID playerId) {
        Long session = joinedSessions.remove(playerId);
        return session != null ? session : sessionOf(playerId);
    }

    /**
     * Loads a player's data, waiting for any still-in-flight save for that
     * same player to finish first (so a quick rejoin can't load stale data
     * out from under a save that hasn't landed yet). Caches the result.
     */
    public CompletableFuture<T> load(UUID playerId) {
        return databaseManager.supplyAsync(() -> loadBlocking(playerId));
    }

    /**
     * Blocking load for use in {@code AsyncPlayerPreLoginEvent}, which
     * already runs off the main thread and is specifically designed for
     * blocking pre-join checks. Throws on failure - callers must deny
     * login rather than let the player join on default data.
     */
    public T loadBlocking(UUID playerId) {
        // Still cached means the copy in memory is the newest there is: a
        // duplicate login while the old session is online, a rejoin before
        // the quit save finished, or a quit save that failed and is waiting
        // on a retry. Reading the database instead would roll them back.
        synchronized (sessionLock) {
            T live = cache.get(playerId);
            if (live != null) {
                sessions.put(playerId, sessionCounter.incrementAndGet());
                return live;
            }
        }
        CompletableFuture<Void> pendingSave = pendingSaves.get(playerId);
        if (pendingSave != null) {
            pendingSave.join();
        }
        T record = fetchOrDefault(playerId);
        // The session starts only once the record is in place, so nothing
        // can cache a blank default for them while it loads.
        synchronized (sessionLock) {
            T existing = cache.putIfAbsent(playerId, record);
            sessions.put(playerId, sessionCounter.incrementAndGet());
            return existing != null ? existing : record;
        }
    }

    /**
     * Async save of the currently cached value. No-op if nothing is cached
     * for this player.
     * <p>
     * MAIN THREAD ONLY. The record is serialized here, on the caller's
     * thread, and only the finished document is handed to the database - see
     * {@link #encode}.
     */
    public CompletableFuture<Void> save(UUID playerId) {
        if (owner == null || !owner.isEnabled()) {
            return write(playerId, false);
        }
        // Coalesced: every save() for this player in the same tick shares
        // one write at the end of it. A single cube kill used to set off
        // around ten of these across the plugins (payout, quests, blocktree,
        // achievements, milestones, levels...), and each one serialized the
        // player's whole record - their entire pet collection - and wrote
        // the whole subdocument to Mongo. Now it's one diffed write.
        CompletableFuture<Void> existing = coalesced.get(playerId);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        coalesced.put(playerId, future);
        if (!flushScheduled) {
            flushScheduled = true;
            Bukkit.getScheduler().runTask(owner, me.dontshare.yieldcore.perf.PerfTracker.timed("core.saves", this::flushCoalesced));
        }
        return future;
    }

    /**
     * An immediate save that rewrites the whole subdocument - for a player
     * leaving, where nothing should rest on the diff baseline. Anything
     * coalesced for them rides along with it.
     */
    public CompletableFuture<Void> saveNow(UUID playerId) {
        CompletableFuture<Void> pending = coalesced.remove(playerId);
        CompletableFuture<Void> future = write(playerId, true);
        if (pending != null) {
            future.whenComplete((ignored, error) -> complete(pending, error));
        }
        return future;
    }

    private void flushCoalesced() {
        flushScheduled = false;
        List<Map.Entry<UUID, CompletableFuture<Void>>> batch = new ArrayList<>(coalesced.entrySet());
        coalesced.clear();
        for (Map.Entry<UUID, CompletableFuture<Void>> entry : batch) {
            write(entry.getKey(), false).whenComplete((ignored, error) -> complete(entry.getValue(), error));
        }
    }

    private static void complete(CompletableFuture<Void> future, Throwable error) {
        if (error != null) {
            future.completeExceptionally(error);
        } else {
            future.complete(null);
        }
    }

    /**
     * Serializes the cached record and writes back only what actually moved.
     * <p>
     * Replacing the whole {@code fieldKey} subdocument on every save means a
     * player's entire record - for yield-packs, every pet they have ever
     * owned - is rewritten because one quest counter went up. Since this
     * store is shared, every feature's save pays for every other feature's
     * data. Comparing the freshly encoded document against the last one
     * written, field by field, turns that into a {@code $set} of just the
     * fields that differ, and into nothing at all when a player is idle.
     * <p>
     * A forced save (quit, shutdown) writes the subdocument whole, so nothing
     * durable rests on the comparison being right.
     */
    /**
     * A 64-bit, order-sensitive fingerprint of one encoded field.
     * <p>
     * Not {@code BsonValue#hashCode}: a document's is a plain sum over its
     * entries and a string's only 32 bits, so two edits inside one pet (a
     * level and its xp, say) could cancel out and read as "unchanged" - that
     * field would then go unwritten until the next forced save.
     */
    static long deepHash(BsonValue value) {
        long h = value.getBsonType().ordinal() * 0x9E3779B97F4A7C15L;
        switch (value.getBsonType()) {
            case DOCUMENT -> {
                for (Map.Entry<String, BsonValue> entry : value.asDocument().entrySet()) {
                    h = mix(h, hashString(entry.getKey()));
                    h = mix(h, deepHash(entry.getValue()));
                }
            }
            case ARRAY -> {
                for (BsonValue element : value.asArray()) {
                    h = mix(h, deepHash(element));
                }
                h = mix(h, value.asArray().size());
            }
            case STRING -> h = mix(h, hashString(value.asString().getValue()));
            case INT32 -> h = mix(h, value.asInt32().getValue());
            case INT64 -> h = mix(h, value.asInt64().getValue());
            case DOUBLE -> h = mix(h, Double.doubleToLongBits(value.asDouble().getValue()));
            case BOOLEAN -> h = mix(h, value.asBoolean().getValue() ? 1 : 2);
            case DATE_TIME -> h = mix(h, value.asDateTime().getValue());
            case DECIMAL128 -> {
                h = mix(h, value.asDecimal128().getValue().getHigh());
                h = mix(h, value.asDecimal128().getValue().getLow());
            }
            case BINARY -> {
                h = mix(h, value.asBinary().getType());
                for (byte b : value.asBinary().getData()) {
                    h = mix(h, b);
                }
            }
            default -> h = mix(h, value.hashCode());
        }
        return h;
    }

    private static long hashString(String text) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < text.length(); i++) {
            h = (h ^ text.charAt(i)) * 0x100000001B3L;
        }
        return h;
    }

    private static long mix(long h, long v) {
        h = (h ^ v) * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 31);
    }

    private CompletableFuture<Void> write(UUID playerId, boolean force) {
        T record = cache.get(playerId);
        if (record == null) {
            return CompletableFuture.completedFuture(null);
        }

        BsonDocument encoded;
        try {
            encoded = encode(record);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Failed to serialize player data for " + playerId, e);
            me.dontshare.yieldcore.diagnostics.DataHealth.saveFailed(fieldKey, playerId, e);
            return CompletableFuture.failedFuture(e);
        }

        Map<String, Long> previous = force ? null : lastFieldHashes.get(playerId);
        Map<String, Long> current = new HashMap<>(encoded.size() * 2);
        for (Map.Entry<String, BsonValue> field : encoded.entrySet()) {
            current.put(field.getKey(), deepHash(field.getValue()));
        }

        Bson update;
        if (previous == null) {
            update = Updates.set(fieldKey, encoded);
        } else {
            List<Bson> changes = new ArrayList<>();
            for (Map.Entry<String, BsonValue> field : encoded.entrySet()) {
                Long before = previous.get(field.getKey());
                if (before == null || before.longValue() != current.get(field.getKey()).longValue()) {
                    changes.add(Updates.set(fieldKey + "." + field.getKey(), field.getValue()));
                }
            }
            for (String goneKey : previous.keySet()) {
                if (!current.containsKey(goneKey)) {
                    changes.add(Updates.unset(fieldKey + "." + goneKey));
                }
            }
            if (changes.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            update = Updates.combine(changes);
        }

        // Chained behind this player's previous write, if one is still in
        // flight: the database pool has several threads, and two writes of
        // the same record racing could land out of order - an older
        // snapshot overwriting a newer one.
        CompletableFuture<Void> prior = pendingSaves.get(playerId);
        java.util.function.Supplier<CompletableFuture<Void>> run = () -> databaseManager.supplyAsync(() -> {
            collection.updateOne(Filters.eq("_id", playerId), update, new UpdateOptions().upsert(true));
            return (Void) null;
        });
        CompletableFuture<Void> future = prior == null ? run.get()
                : prior.handle((ignored, error) -> null).thenCompose(ignored -> run.get());

        pendingSaves.put(playerId, future);
        future.whenComplete((ignored, error) -> {
            pendingSaves.remove(playerId, future);
            if (error != null) {
                // Cleared, not left stale: the next save must then rewrite the
                // whole subdocument rather than trusting a baseline that may
                // never have landed.
                lastFieldHashes.remove(playerId);
                logger.log(Level.SEVERE, "Failed to save player data for " + playerId, error);
                me.dontshare.yieldcore.diagnostics.DataHealth.saveFailed(fieldKey, playerId, error);
            } else {
                // Only while the player is still loaded: the save made as
                // they quit usually lands after unload() has cleared their
                // entry, and putting it back leaked one entry per player
                // who ever left.
                synchronized (sessionLock) {
                    if (cache.containsKey(playerId)) {
                        lastFieldHashes.put(playerId, current);
                    }
                }
            }
        });
        return future;
    }

    /**
     * Turns the live record into a standalone document.
     * <p>
     * This is the whole reason saving is structured the way it is. Handing
     * the record itself to a database thread means the POJO codec walks its
     * {@code List}/{@code Map} fields there, while the main thread is free to
     * be adding a rolled pet or removing fused ones at that exact moment -
     * a {@link java.util.ConcurrentModificationException} that surfaces only
     * as a logged failure, i.e. a silently dropped save. Encoding on the
     * calling thread means the document handed over is already a finished,
     * immutable snapshot that no later mutation can disturb.
     */
    private BsonDocument encode(T record) {
        BsonDocument document = new BsonDocument();
        try (BsonDocumentWriter writer = new BsonDocumentWriter(document)) {
            codecRegistry.get(type).encode(writer, record, EncoderContext.builder().build());
        }
        return document;
    }

    /**
     * Blocking save, for use during {@code onDisable} only - async tasks
     * are not guaranteed to run to completion while the server is
     * shutting down, so the final save has to block instead.
     */
    public void saveSync(UUID playerId) {
        CompletableFuture<Void> pending = coalesced.remove(playerId);
        if (pending != null) {
            pending.complete(null);
        }
        T record = cache.get(playerId);
        if (record == null) {
            return;
        }
        try {
            collection.updateOne(Filters.eq("_id", playerId), Updates.set(fieldKey, encode(record)), new UpdateOptions().upsert(true));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to save player data for " + playerId + " during shutdown", e);
            me.dontshare.yieldcore.diagnostics.DataHealth.saveFailed(fieldKey, playerId, e);
        }
    }

    /** Blocking save of every currently cached player. Call this from {@code onDisable}. */
    public void saveAllSync() {
        for (UUID playerId : cache.keySet()) {
            saveSync(playerId);
        }
    }

    /**
     * A login this store loaded for was turned away later in pre-login
     * (another store failed, a ban, a full server) - no quit will ever come
     * to end the session, so it ends here. Left alone if they are in fact
     * online: a refused duplicate login must not unload the live session.
     */
    public void abandonLogin(UUID playerId) {
        if (Bukkit.getPlayer(playerId) == null) {
            unload(playerId, sessionOf(playerId));
            return;
        }
        // Their pre-login moved the session on; hand it back to the
        // connection that is actually playing, or its quit won't match.
        synchronized (sessionLock) {
            Long joined = joinedSessions.get(playerId);
            if (joined != null) {
                sessions.put(playerId, joined);
            }
        }
    }

    /**
     * Ends the session {@code session} (from {@link #takeJoinedSession}, taken
     * as they left) and drops the player from the in-memory cache - unless a
     * new login has started another session since, in which case the record
     * is theirs now and stays.
     */
    public void unload(UUID playerId, long session) {
        synchronized (sessionLock) {
            if (session < 0 || !sessions.remove(playerId, session)) {
                return;
            }
            cache.remove(playerId);
            lastFieldHashes.remove(playerId);
        }
    }

    /**
     * Periodically saves every cached player, bounding data loss on a hard
     * crash (killed process, OOM, host outage) to at most one interval's
     * worth of changes - a hard crash fires no events at all, so this is
     * the only thing that protects against it.
     * <p>
     * Runs on the main thread, and deliberately so: serializing a record is
     * only safe on the thread that mutates it (see {@link #encode}). To keep
     * that off the tick budget it sweeps rather than bursts - each second it
     * visits only the slice of cached players needed to get through all of
     * them once per {@code intervalTicks}, instead of serializing everyone
     * on one tick. Combined with the unchanged-document check in
     * {@link #write}, a server full of idle players costs almost nothing.
     * <p>
     * The initial delay is jittered so that multiple stores (one per plugin,
     * all typically started around server boot) don't land on the same tick.
     */
    public void startAutoSave(JavaPlugin plugin, long intervalTicks) {
        this.owner = plugin;
        long step = 20L;
        int slices = (int) Math.max(1, intervalTicks / step);
        long initialDelay = ThreadLocalRandom.current().nextLong(step);
        Bukkit.getScheduler().runTaskTimer(plugin, me.dontshare.yieldcore.perf.PerfTracker.timed("core.autosave", () -> {
            if (autoSaveQueue.isEmpty()) {
                autoSaveQueue.addAll(cache.keySet());
                autoSaveBudget = Math.max(1, (autoSaveQueue.size() + slices - 1) / slices);
            }
            for (int i = 0; i < autoSaveBudget; i++) {
                UUID playerId = autoSaveQueue.poll();
                if (playerId == null) {
                    break;
                }
                if (cache.containsKey(playerId)) {
                    write(playerId, false);
                }
            }
        }), initialDelay, step);
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
