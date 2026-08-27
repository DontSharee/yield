plugins {
    // Lets Gradle auto-download JDK toolchains (e.g. JDK 25, required by
    // Paper 26.2) instead of failing when one isn't already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "yield"

include("yield-core")
project(":yield-core").projectDir = file("plugins/yield-core")
