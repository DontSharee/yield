version = "0.1.0"

dependencies {
    // Not transitive from yield-packs'/yield-zones' own compileOnly
    // dependencies on it - needed directly here since PlayerLevelingService
    // references yield-core types (PlayerDataStore) itself.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-zones"))
    compileOnly(project(":yield-mining"))
    // For the @BsonId annotation on this plugin's own LevelingProfile - the
    // real driver ships inside yield-core's shaded jar at runtime.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
