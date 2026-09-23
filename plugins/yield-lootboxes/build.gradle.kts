version = "0.1.0"

dependencies {
    // Not transitive from yield-packs' own compileOnly dependency on it -
    // needed directly here since LootboxService/LootboxGui reference
    // yield-core types (PlayerDataStore, Gui, MenuLore, etc.) themselves.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
}
