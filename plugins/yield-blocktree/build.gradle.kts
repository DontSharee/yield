version = "0.5.0"

dependencies {
    // Not transitive from yield-packs'/yield-zones' own compileOnly
    // dependencies on it - needed directly here since BlockTreeService/the
    // GUIs reference yield-core types (PlayerDataStore, Gui, MenuLore, etc.)
    // themselves.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    compileOnly(project(":yield-zones"))
    // For the @BsonId annotation on this plugin's own BlockTreeProfile.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
