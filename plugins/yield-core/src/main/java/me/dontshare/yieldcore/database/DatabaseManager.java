package me.dontshare.yieldcore.database;

import com.mongodb.MongoClientSettings;
import com.mongodb.ConnectionString;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
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

    public void close() {
        executor.shutdown();
        client.close();
    }
}
