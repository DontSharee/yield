version = "0.3.3"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // PlayerStores/PlayerRecord, Text/Formatting, the listener manager.
    compileOnly(project(":yield-core"))
    // The egg an event sells is an ordinary pack: PackOpenService hatches it,
    // PackRegistry names it, and the whole reveal comes for free.
    compileOnly(project(":yield-packs"))
    // OreCubeKilledEvent - where the event currency comes from.
    compileOnly(project(":yield-zones"))
    // Registering the event's own station contents and its price.
    compileOnly(project(":yield-packstations"))
    // @BsonId on this plugin's own player record - not transitive from
    // yield-core's own compileOnly dependency on the driver.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
