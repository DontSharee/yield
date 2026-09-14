version = "0.0.3"

dependencies {
    // Not transitive from yield-packs' own compileOnly dependency on it -
    // needed directly here since DonorRankService/YieldRanks reference
    // yield-core types (CommandManager, AdminCommandRegistry, Text)
    // themselves.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    compileOnly(project(":yield-leveling"))
    // Soft dependency (see plugin.yml) - chat-color/tag auto-equip on
    // rank purchase degrades to a no-op if yield-cosmetics isn't
    // installed. Still needed at compile time to call CosmeticService
    // directly rather than through reflection.
    compileOnly(project(":yield-cosmetics"))
}
