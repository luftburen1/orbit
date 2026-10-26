import java.io.IOException
import java.net.URI
import java.security.MessageDigest

plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":tray"))
    implementation(libs.jmdns)
    runtimeOnly(libs.dbus.unixsocket)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "orbit.agent.OrbitAgent"
    // ConsoleMode switches the text console to graphics mode through the foreign-function API.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    // dbus-java finds its socket transport through ServiceLoader, so the service files must survive shading.
    mergeServiceFiles()
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The version shown on the idle screen.
tasks.named<ProcessResources>("processResources") {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("orbit/agent/orbit-agent.properties") {
        expand("version" to version)
    }
}

// What both agent bundles contain: the agent jar, its systemd user unit and the install scripts.
val agentFiles = copySpec {
    from(tasks.named("shadowJar")) { rename { "orbit-agent.jar" } }
    from("dist") {
        filePermissions { unix("rwxr-xr-x") }
        include("install-agent.sh", "install-agent-tty1.sh")
    }
    from("dist") { include("orbit-agent.service", "install-agent.ps1") }
}

// orbit-agent-<version>.zip: needs Java 25 on the client.
val agentDist by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Bundles the agent with its installer."
    archiveBaseName = "orbit-agent"
    destinationDirectory = layout.buildDirectory.dir("dist")
    into("orbit-agent") { with(agentFiles) }
}

// The Java modules the agent uses (jdeps --print-module-deps on the shadow jar), plus java.logging. jdk.net and
// jdk.security.auth are for D-Bus (the tray icon), which talks over a Unix socket.
val agentModules = "java.base,java.desktop,java.logging,java.net.http,java.sql,jdk.net,jdk.security.auth"

// The machine this build runs on, e.g. linux-arm64.
val hostPlatform = run {
    val os = System.getProperty("os.name").lowercase().let {
        when {
            it.startsWith("windows") -> "windows"
            it.startsWith("mac") -> "macos"
            else -> "linux"
        }
    }
    val arch = when (val a = System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> a
    }
    "$os-$arch"
}

// Which machine agentBundle's runtime is for: -PtargetPlatform=windows-x64, linux-arm64, linux-x64, macos-arm64…
// Defaults to this machine.
val targetPlatform = (findProperty("targetPlatform") as String?) ?: hostPlatform

/**
 * Temurin's newest Java 25 runtime (JRE) for another platform, downloaded once from Adoptium into Gradle's cache and
 * checked against Adoptium's SHA-256. JDK 25 builds ship no jmods, so jlink here can't make a trimmed runtime for
 * another platform; the whole JRE is used instead. Delete the cache folder to fetch a newer one.
 */
fun targetJre(platform: String): File {
    val (os, arch) = platform.split("-").let { (o, a) ->
        (if (o == "macos") "mac" else o) to (if (a == "arm64") "aarch64" else a)
    }
    val dir = gradle.gradleUserHomeDir.resolve("caches/orbit-jres/$platform")
    val jre = dir.resolve("jre")
    if (jre.resolve("release").isFile) {
        return jre
    }
    val query = "https://api.adoptium.net/v3/assets/latest/25/hotspot?image_type=jre&vendor=eclipse" +
        "&os=$os&architecture=$arch"
    val json = try {
        URI(query).toURL().readText()
    } catch (e: IOException) {
        throw GradleException("cannot ask Adoptium for a Java 25 runtime for $platform ($query): ${e.message}", e)
    }
    val pkg = (groovy.json.JsonSlurper().parseText(json) as List<*>)
        .map { (it as Map<*, *>)["binary"] as Map<*, *> }
        .firstOrNull()?.let { it["package"] as Map<*, *> }
        ?: throw GradleException("Adoptium has no Java 25 runtime for $platform")
    val archive = dir.resolve(pkg["name"] as String)
    dir.mkdirs()
    logger.lifecycle("Downloading ${pkg["name"]} (${(pkg["size"] as Number).toLong() / 1_000_000} MB)")
    URI(pkg["link"] as String).toURL().openStream().use { input ->
        archive.outputStream().use { output -> input.copyTo(output) }
    }
    val sha = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { b -> "%02x".format(b) }
    if (sha != pkg["checksum"]) {
        archive.delete()
        throw GradleException("checksum mismatch for ${archive.name}: got $sha, expected ${pkg["checksum"]}")
    }
    delete(jre)
    copy {
        from(if (archive.name.endsWith(".zip")) zipTree(archive) else tarTree(resources.gzip(archive)))
        // Drops the archive's top folder (e.g. jdk-25.0.4.1+1-jre/).
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        includeEmptyDirs = false
        into(jre)
    }
    delete(archive)
    return jre
}

// A trimmed Java runtime with only the agent's modules, made with the JDK 25 that compiles the project. Only for
// this machine's platform.
val agentRuntime by tasks.registering(Exec::class) {
    description = "Makes a trimmed Java runtime for the agent, for this machine's OS and CPU."
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { it.metadata.installationPath }
    val out = layout.buildDirectory.dir("jlink/runtime")
    inputs.property("modules", agentModules)
    outputs.dir(out)
    doFirst {
        delete(out)
        executable = jdk.get().file("bin/jlink").asFile.absolutePath
        args("--add-modules", agentModules, "--strip-debug", "--no-header-files", "--no-man-pages",
            "--compress", "zip-9", "--output", out.get().asFile.absolutePath)
    }
}

// The runtime for -PtargetPlatform when it isn't this machine: Temurin's JRE, see targetJre.
val downloadedRuntime by tasks.registering(Sync::class) {
    description = "Copies Temurin's Java 25 runtime for -PtargetPlatform, downloaded once from Adoptium."
    from(provider { targetJre(targetPlatform) })
    into(layout.buildDirectory.dir("jre/runtime-$targetPlatform"))
    // Some of the JRE's files are read-only, which would stop the next sync from replacing them.
    eachFile { permissions { user { write = true } } }
}

// orbit-agent-<version>-<os>-<arch>.zip: agentDist plus that runtime, so the client needs no Java.
val agentBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Bundles the agent with its installer and a Java runtime for -PtargetPlatform (default: this machine)."
    archiveBaseName = "orbit-agent"
    archiveClassifier = targetPlatform
    destinationDirectory = layout.buildDirectory.dir("dist")
    into("orbit-agent") {
        with(agentFiles)
        from(if (targetPlatform == hostPlatform) agentRuntime else downloadedRuntime) {
            into("runtime")
            // Gradle doesn't keep file modes in zips; Java's launchers and its process helper must stay executable.
            eachFile {
                if (relativeSourcePath.segments.first() == "bin" || name == "jspawnhelper" || name == "jexec") {
                    permissions { unix("rwxr-xr-x") }
                }
            }
        }
    }
}
