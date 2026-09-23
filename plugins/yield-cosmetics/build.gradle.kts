version = "0.1.2"

dependencies {
    // Not transitive from yield-packs' own compileOnly dependency on it -
    // needed directly here since cosmetics code references yield-core types
    // (PlayerDataStore, ChatFormatter, DatabaseManager) and yield-packs
    // types (PackPlayerProfile, YieldPacks) directly.
    compileOnly(project(":yield-core"))
    // Needed at compile time for the raw MongoCollection/Document types
    // CosmeticPopularityService queries directly - the real driver classes
    // ship inside yield-core's own shaded jar at runtime (same reasoning as
    // yield-packs'/yield-leaderboards' identical compileOnly on this dependency).
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
