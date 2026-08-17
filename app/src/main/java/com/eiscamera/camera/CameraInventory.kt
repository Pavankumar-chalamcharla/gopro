package com.eiscamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import com.eiscamera.logging.EisLog

/**
 * Enumerates every camera device the platform exposes via Camera2
 * (CameraManager#getCameraIdList) and extracts static CameraCharacteristics
 * relevant to EIS capability classification.
 *
 * Per spec section 6: "Do not assume all cameras on the same phone have the
 * same capabilities" — every camera id is scanned and classified
 * independently; nothing is inferred from one camera onto another.
 *
 * This class does not open any camera or start a capture session; it only
 * reads static metadata, which does not require the CAMERA runtime
 * permission (see AndroidManifest.xml note).
 */
class CameraInventory(context: Context) {

    private val cameraManager =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun scan(): List<CameraInfo> {
        val ids = try {
            cameraManager.cameraIdList
        } catch (e: Exception) {
            EisLog.e(EisLog.Tag.CAMERA, "Failed to enumerate camera id list", e)
            emptyArray()
        }

        return ids.mapNotNull { id ->
            try {
                extract(id).also {
                    EisLog.d(
                        EisLog.Tag.CAMERA,
                        "Camera $id: ${it.lensFacing}, ${it.pixelArrayWidth}x${it.pixelArrayHeight}, " +
                            "hwLevel=${it.hardwareLevel}"
                    )
                }
            } catch (e: Exception) {
                EisLog.e(EisLog.Tag.CAMERA, "Failed to read characteristics for camera $id", e)
                null
            }
        }
    }

    private fun extract(id: String): CameraInfo {
        val c = cameraManager.getCameraCharacteristics(id)

        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

        val hwLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()

        val oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val oisAvailable = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)

        val eisModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val digitalEisAvailable = eisModes.any { it != CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF }

        val map: StreamConfigurationMap? = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputFormats = map?.outputFormats?.toList() ?: emptyList()

        val maxOutputSize = map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?.let { PixelSize(it.width, it.height) }

        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { FpsRange(it.lower, it.upper) } ?: emptyList()
        val maxFps = fpsRanges.maxOfOrNull { it.maxFps }

        val exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?.let { ExposureRangeNs(it.lower, it.upper) }

        val capabilitiesRaw = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val capabilityNames = capabilitiesRaw.map { capabilityName(it) }
        val isLogical = capabilitiesRaw.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        )

        val physicalIds = if (isLogical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                c.physicalCameraIds.toList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return CameraInfo(
            cameraId = id,
            lensFacing = facing,
            sensorOrientationDegrees = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1,
            hardwareLevel = hwLevel,
            pixelArrayWidth = pixelArray?.width ?: -1,
            pixelArrayHeight = pixelArray?.height ?: -1,
            physicalSensorSizeMm = physicalSize?.let { SensorPhysicalSizeMm(it.width, it.height) },
            focalLengthsMm = focalLengths,
            apertures = apertures,
            opticalStabilizationAvailable = oisAvailable,
            digitalStabilizationAvailable = digitalEisAvailable,
            availableOutputFormats = outputFormats,
            maxOutputSizePixels = maxOutputSize,
            availableFpsRanges = fpsRanges,
            maxDeclaredFps = maxFps,
            minFrameDurationsNs = emptyMap(),
            exposureTimeRangeNs = exposureRange,
            isExternal = facing == "EXTERNAL",
            isLogicalMultiCamera = isLogical,
            physicalCameraIds = physicalIds,
            capabilities = capabilityNames,
        )
    }

    private fun capabilityName(v: Int): String = when (v) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        else -> "CAP_$v"
    }
}
