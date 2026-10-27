/*
 * LensLink — top-level build script.
 * Plugin versions are pinned through the version catalog (gradle/libs.versions.toml).
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
