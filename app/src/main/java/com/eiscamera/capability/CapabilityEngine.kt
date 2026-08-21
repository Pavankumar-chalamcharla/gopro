package com.eiscamera.capability

import android.hardware.Sensor
import com.eiscamera.camera.CameraInfo
import com.eiscamera.camera.CameraStreamQualitySnapshot
import com.eiscamera.orientation.OrientationDriftSnapshot
import com.eiscamera.processing.ProcessingInfo
import com.eiscamera.sensors.SensorInfo
import com.eiscamera.sensors.SensorQualitySnapshot
import com.eiscamera.synchronization.SyncResultSnapshot

/**
 * Converts raw device measurements into a stabilization [CapabilityResult].
 *
 * IMPORTANT — read before extending this class:
 *
 * At V0.2, the only data available was a STATIC capability scan (declared
 * sensor/camera characteristics, no real measurement). V0.3 added measured
 * sensor quality, V0.4 added measured camera-stream quality, and V0.7 (this
 * version) adds an estimated gyro<->camera clock offset. With all three
 * optional measurements present AND passing their thresholds, this engine
 * can now return LEVEL_2_ADVANCED — see [advancedEligibility] for the exact,
 * documented gate. Without all three (or if any fails its threshold), it
 * still returns LEVEL_1_BASIC, same as before.
 *
 * It must NEVER return LEVEL_3/4 — rolling-shutter compensation and full
 * stabilization need lens-profile and rolling-shutter-readout data this
 * project hasn't built yet (roadmap V1.3+). And it must never return
 * LEVEL_2_ADVANCED without ALL THREE measurements passing their documented
 * thresholds (spec section 42: "no false claims") — a device that's only
 * been through V0.2's static scan, or only some of V0.3/V0.4/V0.7, always
 * stays at Basic/provisional, exactly as before.
 */
class CapabilityEngine {

    fun classify(
        sensors: List<SensorInfo>,
        missingCriticalSensors: List<String>,
        cameras: List<CameraInfo>,
        processing: ProcessingInfo,
        sensorQuality: SensorQualitySnapshot? = null,
        cameraQuality: List<CameraStreamQualitySnapshot> = emptyList(),
        syncResult: SyncResultSnapshot? = null,
        orientationDrift: OrientationDriftSnapshot? = null,
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
        }
        if (cameraQuality.isNotEmpty()) {
            reasons += measuredCameraQualityReasoning(cameraQuality)
        }
        if (syncResult != null) {
            reasons += measuredSyncReasoning(syncResult)
        }
        if (orientationDrift != null) {
            reasons += measuredOrientationDriftReasoning(orientationDrift)
        }

        val measuredParts = mutableListOf<String>()
        if (sensorQuality != null) measuredParts += "sensor quality (V0.3)"
        if (cameraQuality.isNotEmpty()) measuredParts += "camera-stream quality (V0.4)"
        if (syncResult != null) measuredParts += "gyro-camera synchronization (V0.7)"

        val eligibility = advancedEligibility(sensorQuality, cameraQuality, syncResult)

        reasons += when {
            measuredParts.isEmpty() ->
                "NOTE: This is a PROVISIONAL classification from static capability scanning only. " +
                    "No sensor-quality test, camera-stream quality test, or gyro-camera synchronization " +
                    "test has run yet. Confirmed classification — and any level above Basic — requires " +
                    "those measurements."
            measuredParts.size < 3 -> {
                val missing = listOf("sensor quality (V0.3)", "camera-stream quality (V0.4)", "gyro-camera synchronization (V0.7)")
                    .filterNot { it in measuredParts }
                val verb = if (measuredParts.size > 1) "have" else "has"
                "NOTE: ${measuredParts.joinToString(" and ")} $verb now been MEASURED, but " +
                    "${missing.joinToString(" and ")} still ${if (missing.size > 1) "haven't" else "hasn't"}. " +
                    "Confirmed classification — and any level above Basic — still requires all three."
            }
            eligibility.eligible ->
                "CONCLUSION: All three measurements (V0.3, V0.4, V0.7) are present and every threshold " +
                    "checked above was met. Classified as ADVANCED with full evidence."
            else ->
                "CONCLUSION: All three measurements (V0.3, V0.4, V0.7) are present, but " +
                    "${eligibility.failureReasons.joinToString("; ")}. Remaining at BASIC — see the " +
                    "MEASURED/WARNING lines above for the specific numbers."
        }

        val level = if (eligibility.eligible) CapabilityLevel.LEVEL_2_ADVANCED else CapabilityLevel.LEVEL_1_BASIC
        return CapabilityResult(level, reasons, fullyEvidenced = eligibility.eligible)
    }

    /**
     * Turns a V0.8 OrientationDriftSnapshot into a documented reasoning
     * line. This is INFORMATIONAL ONLY — deliberately not part of
     * [advancedEligibility]. That gate was scoped around the three
     * measurements (V0.3/V0.4/V0.7) that together answer "can this device
     * do gyro-based EIS at all"; orientation drift is a property of the
     * INTEGRATION pipeline built on top of that answer, not a new input to
     * it. Surfacing it here still matters for transparency (spec section
     * 45: "it must expose limitations"), it just doesn't move the level.
     */
    private fun measuredOrientationDriftReasoning(d: OrientationDriftSnapshot): List<String> {
        val correctedPart = d.driftCorrectedDegrees?.let { ", bias-corrected=%.2f°".format(it) } ?: ""
        val line = "MEASURED: Gyro-integrated orientation drifted %.2f° from the rotation-vector " +
            "reference over a %.1fs test window (uncorrected%s)."
        return listOf(line.format(d.driftUncorrectedDegrees, d.durationS, correctedPart))
    }

    private data class AdvancedEligibility(val eligible: Boolean, val failureReasons: List<String>)

    /**
     * The ONLY place LEVEL_2_ADVANCED can be produced. Requires all three
     * optional measurements to be present AND every one of the following
     * to independently pass its documented threshold (spec section 9:
     * "do NOT use arbitrary scoring... every classification must have
     * documented engineering reasoning"):
     *
     *   - sensor quality (V0.3): jitter and stationary noise both within
     *     their MAX_* thresholds
     *   - camera-stream quality (V0.4): at least one tested camera meets
     *     the FPS floor AND stays within the jitter ceiling
     *   - synchronization (V0.7): the offset estimate is TRUSTED (its own
     *     correlation clears MIN_SYNC_CORRELATION_FOR_TRUSTED_OFFSET) and
     *     the estimated offset itself is within MAX_SYNC_OFFSET_MS_FOR_ADVANCED
     *
     * No partial credit and no averaging across criteria — a single failed
     * check keeps the device at Basic, with the specific reason recorded.
     */
    private fun advancedEligibility(
        sensorQuality: SensorQualitySnapshot?,
        cameraQuality: List<CameraStreamQualitySnapshot>,
        syncResult: SyncResultSnapshot?,
    ): AdvancedEligibility {
        if (sensorQuality == null || cameraQuality.isEmpty() || syncResult == null) {
            return AdvancedEligibility(false, listOf("not all three measurements have been run yet"))
        }

        val failures = mutableListOf<String>()

        val jitterOk = sensorQuality.timestampJitterMs?.let {
            it <= CapabilityThresholds.MAX_GYRO_TIMESTAMP_JITTER_MS_FOR_ADVANCED
        } ?: false
        if (!jitterOk) failures += "gyro timestamp jitter did not meet the Advanced threshold"

        val noiseOk = sensorQuality.stationaryNoiseStdDevRadS <= CapabilityThresholds.MAX_GYRO_STATIONARY_STD_DEV_RAD_S
        if (!noiseOk) failures += "gyro stationary noise did not meet the Advanced threshold"

        val cameraOk = cameraQuality.any { cq ->
            val fpsOk = (cq.measuredFps ?: 0.0) >= CapabilityThresholds.MIN_MEASURED_CAMERA_FPS_FOR_BASIC
            val jOk = cq.frameIntervalJitterMs?.let { it <= CapabilityThresholds.MAX_CAMERA_FRAME_JITTER_MS_FOR_ADVANCED } ?: false
            fpsOk && jOk
        }
        if (!cameraOk) failures += "no tested camera met both the FPS floor and jitter ceiling"

        val syncCorrelationOk = (syncResult.correlation ?: 0.0) >= CapabilityThresholds.MIN_SYNC_CORRELATION_FOR_TRUSTED_OFFSET
        val syncOffsetOk = syncResult.estimatedOffsetMs?.let {
            kotlin.math.abs(it) <= CapabilityThresholds.MAX_SYNC_OFFSET_MS_FOR_ADVANCED
        } ?: false
        if (!syncCorrelationOk) failures += "the sync offset estimate's own correlation was too low to trust"
        if (syncCorrelationOk && !syncOffsetOk) failures += "the estimated gyro-camera offset exceeded the Advanced threshold"

        return AdvancedEligibility(failures.isEmpty(), failures)
    }

    /**
     * Turns a V0.7 SyncResultSnapshot into documented reasoning lines.
     */
    private fun measuredSyncReasoning(s: SyncResultSnapshot): List<String> {
        val lines = mutableListOf<String>()
        lines += "AVAILABLE (camera ${s.cameraId}): declared timestamp source = " +
            "${s.cameraTimestampSource}" +
            if (s.cameraTimestampSource == "REALTIME") {
                " (platform-guaranteed same clock domain as the gyroscope)."
            } else {
                " (NO platform guarantee — the offset below is a best-effort empirical estimate)."
            }

        if (s.estimatedOffsetMs != null && s.correlation != null) {
            lines += "MEASURED (camera ${s.cameraId}): estimated gyro<->camera offset = " +
                "${"%.1f".format(s.estimatedOffsetMs)} ms, at correlation r=" +
                "${"%.3f".format(s.correlation)} (from ${s.gyroSampleCount} gyro samples, " +
                "${s.cameraFrameCount} camera frames)."

            if (s.correlation < CapabilityThresholds.MIN_SYNC_CORRELATION_FOR_TRUSTED_OFFSET) {
                lines += "WARNING (camera ${s.cameraId}): correlation is below " +
                    "MIN_SYNC_CORRELATION_FOR_TRUSTED_OFFSET " +
                    "(${CapabilityThresholds.MIN_SYNC_CORRELATION_FOR_TRUSTED_OFFSET}) — this offset " +
                    "estimate is not trusted."
            } else if (kotlin.math.abs(s.estimatedOffsetMs) > CapabilityThresholds.MAX_SYNC_OFFSET_MS_FOR_ADVANCED) {
                lines += "WARNING (camera ${s.cameraId}): estimated offset exceeds " +
                    "MAX_SYNC_OFFSET_MS_FOR_ADVANCED (${CapabilityThresholds.MAX_SYNC_OFFSET_MS_FOR_ADVANCED} ms)."
            }
        } else {
            lines += "ESTIMATED (camera ${s.cameraId}): offset estimate did not produce a usable result " +
                "(insufficient overlapping samples between gyroscope and camera motion)."
        }
        return lines
    }

    /**
     * Turns a V0.4 CameraStreamQualitySnapshot list into documented
     * reasoning lines, one camera at a time (spec section 6: evaluate
     * cameras independently). Does not decide the CapabilityLevel itself
     * — see [advancedEligibility] for the actual gate.
     */
    private fun measuredCameraQualityReasoning(snapshots: List<CameraStreamQualitySnapshot>): List<String> {
        val lines = mutableListOf<String>()
        for (s in snapshots) {
            lines += "MEASURED (camera ${s.cameraId}): ${s.frameCount} frames captured over the test " +
                "window; measured FPS=${s.measuredFps?.let { "%.1f".format(it) } ?: "unknown"}, " +
                "jitter=${s.frameIntervalJitterMs?.let { "%.3f ms".format(it) } ?: "unknown"}, " +
                "likely dropped=${s.likelyDroppedFrames} (ESTIMATED via interval-outlier heuristic, " +
                "not a platform-guaranteed count)."

            s.measuredFps?.let { fps ->
                val verdict = if (fps >= CapabilityThresholds.MIN_MEASURED_CAMERA_FPS_FOR_BASIC) {
                    "meets"
                } else {
                    "is BELOW"
                }
                lines += "MEASURED (camera ${s.cameraId}): sustained FPS $verdict the " +
                    "${CapabilityThresholds.MIN_MEASURED_CAMERA_FPS_FOR_BASIC} fps floor for Basic EIS."
            }

            s.frameIntervalJitterMs?.let { jitter ->
                if (jitter > CapabilityThresholds.MAX_CAMERA_FRAME_JITTER_MS_FOR_ADVANCED) {
                    lines += "NOTE (camera ${s.cameraId}): frame-interval jitter exceeds " +
                        "MAX_CAMERA_FRAME_JITTER_MS_FOR_ADVANCED " +
                        "(${CapabilityThresholds.MAX_CAMERA_FRAME_JITTER_MS_FOR_ADVANCED} ms)."
                }
            }

            if (s.frameCount > 0) {
                val droppedRatio = s.likelyDroppedFrames.toDouble() / s.frameCount
                if (droppedRatio > CapabilityThresholds.MAX_LIKELY_DROPPED_FRAME_RATIO) {
                    lines += "WARNING (camera ${s.cameraId}): likely-dropped-frame ratio " +
                        "(${"%.1f".format(droppedRatio * 100)}%) exceeds " +
                        "MAX_LIKELY_DROPPED_FRAME_RATIO " +
                        "(${(CapabilityThresholds.MAX_LIKELY_DROPPED_FRAME_RATIO * 100).toInt()}%)."
                }
            }
        }
        return lines
    }

    /**
     * Turns a V0.3 SensorQualitySnapshot into documented reasoning lines.
     * Does not decide the CapabilityLevel itself — see
     * [advancedEligibility] for the actual gate.
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
