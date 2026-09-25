version = "0.8.2"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // yield-core's own classes (packet display managers, EntityClickRegistry,
    // PlayerDataStore, etc.) - never shaded, resolved at runtime through
    // Bukkit's plugin classloader chain via "depend: [yield-core]" in
    // plugin.yml.
    compileOnly(project(":yield-core"))
    // PackPlayerProfile (upgradeLevels) and its coin/damage multiplier
    // provider registries.
    compileOnly(project(":yield-packs"))
    // ZoneDefinition/ZoneLockService (a station's zone-unlock check) and
    // OreCubeService's diamond-chance/flat-diamond-bonus provider registries.
    compileOnly(project(":yield-zones"))
    // RebirthService's rebirth-grant-multiplier registry.
    compileOnly(project(":yield-rebirth"))
    // Not transitive from yield-core's own compileOnly dependency on it -
    // needed directly here since the button's push-animation/shrink builds
    // packetevents Vector3f values itself for BlockDisplayManager's
    // setTransformation overload.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    // For the @BsonId annotation on this plugin's own UpgradeProfile.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
