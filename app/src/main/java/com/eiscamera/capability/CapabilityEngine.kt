package com.eiscamera.capability

import android.hardware.Sensor
import com.eiscamera.camera.CameraInfo
import com.eiscamera.processing.ProcessingInfo
import com.eiscamera.sensors.SensorInfo
import com.eiscamera.sensors.SensorQualitySnapshot

/**
 * Converts raw device measurements into a stabilization [CapabilityResult].
 *
 * IMPORTANT — read before extending this class:
 *
 * At V0.2, the only data available is a STATIC capability scan:
 *   - which sensors exist, and what rate they DECLARE (not measured)
 *   - static Camera2 characteristics (not measured stream behavior)
 *   - a static processing/GPU inventory (not a performance benchmark)
 *
 * None of the following exist yet, and this engine must not pretend they do:
 *   - measured gyro sampling rate / jitter / noise / bias      (V0.3)
 *   - measured camera stream FPS stability                     (V0.4)
 *   - gyro<->camera timestamp synchronization quality           (V0.7)
 *   - actual GPU/CPU sustained processing performance           (perf test)
 *
 * Therefore this engine can only ever return, at V0.2:
 *   UNSUPPORTED                  — when a hard requirement is provably absent
 *   LEVEL_1_BASIC (provisional)  — when nothing rules it out, but with
 *                                    fullyEvidenced = false
 *
 * It must NEVER return LEVEL_2/3/4 at V0.2, because those require measured
 * evidence this build does not yet collect (spec section 42: "no false
 * claims"). Once V0.3/V0.4/V0.7 land, this file must be EXTENDED — not by
 * loosening what LEVEL_1_BASIC means, but by adding real MEASURED evidence
 * that can justify higher levels.
 */
class CapabilityEngine {

    fun classify(
        sensors: List<SensorInfo>,
        missingCriticalSensors: List<String>,
        cameras: List<CameraInfo>,
        processing: ProcessingInfo,
        sensorQuality: SensorQualitySnapshot? = null,
    ): CapabilityResult {
        val reasons = mutableListOf<String>()

        // --- Hard requirement 1: a gyroscope must exist ---
        val gyro = sensors.firstOrNull {
            it.type == Sensor.TYPE_GYROSCOPE || it.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED
        }
        if (gyro == null) {
            reasons += "AVAILABLE: No gyroscope reported by SensorManager."
            return CapabilityResult(CapabilityLevel.UNSUPPORTED, reasons, fullyEvidenced = true)
        }
        reasons += "AVAILABLE: Gyroscope present (${gyro.name}, vendor=${gyro.vendor})."

        // --- Hard requirement 2: at least one usable camera must exist ---
        val usableCamera = cameras.firstOrNull { it.lensFacing == "BACK" } ?: cameras.firstOrNull()
        if (usableCamera == null) {
            reasons += "AVAILABLE: No camera reported by CameraManager."
            return CapabilityResult(CapabilityLevel.UNSUPPORTED, reasons, fullyEvidenced = true)
        }
        reasons += "AVAILABLE: Camera '${usableCamera.cameraId}' (${usableCamera.lensFacing}, " +
            "hwLevel=${usableCamera.hardwareLevel}) reports " +
            "${usableCamera.pixelArrayWidth}x${usableCamera.pixelArrayHeight}, " +
            "max declared FPS=${usableCamera.maxDeclaredFps ?: "unknown"}."

        if (usableCamera.hardwareLevel == "LEGACY") {
            // LEGACY is a documented Android platform restriction (minimal
            // manual Camera2 control), not a guess about this specific unit.
            reasons += "AVAILABLE: Camera hardware level is LEGACY — Camera2 exposes minimal " +
                "manual control on this level per the Android CameraCharacteristics documentation. " +
                "This does not by itself block EIS, but it does rule out relying on advanced " +
                "per-frame metadata (e.g. precise exposure/frame-duration) this engine would " +
                "otherwise use in later capability tiers."
        }

        // --- Hard requirement 3: minimum processing floor ---
        if (processing.cpuCoreCount < CapabilityThresholds.MIN_CPU_CORES_FOR_BASIC) {
            reasons += "MEASURED: CPU core count = ${processing.cpuCoreCount}, below " +
                "MIN_CPU_CORES_FOR_BASIC (${CapabilityThresholds.MIN_CPU_CORES_FOR_BASIC}). " +
                "Real-time pipeline is unlikely to sustain frame rate on this many cores."
            return CapabilityResult(CapabilityLevel.UNSUPPORTED, reasons, fullyEvidenced = true)
        }
        reasons += "MEASURED: CPU core count = ${processing.cpuCoreCount} " +
            "(>= ${CapabilityThresholds.MIN_CPU_CORES_FOR_BASIC})."

        // --- Weak pre-check on DECLARED (not measured) gyro rate ---
        val declaredRate = gyro.declaredMaxRateHz
        when {
            declaredRate == null -> reasons += "ESTIMATED: Platform does not declare a minimum " +
                "gyro delay (getMinDelay()==0); declared rate unknown. Real rate must be measured (V0.3)."
            declaredRate < CapabilityThresholds.MIN_DECLARED_GYRO_RATE_HZ_FOR_BASIC -> {
                reasons += "AVAILABLE: Declared max gyro rate ~${"%.0f".format(declaredRate)} Hz is " +
                    "below MIN_DECLARED_GYRO_RATE_HZ_FOR_BASIC " +
                    "(${CapabilityThresholds.MIN_DECLARED_GYRO_RATE_HZ_FOR_BASIC} Hz)."
                return CapabilityResult(CapabilityLevel.UNSUPPORTED, reasons, fullyEvidenced = true)
            }
            else -> reasons += "AVAILABLE: Declared max gyro rate ~${"%.0f".format(declaredRate)} Hz " +
                "(>= ${CapabilityThresholds.MIN_DECLARED_GYRO_RATE_HZ_FOR_BASIC} Hz threshold for Basic)."
        }

        if (!processing.eglQuerySucceeded) {
            reasons += "MEASURED: GPU/EGL probe did not succeed. GPU-accelerated stages " +
                "(roadmap V1.1+) would not be available; a CPU-only fallback would be required."
        } else {
            reasons += "MEASURED: GPU/EGL probe succeeded, renderer='${processing.glRenderer}'."
        }

        if (sensorQuality != null) {
            reasons += measuredSensorQualityReasoning(sensorQuality)
            reasons += "NOTE: Sensor quality has now been MEASURED (V0.3), but camera-stream " +
                "quality (V0.4) and gyro-camera synchronization (V0.7) have not. Confirmed " +
                "classification — and any level above Basic — still requires those too."
        } else {
            reasons += "NOTE: This is a PROVISIONAL classification from static capability " +
                "scanning only. No sensor-quality test (jitter/noise/bias), camera-stream " +
                "quality test, or gyro-camera synchronization test has run yet. Confirmed " +
                "classification — and any level above Basic — requires those measurements."
        }

        return CapabilityResult(CapabilityLevel.LEVEL_1_BASIC, reasons, fullyEvidenced = false)
    }

    /**
     * Turns a V0.3 SensorQualitySnapshot into documented reasoning lines.
     * Deliberately does NOT change the returned CapabilityLevel — Advanced+
     * also requires V0.4 (camera quality) and V0.7 (synchronization), which
     * don't exist yet (see docs/ROADMAP.md). This only makes the REASONING
     * more evidence-based ahead of that.
     */
    private fun measuredSensorQualityReasoning(q: SensorQualitySnapshot): List<String> {
        val lines = mutableListOf<String>()

        lines += if (q.timestampMonotonic) {
            "MEASURED: Gyroscope timestamps were monotonic across ${q.stationarySampleCount} stationary samples."
        } else {
            "MEASURED: Gyroscope timestamps were NOT monotonic during the stationary test — " +
                "timing cannot be fully trusted."
        }

        q.measuredRateHz?.let {
            lines += "MEASURED: Actual stationary sampling rate ~${"%.0f".format(it)} Hz " +
                "(compare against the declared rate — a large gap between the two is itself informative)."
        }

        q.timestampJitterMs?.let {
            val verdict = if (it <= CapabilityThresholds.MAX_GYRO_TIMESTAMP_JITTER_MS_FOR_ADVANCED) "low" else "high"
            lines += "MEASURED: Timestamp jitter (stddev of inter-sample interval) = " +
                "${"%.3f".format(it)} ms ($verdict; threshold " +
                "${CapabilityThresholds.MAX_GYRO_TIMESTAMP_JITTER_MS_FOR_ADVANCED} ms)."
        }

        val noiseVerdict = if (q.stationaryNoiseStdDevRadS <= CapabilityThresholds.MAX_GYRO_STATIONARY_STD_DEV_RAD_S) {
            "low"
        } else {
            "high"
        }
        lines += "MEASURED: Stationary noise (worst-axis stddev) = " +
            "${"%.5f".format(q.stationaryNoiseStdDevRadS)} rad/s ($noiseVerdict; threshold " +
            "${CapabilityThresholds.MAX_GYRO_STATIONARY_STD_DEV_RAD_S} rad/s). Bias magnitude = " +
            "${"%.5f".format(q.stationaryBiasRadS)} rad/s."

        if (q.dynamicTestAvailable && q.dynamicCorrelation != null && q.dynamicLagMs != null) {
            lines += "MEASURED: Cross-correlation between the gyroscope and rotation-vector-derived " +
                "angular velocity peaked at r=${"%.3f".format(q.dynamicCorrelation)} at a lag of " +
                "${"%.0f".format(q.dynamicLagMs)} ms."
            if (q.dynamicCorrelation >= CapabilityThresholds.MIN_DYNAMIC_CORRELATION_FOR_CONSISTENT_SIGNAL) {
                lines += "NOTE: High correlation with the OS's own fused orientation estimate is " +
                    "CONSISTENT WITH (but does not by itself prove) the gyroscope signal being " +
                    "derived from the same sensor-fusion pipeline rather than an independent " +
                    "physical measurement. Cross-check against the sensor's declared name/vendor " +
                    "string on the main scan screen."
            }
            if (kotlin.math.abs(q.dynamicLagMs) > CapabilityThresholds.MAX_DYNAMIC_LAG_MS_BEFORE_WARNING) {
                lines += "WARNING: Measured lag (${"%.0f".format(q.dynamicLagMs)} ms) exceeds " +
                    "MAX_DYNAMIC_LAG_MS_BEFORE_WARNING (${CapabilityThresholds.MAX_DYNAMIC_LAG_MS_BEFORE_WARNING} ms) — " +
                    "a meaningful fraction of a video frame at typical capture rates."
            }
        } else {
            lines += "ESTIMATED: Dynamic response cross-check did not produce a usable result " +
                "(insufficient overlapping samples between the gyroscope and rotation vector)."
        }

        return lines
    }
}
