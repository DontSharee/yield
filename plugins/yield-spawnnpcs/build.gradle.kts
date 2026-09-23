version = "0.2.0"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // yield-core's own classes (Gui, CommandManager, AdminCommandRegistry,
    // etc.) - never shaded, resolved at runtime through Bukkit's plugin
    // classloader chain via "depend: [yield-core]" in plugin.yml.
    compileOnly(project(":yield-core"))
    // PackPlayerProfile (Credits/Coins/Diamonds), the pet registry/equip
    // service - the Crate Shop's own reward-granting logic.
    compileOnly(project(":yield-packs"))
    // OreCubeKilledEvent - CrateKeyDropListener's own trigger for rolling a
    // chance at a crate Key on every ore cube kill.
    compileOnly(project(":yield-zones"))
    // The crate stations' packet-entity button + floating text label
    // (CrateDisplay) build packetevents Vector3f values directly for
    // BlockDisplayManager's non-uniform setTransformation overload, same
    // dependency every other packet-entity station in this codebase needs.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}
