version = "0.10.3"

repositories {
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    implementation("org.mongodb:mongodb-driver-sync:5.10.0")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    implementation(platform("com.intellectualsites.bom:bom-newest:1.56"))
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit") { isTransitive = false }
}

tasks {
    runServer {
        minecraftVersion("26.2")
        downloadPlugins {
            modrinth("packetevents", "2.13.0+spigot")
        }
    }
}
