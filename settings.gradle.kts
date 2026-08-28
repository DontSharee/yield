plugins {
    // Lets Gradle auto-download JDK toolchains (e.g. JDK 25, required by
    // Paper 26.2) instead of failing when one isn't already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "yield"

include("yield-core")
project(":yield-core").projectDir = file("plugins/yield-core")

include("yield-packs")
project(":yield-packs").projectDir = file("plugins/yield-packs")

include("yield-rebirth")
project(":yield-rebirth").projectDir = file("plugins/yield-rebirth")
