package com.eiscamera.sensors

import com.eiscamera.motion.TimeSeriesCorrelation
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-math analysis for the V0.3 Sensor Quality Test (spec section 5,
 * roadmap V0.3). Every function here takes plain [SensorSample] lists and
 * returns plain data classes — no Android framework dependency — so this
 * entire file is unit-testable on the JVM (see SensorQualityAnalyzerTest).
 *
 * Two independent measurements are implemented:
 *
 * 1. STATIONARY PHASE (analyzeStationary): with the phone motionless,
 *    measures the gyroscope's ACTUAL sampling rate, timestamp jitter, and
 *    per-axis noise/bias — replacing the DECLARED numbers V0.2 could only
 *    report from Sensor.getMinDelay() etc.
 *
 * 2. DYNAMIC RESPONSE PHASE (analyzeDynamicResponse): during a deliberate
 *    motion ("flick"), cross-checks the gyroscope's angular-velocity
 *    signal against an INDEPENDENT angular-velocity estimate obtained by
 *    differentiating the rotation-vector sensor's orientation quaternions
 *    over time. See the kdoc on analyzeDynamicResponse for what this can
 *    and cannot prove — the interpretation has a real caveat, and this
 *    class deliberately does not oversimplify it.
 */
object SensorQualityAnalyzer {

    // =================================================================
    // STATIONARY PHASE
    // =================================================================

    data class AxisStats(val mean: Double, val stdDev: Double, val peakToPeak: Double)

    data class StationaryGyroResult(
        val sampleCount: Int,
        /** MEASURED sampling rate = (sampleCount-1) / (total duration). */
        val measuredRateHz: Double?,
        /** MEASURED standard deviation of inter-sample timestamp intervals, in ms. */
        val timestampJitterMs: Double?,
        val timestampMonotonic: Boolean,
        val timestampNonMonotonicCount: Int,
        val x: AxisStats,
        val y: AxisStats,
        val z: AxisStats,
    ) {
        /** Worst-axis noise stddev (rad/s) — the single number CapabilityEngine compares against a threshold. */
        val worstAxisStdDev: Double get() = maxOf(x.stdDev, y.stdDev, z.stdDev)

        /** Magnitude of the mean (bias) vector across all three axes, in rad/s. */
        val biasMagnitude: Double get() = sqrt(x.mean * x.mean + y.mean * y.mean + z.mean * z.mean)
    }

    /**
     * @param samples raw gyroscope samples (values = [x, y, z] angular
     *   velocity in rad/s), collected while the device was held stationary.
     */
    fun analyzeStationary(samples: List<SensorSample>): StationaryGyroResult {
        require(samples.size >= 2) { "Need at least 2 samples to analyze rate/jitter" }
        val n = samples.size

        var nonMonotonicCount = 0
        val intervalsMs = DoubleArray(n - 1)
        for (i in 1 until n) {
            val dtNs = samples[i].timestampNs - samples[i - 1].timestampNs
            if (dtNs <= 0) nonMonotonicCount++
            intervalsMs[i - 1] = dtNs / 1_000_000.0
        }
        val totalDurationS = (samples.last().timestampNs - samples.first().timestampNs) / 1_000_000_000.0
        val measuredRateHz = if (totalDurationS > 0) (n - 1) / totalDurationS else null

        val xs = DoubleArray(n) { samples[it].values[0].toDouble() }
        val ys = DoubleArray(n) { samples[it].values[1].toDouble() }
        val zs = DoubleArray(n) { samples[it].values[2].toDouble() }

        return StationaryGyroResult(
            sampleCount = n,
            measuredRateHz = measuredRateHz,
            timestampJitterMs = stdDev(intervalsMs),
            timestampMonotonic = nonMonotonicCount == 0,
            timestampNonMonotonicCount = nonMonotonicCount,
            x = axisStats(xs),
            y = axisStats(ys),
            z = axisStats(zs),
        )
    }

    internal fun axisStats(values: DoubleArray): AxisStats {
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val pp = (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)
        return AxisStats(mean = mean, stdDev = sqrt(variance), peakToPeak = pp)
    }

    internal fun stdDev(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }

    // =================================================================
    // DYNAMIC RESPONSE PHASE
    // =================================================================

    data class DynamicResponseResult(
        val gyroSampleCount: Int,
        val rotationVectorSampleCount: Int,
        val overlapDurationS: Double,
        /**
         * Lag, in milliseconds, at which the gyroscope's and rotation
         * vector's angular-velocity-magnitude series correlate best.
         * POSITIVE means the gyroscope's features occur EARLIER than the
         * matching features in the rotation-vector-derived signal (i.e.
         * gyro leads / rotation vector is the delayed one). NEGATIVE means
         * the reverse. Sign convention verified against synthetic data
         * with a known injected delay — see SensorQualityAnalyzerTest.
         */
        val bestLagMs: Double,
        /** Normalized (Pearson) cross-correlation at bestLagMs, range [-1, 1]. */
        val bestCorrelation: Double,
        val gyroPeakMagnitudeRadS: Double,
        val rotationVectorPeakMagnitudeRadS: Double,
    )

    /**
     * Cross-checks the gyroscope's angular-velocity signal against an
     * INDEPENDENT angular-velocity estimate derived by differentiating
     * consecutive rotation-vector orientation quaternions.
     *
     * MATH:
     *   Given two unit quaternions q1 (at time t1) and q2 (at time t2)
     *   describing absolute orientation, the relative rotation between
     *   them is q_delta = conjugate(q1) ⊗ q2 (Hamilton product; a unit
     *   quaternion's inverse equals its conjugate). The rotation ANGLE is
     *   θ = 2·acos(|w_delta|) (see quaternionAngularVelocityMagnitude for
     *   why the absolute value is necessary — real rotation-vector samples
     *   are not guaranteed sign-continuous from one reading to the next),
     *   and the average angular SPEED over the interval is ω = θ / (t2 -
     *   t1). This is the reverse of the usual gyro-integration equation
     *   θ(t) = θ(t0) + ∫ω(t)dt: here we recover ω FROM two known
     *   orientations instead of integrating ω INTO an orientation.
     *
     *   Both series are then compared as MAGNITUDE only (sqrt(x²+y²+z²)
     *   for the gyro, |ω| for the rotation-vector-derived estimate) to
     *   avoid needing to resolve axis-convention/reference-frame
     *   differences between the two sensors — a deliberate simplification
     *   that trades some directional information for robustness.
     *
     *   Because the two sensors report at different rates and different
     *   timestamps, both magnitude series are linearly interpolated onto a
     *   common uniform time grid, then compared at a range of time shifts
     *   ("lags") using Pearson correlation; the lag that maximizes
     *   correlation is reported.
     *
     * INTERPRETATION CAVEAT (do not oversimplify when displaying results):
     *   A very high correlation with near-zero lag is CONSISTENT WITH, but
     *   does NOT by itself PROVE, that the gyroscope signal is synthesized
     *   from the same fusion pipeline that produces the rotation vector —
     *   because on a device with a genuine, high-quality gyro, the OS's
     *   own rotation-vector fusion typically leans heavily on that SAME
     *   real gyro as its primary high-rate input, which would ALSO produce
     *   high correlation. This test is one piece of evidence, meant to be
     *   read alongside the sensor's declared name/vendor strings (V0.2)
     *   and its stationary noise character (this file) — not a standalone
     *   verdict.
     *
     * @return null if there were not enough overlapping samples between
     *   the two sensors to produce a meaningful comparison.
     */
    fun analyzeDynamicResponse(
        gyroSamples: List<SensorSample>,
        rotationVectorQuaternions: List<SensorSample>,
        gridStepMs: Double = 10.0,
        maxLagMs: Double = 300.0,
    ): DynamicResponseResult? {
        if (gyroSamples.size < 3 || rotationVectorQuaternions.size < 3) return null

        val gyroSeries = gyroSamples.map {
            val t = it.timestampNs / 1_000_000.0
            val mag = sqrt(
                it.values[0].toDouble() * it.values[0] +
                    it.values[1].toDouble() * it.values[1] +
                    it.values[2].toDouble() * it.values[2]
            )
            t to mag
        }

        val rvSeries = mutableListOf<Pair<Double, Double>>()
        for (i in 1 until rotationVectorQuaternions.size) {
            val a = rotationVectorQuaternions[i - 1]
            val b = rotationVectorQuaternions[i]
            val dtS = (b.timestampNs - a.timestampNs) / 1_000_000_000.0
            if (dtS <= 0) continue
            val omega = quaternionAngularVelocityMagnitude(a.values, b.values, dtS)
            val midTimeMs = (a.timestampNs + b.timestampNs) / 2.0 / 1_000_000.0
            rvSeries += midTimeMs to omega
        }
        if (rvSeries.size < 2) return null

        val startMs = max(gyroSeries.first().first, rvSeries.first().first)
        val endMs = min(gyroSeries.last().first, rvSeries.last().first)
        if (endMs - startMs < gridStepMs * 5) return null

        val gridSize = ((endMs - startMs) / gridStepMs).toInt()
        if (gridSize < 5) return null
        val grid = DoubleArray(gridSize) { startMs + it * gridStepMs }

        val gyroGrid = TimeSeriesCorrelation.interpolateLinear(gyroSeries, grid)
        val rvGrid = TimeSeriesCorrelation.interpolateLinear(rvSeries, grid)

        val maxLagSteps = (maxLagMs / gridStepMs).toInt()
        val lagResult = TimeSeriesCorrelation.crossCorrelationLag(gyroGrid, rvGrid, maxLagSteps)

        return DynamicResponseResult(
            gyroSampleCount = gyroSamples.size,
            rotationVectorSampleCount = rotationVectorQuaternions.size,
            overlapDurationS = (endMs - startMs) / 1000.0,
            bestLagMs = lagResult.bestLagSteps * gridStepMs,
            bestCorrelation = lagResult.bestCorrelation,
            gyroPeakMagnitudeRadS = gyroSeries.maxOf { it.second },
            rotationVectorPeakMagnitudeRadS = rvSeries.maxOf { it.second },
        )
    }

    /** q1, q2 are (w, x, y, z) unit quaternions. Returns average angular speed in rad/s. */
    internal fun quaternionAngularVelocityMagnitude(q1: FloatArray, q2: FloatArray, dtSeconds: Double): Double {
        val w1 = q1[0].toDouble(); val x1 = q1[1].toDouble(); val y1 = q1[2].toDouble(); val z1 = q1[3].toDouble()
        val w2 = q2[0].toDouble(); val x2 = q2[1].toDouble(); val y2 = q2[2].toDouble(); val z2 = q2[3].toDouble()

        // conjugate(q1) = (w1, -x1, -y1, -z1); Hamilton product q_delta = conj(q1) ⊗ q2.
        // We only need the scalar (w) component to recover the rotation angle — which,
        // for unit quaternions, is algebraically just the 4D dot product of q1 and q2.
        val w = w1 * w2 - (-x1) * x2 - (-y1) * y2 - (-z1) * z2

        // CRITICAL FIX (found via real-device testing — see docs/ROADMAP.md V0.3
        // findings): unit quaternions have a "double cover" — q and -q represent
        // the IDENTICAL physical orientation. Android's rotation-vector sensor
        // does not guarantee a consistent sign/hemisphere from one sample to the
        // next; two samples describing nearly the same orientation can land on
        // opposite hemispheres, making the raw dot product swing sharply negative
        // even though the true rotation between them was tiny. Taking the
        // ABSOLUTE VALUE resolves this: cos(Δθ/2) = |dot(q1,q2)| is invariant to
        // either input's sign and gives the true, shortest relative rotation
        // angle regardless of which sign each sample happened to report. Without
        // this fix, a single sign flip turns a fraction-of-a-degree rotation into
        // one read as ~180°, producing an enormous, physically impossible
        // angular-velocity spike — exactly what a real device test surfaced
        // (a rotation-vector-derived peak of ~1187 rad/s from what should have
        // been at most a few tens of rad/s). Verified numerically before and
        // after this fix; see SensorQualityAnalyzerTest.
        val wAbs = abs(w).coerceAtMost(1.0)
        val angle = 2.0 * acos(wAbs)
        return if (dtSeconds > 0) angle / dtSeconds else 0.0
    }
}
