package me.dontshare.yieldcore.database;

import com.mongodb.MongoClientSettings;
import com.mongodb.ConnectionString;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.TransactionBody;
import org.bson.UuidRepresentation;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.bson.codecs.configuration.CodecRegistries.fromCodecs;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

/**
 * Owns the single MongoDB connection for the whole server. Every plugin
 * shares this one connection/thread pool rather than opening their own -
 * other plugins get a handle via {@code YieldCore#getDatabaseManager()}.
 * <p>
 * All queries should go through {@link #supplyAsync} rather than touching
 * {@link #getDatabase()}/{@link #getCollection} directly on the main
 * thread - MongoDB calls are blocking I/O and will cause server lag (TPS
 * drops) if run on the main thread. The one sanctioned exception is
 * {@code AsyncPlayerPreLoginEvent}, which already runs off the main thread
 * and is where {@link PlayerDataStore#loadBlocking} is meant to be called.
 */
public final class DatabaseManager {

    private final MongoClient client;
    private final MongoDatabase database;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public DatabaseManager(FileConfiguration config) {
        String connectionString = config.getString("mongodb.connection-string", "mongodb://localhost:27017");
        String databaseName = config.getString("mongodb.database", "yield");

        CodecRegistry codecRegistry = fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                // No built-in codec for BigInteger (only BigDecimal) - see
                // BigIntegerCodec's own Javadoc. Registered ahead of the POJO
                // provider so it's picked up automatically for any BigInteger
                // field on any POJO.
                fromCodecs(new BigIntegerCodec()),
                fromProviders(PojoCodecProvider.builder().automatic(true).build())
        );

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                // Explicit UUID representation avoids CodecConfigurationException /
                // ambiguous binary-subtype encoding when POJOs use java.util.UUID fields.
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .codecRegistry(codecRegistry)
                // MAJORITY: a save() isn't treated as successful until it's
                // replicated to a majority of the replica set, not just
                // acknowledged by a primary that could crash a moment later.
                // No-op (behaves like default ACKNOWLEDGED) on a standalone
                // instance, but protects for free if a replica set is added.
                .writeConcern(WriteConcern.MAJORITY)
                // Explicit pool size so a burst of many players
                // disconnecting/saving at once has a known, bounded ceiling
                // rather than relying on the driver's default.
                .applyToConnectionPoolSettings(builder -> builder.maxSize(50).minSize(5))
                .build();

        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(databaseName);
    }

    public MongoDatabase getDatabase() {
        return database;
    }

    public MongoCollection<Document> getCollection(String name) {
        return database.getCollection(name);
    }

    public <T> MongoCollection<T> getCollection(String name, Class<T> type) {
        return database.getCollection(name, type);
    }

    /** Runs a database query on a background thread. */
    public <T> CompletableFuture<T> supplyAsync(Function<MongoDatabase, T> query) {
        return CompletableFuture.supplyAsync(() -> query.apply(database), executor);
    }

    /** Runs arbitrary blocking work (e.g. a MongoCollection call) on the shared DB thread pool. */
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor);
    }

    /**
     * Runs {@code work} as a single multi-document ACID transaction - the
     * connection string is Atlas (a real replica set, confirmed by the
     * "Discovered replica set primary" line every boot logs), so real
     * transactions are available, unlike on a standalone instance. Every
     * {@link MongoCollection} call inside {@code work} MUST be passed the
     * {@link ClientSession} it receives (e.g. {@code collection.updateOne(session, ...)})
     * or it won't participate in the transaction at all. {@code work} may run
     * more than once (the driver retries on a transient transaction error),
     * so it must be safe to re-run - avoid side effects outside the
     * transaction itself inside it. BLOCKING - always call from
     * {@link #supplyAsync}, never the main thread.
     * <p>
     * First user of this codebase's very first cross-collection transaction -
     * see yield-auctionhouse, built specifically to make an item/currency
     * duplication bug structurally impossible rather than merely unlikely.
     */
    public <T> T withTransaction(Function<ClientSession, T> work) {
        try (ClientSession session = client.startSession()) {
            TransactionBody<T> body = () -> work.apply(session);
            return session.withTransaction(body);
        }
    }

    public void close() {
        executor.shutdown();
        client.close();
    }
}
