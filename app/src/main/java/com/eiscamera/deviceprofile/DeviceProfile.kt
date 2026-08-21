package com.eiscamera.deviceprofile

import com.eiscamera.camera.CameraInfo
import com.eiscamera.camera.CameraStreamQualitySnapshot
import com.eiscamera.processing.ProcessingInfo
import com.eiscamera.sensors.SensorInfo
import com.eiscamera.sensors.SensorQualitySnapshot
import kotlinx.serialization.Serializable

/**
 * Persistent, versioned snapshot of everything the Device Capability
 * Scanner discovered about this device (spec section 8).
 *
 * SCHEMA v2 (V0.3) added [sensorQuality]. SCHEMA v3 (V0.4) adds
 * [cameraQuality] — measured real frame rate, jitter, and a likely-dropped-
 * frame heuristic per camera id (see camera/CameraStreamQualityAnalyzer.kt).
 * It's a list, not a single value, because each camera on the device is
 * tested and reported independently (spec section 6).
 *
 * Still NOT included, intentionally:
 *   - gyro<->camera synchronization offset/drift -> needs V0.7
 * That will extend this schema further (bumping SCHEMA_VERSION again)
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
    val cameraQuality: List<CameraStreamQualitySnapshot> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 3
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
