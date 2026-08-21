package com.eiscamera.deviceprofile

import com.eiscamera.camera.CameraInfo
import com.eiscamera.processing.ProcessingInfo
import com.eiscamera.sensors.SensorInfo
import com.eiscamera.sensors.SensorQualitySnapshot
import kotlinx.serialization.Serializable

/**
 * Persistent, versioned snapshot of everything the Device Capability
 * Scanner discovered about this device (spec section 8).
 *
 * SCHEMA v2 (V0.3) adds [sensorQuality] — measured gyroscope jitter,
 * stationary noise/bias, and a dynamic-response cross-check against the
 * rotation-vector sensor (see sensors/SensorQualityAnalyzer.kt). It is
 * nullable because it comes from a separate, user-triggered test
 * (spec section 21 calibration workflow), not the automatic V0.2 scan —
 * a fresh install has a profile with sensorQuality == null until the user
 * runs that test at least once.
 *
 * Still NOT included, intentionally:
 *   - measured camera stream timing quality  -> needs V0.4
 *   - gyro<->camera synchronization offset/drift -> needs V0.7
 * Those will extend this schema further (bumping SCHEMA_VERSION again)
 * once implemented, rather than being faked here. See docs/ROADMAP.md.
 */
@Serializable
data class DeviceProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val scanTimestampMs: Long,
    val identity: DeviceIdentity,
    val sensors: List<SensorInfo>,
    val missingCriticalSensors: List<String>,
    val cameras: List<CameraInfo>,
    val processing: ProcessingInfo,
    val capability: CapabilityResultSnapshot,
    val sensorQuality: SensorQualitySnapshot? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
}

/**
 * JSON-friendly mirror of capability.CapabilityResult. We store the
 * CapabilityLevel enum's name as a plain string (rather than referencing
 * the enum type directly) so the persisted schema does not break if the
 * enum's internal ordinals or package ever change.
 */
@Serializable
data class CapabilityResultSnapshot(
    val level: String,
    val reasons: List<String>,
    val evidenceStatus: String,
)
