version = "0.1.11"

dependencies {
    // yield-core's DatabaseManager/PlayerDataStore-adjacent Mongo access,
    // GuiManager, CommandManager, Text.
    compileOnly(project(":yield-core"))
    // PackPlayerProfile (coins/diamonds/teamId), YieldPacks' multiplier-provider
    // hooks, LuckService's extraLuckProvider.
    compileOnly(project(":yield-packs"))
    // OreCubeKilledEvent - trophy drops react to it.
    compileOnly(project(":yield-zones"))
    // MongoDB driver classes for the standalone Team collection (not backed
    // by PlayerDataStore, which is keyed by player id, not team id) -
    // resolved at runtime through yield-core's own shaded jar, same
    // reasoning as yield-packs' own compileOnly on it.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
