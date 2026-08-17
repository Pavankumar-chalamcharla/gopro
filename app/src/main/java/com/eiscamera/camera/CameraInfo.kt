package com.eiscamera.camera

import kotlinx.serialization.Serializable

@Serializable
data class PixelSize(val width: Int, val height: Int)

@Serializable
data class FpsRange(val minFps: Int, val maxFps: Int)

@Serializable
data class SensorPhysicalSizeMm(val widthMm: Float, val heightMm: Float)

@Serializable
data class ExposureRangeNs(val minNs: Long, val maxNs: Long)

/**
 * Static characteristics of a single logical camera device, as exposed by
 * [android.hardware.camera2.CameraCharacteristics].
 *
 * Everything here is AVAILABLE / DECLARED information (spec section 41) —
 * none of it is MEASURED. Real stream behavior (actual sustained FPS, frame
 * timing stability, dropped frames) is the job of the not-yet-implemented
 * CameraQualityTest (spec section 6, roadmap V0.4).
 */
@Serializable
data class CameraInfo(
    val cameraId: String,
    val lensFacing: String,
    val sensorOrientationDegrees: Int,
    val hardwareLevel: String,
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val physicalSensorSizeMm: SensorPhysicalSizeMm?,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    /** True if LENS_OPTICAL_STABILIZATION_MODE_ON is among the available OIS modes. */
    val opticalStabilizationAvailable: Boolean,
    /**
     * True if any CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES entry is not
     * OFF — i.e. the vendor's own black-box digital EIS, which is distinct
     * from (and may conflict with) the gyro-based EIS this app implements.
     * See spec section 26 on distinguishing gyro/visual/hybrid EIS.
     */
    val digitalStabilizationAvailable: Boolean,
    val availableOutputFormats: List<Int>,
    val maxOutputSizePixels: PixelSize?,
    val availableFpsRanges: List<FpsRange>,
    val maxDeclaredFps: Int?,
    /**
     * Per-(format,size) minimum frame duration lookup. NOT populated by the
     * static scan — computing it requires calling
     * StreamConfigurationMap#getOutputMinFrameDuration for a *specific*
     * format+resolution, which only makes sense once a concrete recording
     * configuration is chosen (roadmap V0.4 / V0.6). Left explicitly empty
     * rather than omitted so the schema field exists from V0.2 onward.
     */
    val minFrameDurationsNs: Map<String, Long>,
    val exposureTimeRangeNs: ExposureRangeNs?,
    val isExternal: Boolean,
    val isLogicalMultiCamera: Boolean,
    val physicalCameraIds: List<String>,
    val capabilities: List<String>,
)
