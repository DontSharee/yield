version = "0.0.4"

dependencies {
    // Not transitive from yield-packs'/yield-zones' own compileOnly
    // dependencies on it - needed directly here since PlayerLevelingService
    // references yield-core types (PlayerDataStore) itself.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    compileOnly(project(":yield-zones"))
    compileOnly(project(":yield-mining"))
}
