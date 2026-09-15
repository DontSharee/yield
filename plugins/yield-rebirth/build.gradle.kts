version = "0.2.1"

dependencies {
    // Not transitive from yield-packs' own compileOnly dependency on it -
    // needed directly here since RebirthService/RebirthDialog reference
    // yield-core types (PlayerDataStore, Text, Formatting, CommandManager)
    // themselves.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
}
