// Root build file. No project-level dependencies live here; everything is
// configured per-module in app/build.gradle.kts.
//
// Versions below were current as of this project's creation (2026).
// Android Studio will offer to upgrade AGP/Kotlin/Compose BOM automatically
// when they drift — accept those upgrades rather than pinning indefinitely.
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
