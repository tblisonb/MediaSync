plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

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
