package com.eiscamera.synchronization

import com.eiscamera.motion.TimeSeriesCorrelation
import com.eiscamera.sensors.SensorSample
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-math core of the V0.7 gyro-camera synchronization test (spec
 * section 7). Two problems solved here:
 *
 * 1. estimateOffset: figure out the relationship between the gyroscope's
 *    clock domain and the camera's clock domain, empirically, by
 *    cross-correlating an independent motion signal from each.
 * 2. estimateOrientationAt / slerp: given a (clock-corrected) camera
 *    timestamp, interpolate an orientation estimate from the two nearest
 *    rotation-vector samples.
 *
 * No Android framework dependency — unit-testable on the JVM (see
 * SyncAnalyzerTest). The offset math and SLERP formula were both verified
 * numerically against synthetic data before this file was written.
 */
object SyncAnalyzer {

    data class OffsetEstimate(
        val gyroSampleCount: Int,
        val cameraFrameCount: Int,
        val overlapDurationS: Double,
        /**
         * Milliseconds to ADD to a raw camera SENSOR_TIMESTAMP to convert
         * it into the gyroscope's clock domain:
         *   cameraTimestampInGyroDomainNs = cameraTimestampRawNs + estimatedOffsetMs * 1_000_000
         */
        val estimatedOffsetMs: Double,
        val correlation: Double,
    )

    /**
     * Estimates the time offset between the gyroscope's clock domain and
     * the camera's clock domain by cross-correlating the gyro's
     * angular-velocity magnitude against the camera's frame-to-frame
     * motion-energy signal.
     *
     * APPROACH: both series are first reframed to "milliseconds since
     * their OWN first sample" before correlating. This matters because the
     * two RAW clock domains may not share an epoch at all — this is
     * exactly what CameraInfo.timestampSource == "UNKNOWN" warns about,
     * and a huge, arbitrary difference between raw gyro and camera
     * timestamps is expected in that case, not a bug. The raw first-sample
     * difference (rawEpochDiffMs) is added back in separately; the
     * cross-correlation search only has to find the much smaller RESIDUAL
     * offset (typically tens of ms of genuine pipeline/processing latency
     * difference), not search across an unbounded absolute range. This
     * combination — epoch difference minus cross-correlation lag — was
     * verified against synthetic data with a large (~488 second) arbitrary
     * epoch difference plus a small known processing delay; see
     * SyncAnalyzerTest and the project's math verification notes.
     *
     * CONFIDENCE CAVEAT: if the camera's declared timestampSource is
     * "REALTIME," Android's own platform guarantee already places both
     * clocks in the same domain, and rawEpochDiffMs alone should already
     * be near-correct; the correlation step here mainly catches genuine
     * pipeline-startup skew (this app's gyro listener and camera session
     * not starting at the exact same instant), not a fundamental clock
     * mismatch. If timestampSource is "UNKNOWN," there is no platform
     * guarantee at all, and this whole estimate is a best-effort empirical
     * fallback — CapabilityEngine's reasoning reflects that distinction.
     *
     * @return null if there were not enough overlapping samples to produce
     *   a meaningful comparison.
     */
    fun estimateOffset(
        gyroSamples: List<SensorSample>,
        cameraMotionSamples: List<CameraMotionSample>,
        gridStepMs: Double = 10.0,
        maxRelativeLagMs: Double = 500.0,
    ): OffsetEstimate? {
        if (gyroSamples.size < 3 || cameraMotionSamples.size < 3) return null

        val gyroFirstNs = gyroSamples.first().timestampNs
        val cameraFirstNs = cameraMotionSamples.first().timestampNs
        val rawEpochDiffMs = (gyroFirstNs - cameraFirstNs) / 1_000_000.0

        val gyroSeries = gyroSamples.map {
            val relMs = (it.timestampNs - gyroFirstNs) / 1_000_000.0
            val mag = sqrt(
                it.values[0].toDouble() * it.values[0] +
                    it.values[1].toDouble() * it.values[1] +
                    it.values[2].toDouble() * it.values[2]
            )
            relMs to mag
        }
        val cameraSeries = cameraMotionSamples.map {
            val relMs = (it.timestampNs - cameraFirstNs) / 1_000_000.0
            relMs to it.motionEnergy
        }

        val startMs = max(gyroSeries.first().first, cameraSeries.first().first)
        val endMs = min(gyroSeries.last().first, cameraSeries.last().first)
        if (endMs - startMs < gridStepMs * 5) return null

        val gridSize = ((endMs - startMs) / gridStepMs).toInt()
        if (gridSize < 5) return null
        val grid = DoubleArray(gridSize) { startMs + it * gridStepMs }

        val gyroGrid = TimeSeriesCorrelation.interpolateLinear(gyroSeries, grid)
        val cameraGrid = TimeSeriesCorrelation.interpolateLinear(cameraSeries, grid)

        val maxLagSteps = (maxRelativeLagMs / gridStepMs).toInt()
        val lagResult = TimeSeriesCorrelation.crossCorrelationLag(gyroGrid, cameraGrid, maxLagSteps)
        val relativeLagMs = lagResult.bestLagSteps * gridStepMs

        // finalOffset = rawEpochDiff - relativeLag — derivation and sign
        // verified numerically against synthetic data; see SyncAnalyzerTest.
        val estimatedOffsetMs = rawEpochDiffMs - relativeLagMs

        return OffsetEstimate(
            gyroSampleCount = gyroSamples.size,
            cameraFrameCount = cameraMotionSamples.size,
            overlapDurationS = (endMs - startMs) / 1000.0,
            estimatedOffsetMs = estimatedOffsetMs,
            correlation = lagResult.bestCorrelation,
        )
    }

    // =================================================================
    // ORIENTATION INTERPOLATION
    // =================================================================

    /**
     * Spherical linear interpolation (SLERP) between two orientation
     * quaternions q1 (at t1Ms) and q2 (at t2Ms), evaluated at
     * [queryTimeMs]. This is the "estimate orientation at an arbitrary
     * camera timestamp" mechanism spec section 7 calls for — given the two
     * rotation-vector samples that bracket a (clock-corrected) camera
     * frame timestamp, this interpolates smoothly between them.
     *
     * Deliberately uses ROTATION VECTOR orientation samples, not raw gyro
     * integration — turning raw angular velocity into a full orientation
     * estimate from scratch is a separate, larger concern (roadmap V0.8),
     * and conflating the two would make this function responsible for both
     * problems at once. This solves only "given two known orientations,
     * what's a smooth intermediate one" — synchronization, not integration.
     *
     * Handles quaternion double-cover the same way V0.3's dynamic-response
     * fix does: if the dot product of q1 and q2 is negative, one is
     * flipped to take the shorter interpolation path, since q and -q
     * represent the identical orientation. Verified numerically, including
     * this sign-flip case — see SyncAnalyzerTest.
     */
    fun slerp(q1: FloatArray, t1Ms: Double, q2: FloatArray, t2Ms: Double, queryTimeMs: Double): DoubleArray {
        val frac = if (t2Ms > t1Ms) ((queryTimeMs - t1Ms) / (t2Ms - t1Ms)).coerceIn(0.0, 1.0) else 0.0

        val w1 = q1[0].toDouble(); val x1 = q1[1].toDouble(); val y1 = q1[2].toDouble(); val z1 = q1[3].toDouble()
        var w2 = q2[0].toDouble(); var x2 = q2[1].toDouble(); var y2 = q2[2].toDouble(); var z2 = q2[3].toDouble()

        var dot = w1 * w2 + x1 * x2 + y1 * y2 + z1 * z2
        if (dot < 0.0) {
            w2 = -w2; x2 = -x2; y2 = -y2; z2 = -z2
            dot = -dot
        }
        dot = dot.coerceAtMost(1.0)

        if (dot > 0.9995) {
            // Nearly identical orientations: linear interpolation + re-normalize
            // avoids numerical instability from dividing by sin(theta0) near 0.
            val w = w1 + frac * (w2 - w1)
            val x = x1 + frac * (x2 - x1)
            val y = y1 + frac * (y2 - y1)
            val z = z1 + frac * (z2 - z1)
            val n = sqrt(w * w + x * x + y * y + z * z)
            return if (n > 0) doubleArrayOf(w / n, x / n, y / n, z / n) else doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        }

        val theta0 = acos(dot)
        val sinTheta0 = sin(theta0)
        val theta = theta0 * frac
        val s0 = cos(theta) - dot * sin(theta) / sinTheta0
        val s1 = sin(theta) / sinTheta0

        return doubleArrayOf(
            s0 * w1 + s1 * w2,
            s0 * x1 + s1 * x2,
            s0 * y1 + s1 * y2,
            s0 * z1 + s1 * z2,
        )
    }

    /**
     * Finds the two rotation-vector samples that bracket [queryTimeNs] and
     * SLERPs between them. Returns null if [queryTimeNs] falls outside the
     * collected range (extrapolation is deliberately not attempted).
     */
    fun estimateOrientationAt(
        rotationVectorQuaternions: List<SensorSample>,
        queryTimeNs: Long,
    ): DoubleArray? {
        if (rotationVectorQuaternions.size < 2) return null
        if (queryTimeNs < rotationVectorQuaternions.first().timestampNs) return null
        if (queryTimeNs > rotationVectorQuaternions.last().timestampNs) return null

        for (i in 1 until rotationVectorQuaternions.size) {
            val a = rotationVectorQuaternions[i - 1]
            val b = rotationVectorQuaternions[i]
            if (queryTimeNs in a.timestampNs..b.timestampNs) {
                return slerp(
                    q1 = a.values, t1Ms = a.timestampNs / 1_000_000.0,
                    q2 = b.values, t2Ms = b.timestampNs / 1_000_000.0,
                    queryTimeMs = queryTimeNs / 1_000_000.0,
                )
            }
        }
        return null
    }
}
