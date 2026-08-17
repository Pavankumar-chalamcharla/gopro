package com.eiscamera.deviceprofile

import android.content.Context
import com.eiscamera.logging.EisLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the DeviceProfile as JSON in app-private internal storage
 * (Context#filesDir). No storage permission required; the profile never
 * leaves the device unless the user explicitly exports it (spec section 31,
 * not yet implemented).
 */
class DeviceProfileRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val profileFile: File
        get() = File(context.applicationContext.filesDir, "device_profile.json")

    /**
     * Returns the cached profile only if it exists, matches the current
     * schema version, AND matches the current device identity. Otherwise
     * returns null so the caller re-scans (spec section 8: invalidate on
     * hardware/software change).
     */
    fun load(): DeviceProfile? {
        val file = profileFile
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            val profile = json.decodeFromString(DeviceProfile.serializer(), text)
            if (profile.schemaVersion != DeviceProfile.SCHEMA_VERSION) {
                EisLog.i(
                    EisLog.Tag.PROFILE,
                    "Cached profile schema v${profile.schemaVersion} != current v${DeviceProfile.SCHEMA_VERSION}; discarding"
                )
                return null
            }
            if (profile.identity.isStaleRelativeToCurrent()) {
                EisLog.i(EisLog.Tag.PROFILE, "Cached profile identity does not match current device; discarding")
                return null
            }
            profile
        } catch (e: Exception) {
            EisLog.e(EisLog.Tag.PROFILE, "Failed to load cached device profile", e)
            null
        }
    }

    fun save(profile: DeviceProfile) {
        try {
            profileFile.writeText(json.encodeToString(profile))
            EisLog.i(EisLog.Tag.PROFILE, "Saved device profile (${profileFile.length()} bytes)")
        } catch (e: Exception) {
            EisLog.e(EisLog.Tag.PROFILE, "Failed to save device profile", e)
        }
    }

    fun clear() {
        if (profileFile.exists()) profileFile.delete()
    }
}
