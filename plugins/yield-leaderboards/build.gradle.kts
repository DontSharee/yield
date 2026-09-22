version = "0.1.4"

repositories {
    maven("https://repo.fancyinnovations.com/releases")
}

dependencies {
    compileOnly(project(":yield-core"))
    // Needed at compile time for the raw MongoCollection/Document types
    // LeaderboardService queries directly - the real driver classes ship
    // inside yield-core's own shaded jar at runtime (same reasoning as
    // yield-packs' identical compileOnly on this same dependency).
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
    // The actual FancyHolograms plugin must be separately installed on the
    // server (like PlaceholderAPI/HeadDatabase) - this is compile-only,
    // just to reference its API classes.
    compileOnly("de.oliver:FancyHolograms:2.4.2")
}
