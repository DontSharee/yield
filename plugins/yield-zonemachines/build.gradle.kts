version = "0.1.1"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // yield-core's own classes (packet display managers, EntityClickRegistry,
    // PlayerDataStore, etc.) - never shaded, resolved at runtime through
    // Bukkit's plugin classloader chain via "depend: [yield-core]" in
    // plugin.yml.
    compileOnly(project(":yield-core"))
    // PackPlayerProfile (coins/gems), CandyItem/candy config, the pet
    // registry.
    compileOnly(project(":yield-packs"))
    // ZoneDefinition/ZoneLockService (a machine's zone-unlock check).
    compileOnly(project(":yield-zones"))
    // RebirthService - the Rebirth Machine performs a real rebirth in place.
    compileOnly(project(":yield-rebirth"))
    // Not transitive from yield-core's own compileOnly dependency on it -
    // needed directly here since the button's push-animation builds
    // packetevents Vector3f values itself for BlockDisplayManager's
    // non-uniform setTransformation overload.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}
