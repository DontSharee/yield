version = "0.3.6"

dependencies {
    // Text/CommandManager/Gui - and every other module's own cross-plugin
    // events directly (PackOpenedEvent, PetFusedEvent, OreCubeKilledEvent,
    // ZoneUnlockedEvent, RebirthEvent, PrestigeEvent, SkillNodeBoughtEvent,
    // TeamCreatedEvent, WorldBossKilledEvent, ...) for achievement/milestone
    // triggers, same dependency footprint as yield-broadcasts (which already
    // listens to this exact same event set).
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    compileOnly(project(":yield-zones"))
    compileOnly(project(":yield-rebirth"))
    compileOnly(project(":yield-skilltree"))
    compileOnly(project(":yield-teams"))
    // For the @BsonId annotation on this plugin's own AchievementProfile.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
