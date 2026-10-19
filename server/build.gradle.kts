plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

version = "0.3.0"

// The version names the release tarball, not the jar: install.sh checks lib/server.jar.
tasks.jar { archiveVersion = "" }

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

// The release tarball installs itself: unpack it and run `sudo ./install.sh`.
distributions {
    main {
        contents {
            from("../deploy/install.sh") { filePermissions { unix("rwxr-xr-x") } }
            from("../deploy/mediasync.service")
            // Optional backups (backup/install.sh), and the setup for the machine that holds them.
            from("../deploy/backup") {
                into("backup")
                exclude("*.sh")
            }
            from("../deploy/backup") {
                into("backup")
                include("*.sh")
                filePermissions { unix("rwxr-xr-x") }
            }
        }
    }
}

// Java 17 bytecode like :shared, so the desktop client's tests can run this server in-process.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

application {
    mainClass = "com.tlisonbee.mediasync.server.MainKt"
    applicationName = "mediasync-server"
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)
    implementation(libs.zxing.core)
    implementation(libs.metadata.extractor)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.cio)
}

tasks.test {
    useJUnit()
    // Real encoded files for the media-hash checks, shared with :shared's tests.
    systemProperty("mediasync.fixtures", rootProject.file("shared/src/test/resources/fixtures").absolutePath)
}
