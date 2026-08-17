package com.eiscamera.diagnostics

import android.content.Context
import com.eiscamera.camera.CameraInventory
import com.eiscamera.capability.CapabilityEngine
import com.eiscamera.deviceprofile.CapabilityResultSnapshot
import com.eiscamera.deviceprofile.DeviceIdentity
import com.eiscamera.deviceprofile.DeviceProfile
import com.eiscamera.logging.EisLog
import com.eiscamera.processing.ProcessingInventory
import com.eiscamera.sensors.SensorInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the full V0.2 static device capability scan:
 *   sensors -> cameras -> processing/GPU -> capability engine -> DeviceProfile
 *
 * All hardware inspection runs on Dispatchers.Default, never the UI thread
 * (spec section 28). This is a one-shot inventory, not a continuous sensor
 * stream — continuous gyro recording is a separate, not-yet-implemented
 * component (roadmap V0.5).
 */
class DeviceScanCoordinator(private val context: Context) {

    suspend fun runFullScan(): DeviceProfile = withContext(Dispatchers.Default) {
        EisLog.i(EisLog.Tag.CAPABILITY, "Starting device capability scan")

        val sensorInventory = SensorInventory(context)
        val sensors = sensorInventory.scan()
        val missingCritical = sensorInventory.missingCriticalSensors()

        val cameras = CameraInventory(context).scan()
        val processing = ProcessingInventory(context).scan()

        val capabilityResult = CapabilityEngine().classify(
            sensors = sensors,
            missingCriticalSensors = missingCritical,
            cameras = cameras,
            processing = processing,
        )

        EisLog.i(
            EisLog.Tag.CAPABILITY,
            "Scan complete: level=${capabilityResult.level}, fullyEvidenced=${capabilityResult.fullyEvidenced}"
        )

        DeviceProfile(
            scanTimestampMs = System.currentTimeMillis(),
            identity = DeviceIdentity.current(),
            sensors = sensors,
            missingCriticalSensors = missingCritical,
            cameras = cameras,
            processing = processing,
            capability = CapabilityResultSnapshot(
                level = capabilityResult.level.name,
                reasons = capabilityResult.reasons,
                evidenceStatus = if (capabilityResult.fullyEvidenced) "FULLY_EVIDENCED" else "PROVISIONAL",
            ),
        )
    }
}
