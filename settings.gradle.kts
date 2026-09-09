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
        // zxing-android-embedded (QR scanner) is published to JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "GPSTrack"
include(":app")
