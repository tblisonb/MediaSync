plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
}

application {
    mainClass = "com.tlisonbee.mediasync.spike.MainKt"
}

// Scratch copies go under spike/build/; paths in --args are relative to where gradlew was started.
tasks.named<JavaExec>("run") {
    workingDir = layout.buildDirectory.get().asFile.also { it.mkdirs() }
    environment("MEDIASYNC_START_DIR", gradle.startParameter.currentDir.absolutePath)
}
