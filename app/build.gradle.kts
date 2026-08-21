plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.eiscamera.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eiscamera.app"
        // minSdk 26 (Android 8.0): lets adaptive launcher icons be used
        // without also authoring legacy raster mipmaps, and every Camera2 /
        // EGL14 / coroutines API this module uses is available at 26.
        // Lowering this further is possible later (see docs/ROADMAP.md) but
        // is not a V0.2 engineering requirement — the "universal device"
        // goal in this project is about CAPABILITY adaptation, not chasing
        // the lowest possible Android version.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0" // tracks docs/ROADMAP.md stage, not a marketing version

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed, repo-committed debug key so every build — whether from
            // GitHub Actions (fresh VM every run) or a local machine — signs
            // with the SAME key. Without this, AGP falls back to
            // ~/.android/debug.keystore, which GitHub's ephemeral runners
            // regenerate (with a brand-new random key) on every single run,
            // making each new APK look like a "different app" to Android and
            // forcing an uninstall before every update. This is a debug-only
            // key with a widely-known, intentionally public password — never
            // do this for a release signingConfig.
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
