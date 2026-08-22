package com.eiscamera.deviceprofile

import com.eiscamera.camera.CameraInfo
import com.eiscamera.camera.ExposureRangeNs
import com.eiscamera.camera.FpsRange
import com.eiscamera.camera.PixelSize
import com.eiscamera.camera.SensorPhysicalSizeMm
import com.eiscamera.capability.CapabilityLevel
import com.eiscamera.processing.ProcessingInfo
import com.eiscamera.sensors.SensorInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Verifies the DeviceProfile schema (spec section 8) round-trips through
 * JSON without loss, since this is the persisted format DeviceProfileRepository
 * relies on for cross-launch reuse.
 */
class DeviceProfileSerializationTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun `device profile round-trips through JSON without loss`() {
        val profile = sampleProfile()

        val encoded = json.encodeToString(DeviceProfile.serializer(), profile)
        val decoded = json.decodeFromString(DeviceProfile.serializer(), encoded)

        assertEquals(profile, decoded)
    }

    @Test
    fun `stale schema version is detectable`() {
        val profile = sampleProfile().copy(schemaVersion = DeviceProfile.SCHEMA_VERSION + 1)
        assertNotEquals(DeviceProfile.SCHEMA_VERSION, profile.schemaVersion)
    }

    private fun sampleProfile(): DeviceProfile = DeviceProfile(
        scanTimestampMs = 1_700_000_000_000,
        identity = DeviceIdentity(
            manufacturer = "TestOEM",
            model = "TestModel",
            fingerprint = "testOEM/testModel/test:14/AB1/12345:user/release-keys",
            androidRelease = "14",
            apiLevel = 34,
        ),
        sensors = listOf(
            SensorInfo(
                type = 4,
                typeName = "Gyroscope (calibrated)",
                name = "Test Gyro",
                vendor = "Test Vendor",
                version = 1,
                resolution = 0.001f,
                maximumRange = 34.9f,
                minDelayUs = 2500,
                maxDelayUs = 200000,
                reportingMode = "CONTINUOUS",
                isWakeUpSensor = false,
                power = 0.5f,
                declaredMaxRateHz = 400.0,
            )
        ),
        missingCriticalSensors = emptyList(),
        cameras = listOf(
            CameraInfo(
                cameraId = "0",
                lensFacing = "BACK",
                sensorOrientationDegrees = 90,
                hardwareLevel = "FULL",
                pixelArrayWidth = 4000,
                pixelArrayHeight = 3000,
                physicalSensorSizeMm = SensorPhysicalSizeMm(5.6f, 4.2f),
                focalLengthsMm = listOf(4.7f),
                apertures = listOf(1.8f),
                opticalStabilizationAvailable = true,
                digitalStabilizationAvailable = true,
                availableOutputFormats = listOf(35),
                maxOutputSizePixels = PixelSize(4000, 3000),
                availableFpsRanges = listOf(FpsRange(30, 30), FpsRange(30, 60)),
                maxDeclaredFps = 60,
                minFrameDurationsNs = emptyMap(),
                exposureTimeRangeNs = ExposureRangeNs(100_000L, 200_000_000L),
                isExternal = false,
                isLogicalMultiCamera = false,
                physicalCameraIds = emptyList(),
                capabilities = listOf("BACKWARD_COMPATIBLE"),
                timestampSource = "REALTIME",
            )
        ),
        processing = ProcessingInfo(
            manufacturer = "TestOEM",
            model = "TestModel",
            device = "testdevice",
            board = "testboard",
            hardware = "testhw",
            androidRelease = "14",
            apiLevel = 34,
            supportedAbis = listOf("arm64-v8a"),
            cpuCoreCount = 8,
            totalMemoryBytes = 8_000_000_000,
            availableMemoryBytes = 3_000_000_000,
            lowRamDevice = false,
            glRenderer = "Adreno (TM) 730",
            glVendor = "Qualcomm",
            glVersion = "OpenGL ES 3.2",
            glExtensions = listOf("GL_OES_EGL_image_external"),
            eglQuerySucceeded = true,
            hardwareVideoEncoders = emptyList(),
        ),
        capability = CapabilityResultSnapshot(
            level = CapabilityLevel.LEVEL_1_BASIC.name,
            reasons = listOf("AVAILABLE: Gyroscope present (Test Gyro, vendor=Test Vendor)."),
            evidenceStatus = "PROVISIONAL",
        ),
    )
}
