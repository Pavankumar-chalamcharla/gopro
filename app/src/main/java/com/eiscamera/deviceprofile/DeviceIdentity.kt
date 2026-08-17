package com.eiscamera.deviceprofile

import android.os.Build
import kotlinx.serialization.Serializable

@Serializable
data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    val androidRelease: String,
    val apiLevel: Int,
) {
    companion object {
        fun current(): DeviceIdentity = DeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            fingerprint = Build.FINGERPRINT,
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
        )
    }

    /**
     * Whether a cached profile carrying THIS identity should be considered
     * stale relative to whatever device is currently running. We key on
     * Build.FINGERPRINT rather than just manufacturer/model because an
     * OS/OEM update can change camera HAL or sensor HAL behavior on the
     * exact same physical device (spec section 8: "if hardware/software
     * configuration changes, invalidate or update the profile").
     */
    fun isStaleRelativeToCurrent(): Boolean = this != current()
}
