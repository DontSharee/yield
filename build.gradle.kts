import org.gradle.api.tasks.bundling.Jar
import java.io.File

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Maintained fork of com.jcraft:jsch (same com.jcraft.jsch.* package,
        // drop-in compatible) - the original is stale and fails modern SSH
        // key-exchange/cipher negotiation against current hosts.
        classpath("com.github.mwiede:jsch:2.28.6")
    }
}

plugins {
    id("com.gradleup.shadow") version "8.3.5" apply false
    id("xyz.jpenilla.run-paper") version "3.1.0" apply false
}

// Uploads a single file over SFTP, reading connection details from
// gradle.properties (gitignored, not committed - see gradle.properties for
// the required keys). Shared by each subproject's own "deploy" task so a
// build of just one module only ever touches that module's jar on the
// server, not every plugin's.
fun uploadFile(project: Project, file: File) {
    val host = project.findProperty("sftpHost") as String?
        ?: error("Missing 'sftpHost' in gradle.properties - see gradle.properties")
    val port = (project.findProperty("sftpPort") as String?)?.toInt() ?: 22
    val user = project.findProperty("sftpUser") as String?
        ?: error("Missing 'sftpUser' in gradle.properties - see gradle.properties")
    val password = project.findProperty("sftpPassword") as String?
        ?: error("Missing 'sftpPassword' in gradle.properties - see gradle.properties")
    val remotePath = (project.findProperty("sftpRemotePath") as String?) ?: "/"

    val jsch = com.jcraft.jsch.JSch()
    val session = jsch.getSession(user, host, port)
    session.setPassword(password.toByteArray())
    session.setConfig("StrictHostKeyChecking", "no")
    session.connect(10_000)

    val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
    channel.connect(10_000)
    try {
        // The uploaded filename bakes in the plugin's version (e.g.
        // "yield-core-0.1.2.jar" - see the per-plugin versioning scheme in
        // CLAUDE.md), so every version bump would otherwise leave the
        // previous jar behind on the server forever, and Paper doesn't like
        // multiple jars claiming the same plugin name.
        // Delete any other jar matching this exact plugin's own base name
        // before uploading the new one - an EXACT match on
        // "<baseName>-X.Y.Z.jar", not a prefix check, since one plugin's
        // name can be a literal prefix of another's and a naive
        // startsWith("$baseName-") would wipe out the other plugin's jar
        // just by deploying this one.
        val baseName = file.name.replace(Regex("-\\d+\\.\\d+\\.\\d+\\.jar$"), "")
        val staleJarPattern = Regex("^${Regex.escape(baseName)}-\\d+\\.\\d+\\.\\d+\\.jar$")
        @Suppress("UNCHECKED_CAST")
        val existing = channel.ls(remotePath) as java.util.Vector<com.jcraft.jsch.ChannelSftp.LsEntry>
        for (entry in existing) {
            val name = entry.filename
            if (name != file.name && staleJarPattern.matches(name)) {
                try {
                    project.logger.lifecycle("Deleting stale jar $name")
                    channel.rm("$remotePath/$name")
                } catch (e: Exception) {
                    project.logger.warn("Failed to delete stale jar $name: ${e.message}")
                }
            }
        }

        val remoteFile = "$remotePath/${file.name}"
        project.logger.lifecycle("Uploading ${file.name} -> $remoteFile")
        channel.put(file.absolutePath, remoteFile)
    } finally {
        channel.disconnect()
        session.disconnect()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.gradleup.shadow")
    apply(plugin = "xyz.jpenilla.run-paper")

    group = "me.dontshare"
    // Per-plugin version, X.Y.Z - each plugin's own build.gradle.kts
    // overrides this once it's actually been touched under this scheme.
    // Bump the Y segment for a major change, the Z segment for a minor one
    // (see CLAUDE.md).
    version = "0.0.1"

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:26.2.build.119-stable")
    }

    // Uploads just this plugin's own shaded jar - not every subproject's -
    // so building/testing one module never touches another's already-deployed
    // jar with whatever happens to be on this branch for it.
    tasks.register("deploy") {
        group = "deployment"
        description = "Uploads this plugin's shaded jar to the configured SFTP server."

        dependsOn("shadowJar")

        doLast {
            val jarFile = (tasks.named("shadowJar").get() as Jar).archiveFile.get().asFile
            if (jarFile.exists()) {
                uploadFile(project, jarFile)
            }
        }
    }

    tasks.named("build") {
        dependsOn("shadowJar")
        finalizedBy("deploy")
    }

    // The plain "jar" task's output has no bundled dependencies and isn't
    // deployable on its own - give it an obvious suffix so it can't be
    // mistaken for the real artifact, and let the shaded jar (which has
    // everything bundled) take over the normal, unsuffixed filename.
    tasks.named("jar", Jar::class) {
        archiveClassifier.set("thin")
    }

    tasks.named("shadowJar", Jar::class) {
        archiveClassifier.set("")
    }

    // afterEvaluate, not immediate - this closure runs (for every subproject)
    // during the root script's own evaluation, which happens before each
    // subproject's own build.gradle.kts does. Reading project.version here
    // directly would permanently bake in this block's "0.0.1" default,
    // ignoring any per-plugin override a module's own build.gradle.kts sets
    // afterward - afterEvaluate defers until that override has landed.
    afterEvaluate {
        tasks.withType<ProcessResources> {
            // expand()'s token map isn't tracked as a task input on its own,
            // so a version bump alone doesn't invalidate Gradle's up-to-date
            // cache for this task - plugin.yml can silently keep an old
            // ${version} substitution baked in even after a fresh build.
            // Declaring it as an explicit input property fixes that.
            inputs.property("pluginVersion", project.version)
            filteringCharset = "UTF-8"
            // Scoped to plugin.yml only (the one file that actually uses
            // ${version}) - expand() compiles each matched file through a
            // Groovy template, which hard-caps string literals at 65535
            // chars; applying it to every resource broke on yield-packs'
            // own packs.yml once its content grew past that.
            filesMatching("plugin.yml") {
                expand("version" to project.version)
            }
        }
    }
}

// Handy after a bulk redeploy to confirm there's exactly one jar per
// plugin on the server - reuses yield-core's gradle.properties SFTP config
// since it's the same server/credentials for every module.
tasks.register("listRemotePlugins") {
    group = "deployment"
    description = "Lists every .jar currently on the configured SFTP server's plugins folder."

    doLast {
        val project = project(":yield-core")
        val host = project.findProperty("sftpHost") as String
        val port = (project.findProperty("sftpPort") as String?)?.toInt() ?: 22
        val user = project.findProperty("sftpUser") as String
        val password = project.findProperty("sftpPassword") as String
        val remotePath = (project.findProperty("sftpRemotePath") as String?) ?: "/"

        val jsch = com.jcraft.jsch.JSch()
        val session = jsch.getSession(user, host, port)
        session.setPassword(password.toByteArray())
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(10_000)
        val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
        channel.connect(10_000)
        try {
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(remotePath) as java.util.Vector<com.jcraft.jsch.ChannelSftp.LsEntry>
            for (entry in entries.sortedBy { it.filename }) {
                if (entry.filename.endsWith(".jar")) {
                    println(entry.filename)
                }
            }
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }
}
