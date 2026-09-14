version = "0.0.6"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // yield-core's own classes (packet display managers, EntityClickRegistry,
    // PlayerDataStore, etc.) - never shaded, resolved at runtime through
    // Bukkit's plugin classloader chain via "depend: [yield-core]" in
    // plugin.yml.
    compileOnly(project(":yield-core"))
    // PackDefinition/PackRegistry/PackRollService (buyStationPack) and
    // PackPlayerProfile for affordability checks.
    compileOnly(project(":yield-packs"))
    // ZoneDefinition/ZoneLockService (a station's zone-unlock check).
    compileOnly(project(":yield-zones"))
    // Not transitive from yield-core's own compileOnly dependency on it -
    // needed directly here since the station's button/wall build packetevents
    // Vector3f values itself for BlockDisplayManager's non-uniform
    // setTransformation overload.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}
