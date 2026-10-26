plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":tray"))
    implementation(libs.javalin)
    implementation(libs.jmdns)
    implementation(libs.flatlaf)
    runtimeOnly(libs.dbus.unixsocket)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "orbit.host.OrbitHost"
}

// The version shown in the About box.
tasks.named<ProcessResources>("processResources") {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("orbit/host/orbit.properties") {
        expand("version" to version)
    }
}

// dbus-java finds its socket transport through ServiceLoader, so the service files must survive shading.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
}

// The logo as a PNG, for jpackage and the application menu (the app draws its icons in code).
val hostIcon by tasks.registering(JavaExec::class) {
    description = "Renders the Orbit logo to build/icon/orbit.png."
    val png = layout.buildDirectory.file("icon/orbit.png")
    classpath = project(":protocol").sourceSets["main"].runtimeClasspath
    mainClass = "orbit.protocol.Logo"
    jvmArgs("-Djava.awt.headless=true")
    outputs.file(png)
    argumentProviders.add(CommandLineArgumentProvider { listOf(png.get().asFile.absolutePath, "256") })
}

// Native installer with a bundled Java runtime. Pass -PjpackageType=deb|rpm for an installer; the default
// app-image is a self-contained directory that needs no packaging tools.
val jpackage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a native Orbit host package with jpackage."
    val shadowJar = tasks.named<Jar>("shadowJar")
    dependsOn(shadowJar, hostIcon)
    val outDir = layout.buildDirectory.dir("jpackage")
    val type = providers.gradleProperty("jpackageType").orElse("app-image")
    inputs.files(shadowJar, hostIcon)
    inputs.property("type", type)
    outputs.dir(outDir)
    doFirst {
        delete(outDir)
        val jar = shadowJar.get().archiveFile.get().asFile
        commandLine(
            "jpackage",
            "--type", type.get(),
            "--name", "Orbit",
            "--app-version", project.version.toString(),
            "--description", "Start and control Moonlight streams from your Sunshine host",
            "--icon", hostIcon.get().outputs.files.singleFile.absolutePath,
            "--input", jar.parentFile.absolutePath,
            "--main-jar", jar.name,
            "--main-class", "orbit.host.OrbitHost",
            "--dest", outDir.get().asFile.absolutePath,
        )
    }
}

// orbit-host-<version>-linux-<arch>.zip: the jpackage app (with its own Java runtime), its installer and icon.
val hostBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Bundles the host app with its installer, for Linux on this machine's CPU."
    onlyIf("the installer is for Linux") { System.getProperty("os.name").lowercase().startsWith("linux") }
    val arch = when (val a = System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> a
    }
    archiveBaseName = "orbit-host"
    archiveClassifier = "linux-$arch"
    destinationDirectory = layout.buildDirectory.dir("dist")
    into("orbit-host") {
        from("dist") {
            include("install-host.sh")
            filePermissions { unix("rwxr-xr-x") }
        }
        from(hostIcon)
        from(jpackage) {
            // Gradle doesn't keep file modes in zips: the launcher, Java's own programs and its process helper must
            // stay executable.
            eachFile {
                val segments = relativeSourcePath.segments
                if (segments.size >= 2 && segments[segments.size - 2] == "bin" || name == "jspawnhelper" ||
                        name == "jexec") {
                    permissions { unix("rwxr-xr-x") }
                }
            }
        }
    }
}
