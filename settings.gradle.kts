/*
 * LensLink — settings.gradle.kts
 *
 * Two modules:
 *   :camerabridge  — AAR library: cross-compiled libgphoto2/libusb + JNI bridge
 *   :app           — the Jetpack Compose application
 */

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

rootProject.name = "LensLink"

include(":camerabridge")
include(":app")
