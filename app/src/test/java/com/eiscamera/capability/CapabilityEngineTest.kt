package com.eiscamera.capability

import android.hardware.Sensor
import com.eiscamera.camera.CameraInfo
import com.eiscamera.processing.ProcessingInfo
import com.eiscamera.sensors.SensorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for CapabilityEngine per spec section 29 ("Simulated device
 * profiles -> correct capability classification"). The central invariant
 * under test is spec section 42: the engine must NEVER claim a level above
 * Basic at V0.2, because no measured sensor/camera/sync quality data exists
 * yet to justify it — no matter how good the DECLARED numbers look.
 */
class CapabilityEngineTest {

    private val engine = CapabilityEngine()

    private fun gyro(rateHz: Double? = 400.0) = SensorInfo(
        type = Sensor.TYPE_GYROSCOPE,
        typeName = "Gyroscope (calibrated)",
        name = "Test Gyro",
        vendor = "Test Vendor",
        version = 1,
        resolution = 0.001f,
        maximumRange = 34.9f,
        minDelayUs = if (rateHz != null) (1_000_000 / rateHz).toInt() else 0,
        maxDelayUs = 200_000,
        reportingMode = "CONTINUOUS",
        isWakeUpSensor = false,
        power = 0.5f,
        declaredMaxRateHz = rateHz,
    )

    private fun camera(hwLevel: String = "FULL") = CameraInfo(
        cameraId = "0",
        lensFacing = "BACK",
        sensorOrientationDegrees = 90,
        hardwareLevel = hwLevel,
        pixelArrayWidth = 4000,
        pixelArrayHeight = 3000,
        physicalSensorSizeMm = null,
        focalLengthsMm = listOf(4.7f),
        apertures = listOf(1.8f),
        opticalStabilizationAvailable = false,
        digitalStabilizationAvailable = false,
        availableOutputFormats = emptyList(),
        maxOutputSizePixels = null,
        availableFpsRanges = emptyList(),
        maxDeclaredFps = 60,
        minFrameDurationsNs = emptyMap(),
        exposureTimeRangeNs = null,
        isExternal = false,
        isLogicalMultiCamera = false,
        physicalCameraIds = emptyList(),
        capabilities = emptyList(),
    )

    private fun processing(cores: Int = 8, eglOk: Boolean = true) = ProcessingInfo(
        manufacturer = "TestOEM",
        model = "TestModel",
        device = "testdevice",
        board = "testboard",
        hardware = "testhw",
        androidRelease = "14",
        apiLevel = 34,
        supportedAbis = listOf("arm64-v8a"),
        cpuCoreCount = cores,
        totalMemoryBytes = 8_000_000_000,
        availableMemoryBytes = 3_000_000_000,
        lowRamDevice = false,
        glRenderer = if (eglOk) "Adreno (TM) 730" else null,
        glVendor = if (eglOk) "Qualcomm" else null,
        glVersion = if (eglOk) "OpenGL ES 3.2" else null,
        glExtensions = emptyList(),
        eglQuerySucceeded = eglOk,
        hardwareVideoEncoders = emptyList(),
    )

    @Test
    fun `no gyroscope yields UNSUPPORTED with a fully evidenced reason`() {
        val result = engine.classify(
            sensors = emptyList(),
            missingCriticalSensors = listOf("Gyroscope"),
            cameras = listOf(camera()),
            processing = processing(),
        )
        assertEquals(CapabilityLevel.UNSUPPORTED, result.level)
        assertTrue(result.fullyEvidenced)
        assertTrue(result.reasons.any { it.contains("gyroscope", ignoreCase = true) })
    }

    @Test
    fun `no camera yields UNSUPPORTED`() {
        val result = engine.classify(
            sensors = listOf(gyro()),
            missingCriticalSensors = emptyList(),
            cameras = emptyList(),
            processing = processing(),
        )
        assertEquals(CapabilityLevel.UNSUPPORTED, result.level)
        assertTrue(result.reasons.any { it.contains("camera", ignoreCase = true) })
    }

    @Test
    fun `insufficient CPU cores yields UNSUPPORTED`() {
        val result = engine.classify(
            sensors = listOf(gyro()),
            missingCriticalSensors = emptyList(),
            cameras = listOf(camera()),
            processing = processing(cores = 2),
        )
        assertEquals(CapabilityLevel.UNSUPPORTED, result.level)
        assertTrue(result.reasons.any { it.contains("CPU core", ignoreCase = true) })
    }

    @Test
    fun `low declared gyro rate yields UNSUPPORTED`() {
        val result = engine.classify(
            sensors = listOf(gyro(rateHz = 20.0)),
            missingCriticalSensors = emptyList(),
            cameras = listOf(camera()),
            processing = processing(),
        )
        assertEquals(CapabilityLevel.UNSUPPORTED, result.level)
        assertTrue(result.reasons.any { it.contains("Declared max gyro rate") })
    }

    @Test
    fun `everything minimally sufficient yields provisional LEVEL_1_BASIC`() {
        val result = engine.classify(
            sensors = listOf(gyro(rateHz = 400.0)),
            missingCriticalSensors = emptyList(),
            cameras = listOf(camera()),
            processing = processing(cores = 8, eglOk = true),
        )
        assertEquals(CapabilityLevel.LEVEL_1_BASIC, result.level)
        assertTrue(
            "Level 1 at V0.2 must always be provisional until quality tests exist",
            !result.fullyEvidenced,
        )
    }

    @Test
    fun `engine never returns a level above BASIC without measured quality data`() {
        // Even with a very fast declared gyro rate and a successful GPU
        // probe, V0.2 has no measured sensor/camera/sync quality data — the
        // engine must not claim ADVANCED or higher (spec section 42).
        val result = engine.classify(
            sensors = listOf(gyro(rateHz = 1000.0)),
            missingCriticalSensors = emptyList(),
            cameras = listOf(camera()),
            processing = processing(cores = 8, eglOk = true),
        )
        assertTrue(result.level.ordinal <= CapabilityLevel.LEVEL_1_BASIC.ordinal)
    }
}
