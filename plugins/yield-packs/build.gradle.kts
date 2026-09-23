version = "0.58.0"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // yield-core's own classes (Gui, PlayerDataStore, ItemBuilder, etc.) -
    // never shaded, resolved at runtime through Bukkit's plugin classloader
    // chain via the "depend: [yield-core]" in plugin.yml.
    compileOnly(project(":yield-core"))
    // Needed at compile time for @BsonId on PackPlayerProfile and the raw
    // MongoCollection/Document types ExistsCounterStore uses - the real
    // driver classes ship inside yield-core's own shaded jar at runtime.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
    // Pets are player heads resolved by id from the separately-installed
    // HeadDatabase plugin - see plugin.yml's "softdepend" list. Published on
    // Maven Central directly, no extra repository needed.
    compileOnly("com.arcaniax:HeadDatabase-API:1.3.2")
    // The Enchanting Table's own packet-button display (PetEnchantTableDisplay)
    // builds packetevents Vector3f values itself for BlockDisplayManager's
    // non-uniform setTransformation overload - same dependency yield-zones/
    // yield-mining already needed for their own packet-entity stations.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}
