pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MediaSync"
include(":shared", ":spike", ":server", ":desktop")

// The Android app needs the Android SDK (ANDROID_HOME, or sdk.dir in local.properties). Without
// one, the server and the desktop client still build; the app comes from a release instead.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.isFile }?.readLines()
        ?.firstNotNullOfOrNull { it.trim().removePrefix("sdk.dir=").takeIf { dir -> dir != it.trim() } }
if (androidSdk != null && file(androidSdk).isDirectory) include(":app")
