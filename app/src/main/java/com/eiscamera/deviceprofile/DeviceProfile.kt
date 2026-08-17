package com.eiscamera.deviceprofile

import com.eiscamera.camera.CameraInfo
import com.eiscamera.processing.ProcessingInfo
import com.eiscamera.sensors.SensorInfo
import kotlinx.serialization.Serializable

/**
 * Persistent, versioned snapshot of everything the Device Capability
 * Scanner discovered about this device (spec section 8).
 *
 * NOT included at this schema version, intentionally:
 *   - sensorQuality (measured jitter / noise / bias)  -> needs V0.3
 *   - measured camera stream timing quality             -> needs V0.4
 *   - gyro<->camera synchronization offset/drift          -> needs V0.7
 * Those will extend this schema (bumping SCHEMA_VERSION) once implemented,
 * rather than being faked here. See docs/ROADMAP.md.
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
) {
    companion object {
        const val SCHEMA_VERSION = 1
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
