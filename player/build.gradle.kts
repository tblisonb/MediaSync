import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signed with MediaSync's release key (see app/build.gradle.kts), so the two apps share a signature.
val releaseSigning = file(
    providers.gradleProperty("mediasync.signing").orNull
        ?: "${System.getProperty("user.home")}/.android/mediasync-release.properties",
).takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.tlisonbee.mediasync.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tlisonbee.mediasync.player"
        minSdk = 33
        targetSdk = 36
        versionCode = 8
        versionName = "0.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    // -Pplayer.testBuildType=release runs the instrumented tests against the release build, signed
    // like the installed app, so testing on the phone doesn't mean uninstalling it.
    testBuildType = providers.gradleProperty("player.testBuildType").getOrElse("debug")

    buildTypes {
        release {
            // Not shrunk: jaudiotagger relies on reflection.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.dash)
    implementation(libs.material.color.utilities)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.media3.ffmpeg.decoder)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.media3.exoplayer)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
