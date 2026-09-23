version = "0.0.1"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // PlayerStores/PlayerRecord, GUIs, Text/Formatting, commands.
    compileOnly(project(":yield-core"))
    // Coins (what tools cost) and the player profile they come out of.
    compileOnly(project(":yield-packs"))
    // TapService - a tool is a tap multiplier provider registered there.
    compileOnly(project(":yield-zones"))
    // @BsonId on this plugin's own player record - not transitive from
    // yield-core's own compileOnly dependency on the driver.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
