import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

version = "0.2.1"

// Runs on any Java 17+ runtime, not just the JDK it was built with.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

application {
    mainClass = "com.tlisonbee.mediasync.desktop.MainKt"
    applicationName = "mediasync-desktop"
    // An always-running background process, but one that reads tags (with cover art) and hashes
    // large files, so it gets more room than Remind's.
    applicationDefaultJvmArgs = listOf(
        "-Xmx256m",
        "-XX:+UseSerialGC",
        "-XX:TieredStopAtLevel=1",
        "-XX:-UsePerfData",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
    )
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.unixsocket)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
    // The end-to-end tests run the real server in-process.
    testImplementation(project(":server"))
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.server.netty)
}

tasks.test {
    useJUnit()
    systemProperty("mediasync.fixtures", rootProject.file("shared/src/test/resources/fixtures").absolutePath)
}

distributions {
    main {
        contents {
            // Same icon as the web UI.
            from("../server/src/main/resources/static/icon.svg") {
                into("share")
                rename { "mediasync.svg" }
            }
        }
    }
}

// ---- Self-contained package: app + a trimmed Java runtime, so other PCs need no Java (as in Remind) ----

// From `jdeps --print-module-deps`, plus java.logging (jaudiotagger's log) and jdk.crypto.ec, which TLS
// needs for the ECDSA certificates Tailscale issues (jdeps can't see either).
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.instrument", "java.logging", "java.net.http", "java.sql", "java.xml",
    "jdk.crypto.ec", "jdk.net", "jdk.security.auth", "jdk.unsupported",
)

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    description = "Builds a trimmed Java runtime for the bundled package."
    val output = layout.buildDirectory.dir("jlink/runtime").get().asFile
    inputs.property("modules", runtimeModules)
    outputs.dir(output)
    val jlink = File(System.getProperty("java.home"), "bin/jlink")
    doFirst { output.deleteRecursively() } // jlink refuses to overwrite
    commandLine(
        jlink, "--add-modules", runtimeModules.joinToString(","),
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
        "--output", output,
    )
}

// The launcher uses the bundled runtime when there is one, otherwise JAVA_HOME / java on PATH.
tasks.startScripts {
    doLast {
        val script = unixScript
        val text = script.readText()
        val anchor = Regex("""(?m)^APP_HOME=.*\n""").find(text)
            ?: error("Start script layout changed; update the bundled-runtime patch")
        val useBundled = "[ -x \"\$APP_HOME/runtime/bin/java\" ] && JAVA_HOME=\"\$APP_HOME/runtime\"\n"
        script.writeText(text.substring(0, anchor.range.last + 1) + useBundled + text.substring(anchor.range.last + 1))
    }
}

tasks.register<Tar>("bundledDist") {
    description = "Self-contained tarball for installing on other Linux machines (x86_64)."
    group = "distribution"
    val arch = System.getProperty("os.arch").let { if (it == "amd64") "x86_64" else it }
    val baseName = "mediasync-desktop-${project.version}-linux-$arch"
    archiveFileName = "$baseName.tar.gz"
    compression = Compression.GZIP
    destinationDirectory = layout.buildDirectory.dir("distributions")
    into(baseName) {
        with(distributions["main"].contents)
        into("runtime") { from(jlinkRuntime) }
    }
    // Archive tasks don't keep the executable bit from the source files, so set it explicitly.
    val executable = Regex(".*/(install\\.sh|bin/[^/]+|runtime/lib/jspawnhelper)")
    inputs.property("executablePattern", executable.pattern)
    eachFile {
        if (executable.matches(path)) permissions { unix("rwxr-xr-x") }
    }
}
