import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release builds are signed with MediaSync's release key, which lives outside the repository
// (scripts/make-release-key.sh makes one): a properties file with storeFile, storePassword,
// keyAlias and keyPassword, ~/.android/mediasync-release.properties unless -Pmediasync.signing=FILE.
// Without it, release builds come out unsigned.
val releaseSigning = file(
    providers.gradleProperty("mediasync.signing").orNull
        ?: "${System.getProperty("user.home")}/.android/mediasync-release.properties",
).takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.tlisonbee.mediasync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tlisonbee.mediasync"
        minSdk = 33
        targetSdk = 36
        versionCode = 18
        versionName = "0.7.1"
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

    buildTypes {
        release {
            // Not shrunk: kotlinx.serialization and jaudiotagger rely on reflection.
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
    }

    // The instrumented round-trip test runs on the same fixtures as :shared's tests.
    sourceSets.getByName("androidTest").assets.directories.add("../shared/src/test/resources")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    // Google's code scanner: scans the pairing QR code without a camera permission.
    implementation(libs.play.services.code.scanner)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
