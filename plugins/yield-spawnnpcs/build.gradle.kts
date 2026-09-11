version = "0.0.3"

dependencies {
    // yield-core's own classes (Gui, CommandManager, AdminCommandRegistry,
    // etc.) - never shaded, resolved at runtime through Bukkit's plugin
    // classloader chain via "depend: [yield-core]" in plugin.yml.
    compileOnly(project(":yield-core"))
    // PackPlayerProfile (Credits/Coins/Gems), the pet registry/equip
    // service - the Crate Shop's own reward-granting logic.
    compileOnly(project(":yield-packs"))
}
