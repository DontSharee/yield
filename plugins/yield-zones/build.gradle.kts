version = "0.20.7"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // Not transitive from yield-packs' own compileOnly dependency on it -
    // needed directly here since zone/cube code references yield-core types
    // (FakeFallingBlock, FakeBlockClickRegistry, PlayerDataStore, Text) and
    // yield-packs types (PackPlayerProfile, EquipmentService, LuckService) directly.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    // Not transitive from yield-core's own compileOnly dependency on it -
    // needed directly here since OreCubeService's hit-squish animation
    // builds packetevents Vector3f values itself for BlockDisplayManager's
    // non-uniform setTransformation overload.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}
