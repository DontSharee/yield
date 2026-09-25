version = "0.1.0"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    compileOnly(project(":yield-zones"))
    compileOnly(project(":yield-rebirth"))
    compileOnly(project(":yield-mining"))
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}
