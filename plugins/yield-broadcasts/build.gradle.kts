version = "0.0.8"

dependencies {
    // Text/CommandManager - and every other module's own cross-plugin
    // events directly (PackOpenedEvent, PetFusedEvent, RebirthEvent,
    // PrestigeEvent, ZoneUnlockedEvent, TeamCreatedEvent, ...).
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    compileOnly(project(":yield-zones"))
    compileOnly(project(":yield-rebirth"))
    compileOnly(project(":yield-skilltree"))
    compileOnly(project(":yield-teams"))
}
