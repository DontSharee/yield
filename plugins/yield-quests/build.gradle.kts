version = "0.4.1"

dependencies {
    // Not transitive from other plugins' own compileOnly dependencies on
    // these - needed directly here since quest code references yield-core
    // types (PlayerDataStore, Text, CommandManager) and every other
    // module's own cross-plugin events directly (PackOpenedEvent,
    // PetFusedEvent, OreCubeKilledEvent, RebirthEvent, PrestigeEvent,
    // SkillNodeBoughtEvent, ...) - see GameAction for the full trigger list.
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    compileOnly(project(":yield-zones"))
    compileOnly(project(":yield-rebirth"))
    compileOnly(project(":yield-skilltree"))
    // Present icons in the /daily GUI - see plugin.yml's "softdepend" list.
    // Published on Maven Central directly, no extra repository needed.
    compileOnly("com.arcaniax:HeadDatabase-API:1.3.2")
    // For the @BsonId annotation on this plugin's own QuestProfile.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
