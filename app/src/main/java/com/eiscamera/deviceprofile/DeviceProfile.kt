package com.eiscamera.deviceprofile

import com.eiscamera.camera.CameraInfo
import com.eiscamera.camera.CameraStreamQualitySnapshot
import com.eiscamera.orientation.OrientationDriftSnapshot
import com.eiscamera.processing.ProcessingInfo
import com.eiscamera.sensors.SensorInfo
import com.eiscamera.sensors.SensorQualitySnapshot
import com.eiscamera.synchronization.SyncResultSnapshot
import kotlinx.serialization.Serializable

/**
 * Persistent, versioned snapshot of everything the Device Capability
 * Scanner discovered about this device (spec section 8).
 *
 * SCHEMA v2 (V0.3) added [sensorQuality]. SCHEMA v3 (V0.4) added
 * [cameraQuality]. SCHEMA v4 (V0.7) added [syncResult]. SCHEMA v5 (V0.8)
 * adds [orientationDrift] — an empirical measurement of how far a pure
 * gyro-integrated orientation drifts from the phone's own fused reference
 * over a real test window (see orientation/OrientationDriftAnalyzer.kt).
 * Unlike sensorQuality/cameraQuality/syncResult, this does NOT feed
 * CapabilityEngine's Advanced-level gate — it's informational evidence
 * about the orientation-estimation pipeline itself, not one of the three
 * measurements that gate was originally scoped around. See
 * CapabilityEngine kdoc.
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
    val syncResult: SyncResultSnapshot? = null,
    val orientationDrift: OrientationDriftSnapshot? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 5
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
