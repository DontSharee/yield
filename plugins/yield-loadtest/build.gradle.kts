version = "0.1.1"

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly(project(":yield-core"))
    compileOnly(project(":yield-packs"))
    compileOnly(project(":yield-zones"))
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    // The bots' fake connections are Netty channels - the server ships
    // Netty itself, so this is only for compiling against it.
    compileOnly("io.netty:netty-transport:4.2.15.Final")
    compileOnly("io.netty:netty-buffer:4.2.15.Final")
    compileOnly("io.netty:netty-common:4.2.15.Final")
    // Deleting the bots' documents afterwards.
    compileOnly("org.mongodb:mongodb-driver-sync:5.10.0")
}

// A test tool, never for a live server: building doesn't upload it.
tasks.named("deploy") {
    enabled = false
}
