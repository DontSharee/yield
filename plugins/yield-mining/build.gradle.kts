version = "0.6.3"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // yield-core's own classes (packet display managers, Text, etc.) -
    // never shaded, resolved at runtime through Bukkit's plugin classloader
    // chain via "depend: [yield-core]" in plugin.yml.
    compileOnly(project(":yield-core"))
    // PackPlayerProfile (coins), the player data store.
    compileOnly(project(":yield-packs"))
    // Not transitive from yield-core's own compileOnly dependency on it -
    // needed directly here since the absorb animation builds packetevents
    // Location/Vector3f-backed calls itself via BlockDisplayManager/
    // PacketEntityManager.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}
