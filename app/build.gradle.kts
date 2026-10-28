/*
 * LensLink — app module.
 * Depends on :camerabridge (cross-compiled libgphoto2 + JNI control layer).
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mav.lenslink"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mav.lenslink"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // CI signing: when the KEYSTORE_FILE / KEYSTORE_PASSWORD /
            // KEY_ALIAS / KEY_PASSWORD Gradle properties are provided (e.g.
            // decoded from GitHub Actions secrets), the release APK/AAB is
            // signed; without them the release artifact stays unsigned.
            val ksFile = providers.gradleProperty("KEYSTORE_FILE").orNull
            val ksPass = providers.gradleProperty("KEYSTORE_PASSWORD").orNull
            val keyAlias = providers.gradleProperty("KEY_ALIAS").orNull
            val keyPass = providers.gradleProperty("KEY_PASSWORD").orNull ?: ksPass
            if (ksFile != null && ksPass != null && keyAlias != null) {
                val keystore = rootProject.file(ksFile)
                if (keystore.exists()) {
                    val signing = signingConfigs.create("ci") {
                        storeFile = keystore
                        storePassword = ksPass
                        this.keyAlias = keyAlias
                        this.keyPassword = keyPass
                    }
                    signingConfig = signing
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // With AGP 9 built-in Kotlin, the Kotlin jvmTarget defaults to
    // compileOptions.targetCompatibility — nothing more to configure.

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/versions/**",
        )
        /*
         * Extract native libs into /data/app/.../lib/arm64. The gphoto2
         * camlib/iolib layer (libltdl shim) **opendir()s nativeLibraryDir**
         * to enumerate libptp2.so / liblibusb1.so / libptpip.so and dlopen()s
         * them by path — zip-embedded entries ("base.apk!/lib/arm64-v8a")
         * are invisible to a directory scan, so with extractNativeLibs=false
         * gp_abilities_list_load() reports "Found 0 camera drivers".
         * NB: this setting lives in the app module; the library's own
         * packaging block does not propagate to consumers.
         */
        jniLibs.useLegacyPackaging = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":camerabridge"))

    // BOM-managed Compose stack; material3 is pinned in the catalog for
    // androidx.compose.material3.MaterialShapes (Material 3 Expressive).
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    debugImplementation(libs.compose.ui.tooling)
}
