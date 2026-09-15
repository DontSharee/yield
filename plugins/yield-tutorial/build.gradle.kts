version = "0.0.6"

repositories {
    // TutorialNpcManager spawns its player-shaped NPC via raw PacketEvents
    // types (EntityTypes.PLAYER) directly, not just through yield-core's
    // own wrapper methods - needs the same repository yield-core declares
    // for the same dependency.
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // yield-core's PacketEntityManager/EntityClickRegistry/PlayerProfile.
    compileOnly(project(":yield-core"))
    // PackOpenedEvent, PetEquippedEvent, EquipmentService (for the equip
    // hook's damage lookup isn't needed here, just the event).
    compileOnly(project(":yield-packs"))
    // OreCubeKilledEvent + the zone-entered listener hook on OreCubeService.
    compileOnly(project(":yield-zones"))
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}
