package com.eiscamera.orientation

import com.eiscamera.sensors.SensorSample

/**
 * Empirically measures how far a pure gyro-integrated orientation drifts
 * from the phone's own fused rotation-vector orientation, over a real
 * collection window — a genuine, on-device number for THIS device's gyro,
 * not a general assumption (spec section 42: no false claims).
 *
 * Reuses SensorQualityCollector (V0.3) for data collection — it already
 * gathers gyro and rotation-vector samples concurrently, which is exactly
 * what this needs.
 */
object OrientationDriftAnalyzer {

    data class DriftResult(
        val gyroSampleCount: Int,
        val referenceSampleCount: Int,
        val durationS: Double,
        /** Angular difference (radians) between the gyro-integrated orientation and
         *  the rotation-vector reference, at the end of the window, WITHOUT bias correction. */
        val driftUncorrectedRad: Double,
        /** Same, but with a per-axis stationary bias (from V0.3) subtracted from every
         *  sample before integrating. Null if no bias was supplied. */
        val driftCorrectedRad: Double?,
    )

    /**
     * @param gyroSamples raw gyroscope samples (rad/s), collected during real motion.
     * @param referenceQuaternions rotation-vector orientation samples (w,x,y,z),
     *   collected over the SAME window — used both as the integration's starting
     *   orientation (so any initial-orientation mismatch isn't counted as drift)
     *   and as the ground truth to compare the integrated result against at the end.
     * @param biasRadS optional per-axis stationary bias [x,y,z] (rad/s), from
     *   SensorQualityAnalyzer.StationaryGyroResult's x/y/z.mean — subtracted from
     *   every gyro sample before integrating, when supplied.
     * @return null if either series is too short to produce a meaningful comparison.
     */
    fun analyzeDrift(
        gyroSamples: List<SensorSample>,
        referenceQuaternions: List<SensorSample>,
        biasRadS: DoubleArray? = null,
    ): DriftResult? {
        if (gyroSamples.size < 2 || referenceQuaternions.size < 2) return null

        val startTimeNs = gyroSamples.first().timestampNs
        val endTimeNs = gyroSamples.last().timestampNs
        val durationS = (endTimeNs - startTimeNs) / 1_000_000_000.0
        if (durationS <= 0) return null

        // Start integrating from the reference orientation's closest-preceding
        // sample, so the comparison isolates DRIFT rather than any pre-existing
        // orientation offset between the two sensors.
        val startQuaternionSample = referenceQuaternions.lastOrNull { it.timestampNs <= startTimeNs }
            ?: referenceQuaternions.first()
        val startQuaternion = doubleArrayOf(
            startQuaternionSample.values[0].toDouble(),
            startQuaternionSample.values[1].toDouble(),
            startQuaternionSample.values[2].toDouble(),
            startQuaternionSample.values[3].toDouble(),
        )

        val uncorrectedFinal = integrateSeries(startQuaternion, gyroSamples, bias = null)
        val correctedFinal = biasRadS?.let { integrateSeries(startQuaternion, gyroSamples, bias = it) }

        val referenceFinalSample = referenceQuaternions.lastOrNull { it.timestampNs <= endTimeNs }
            ?: referenceQuaternions.last()
        val referenceFinal = doubleArrayOf(
            referenceFinalSample.values[0].toDouble(),
            referenceFinalSample.values[1].toDouble(),
            referenceFinalSample.values[2].toDouble(),
            referenceFinalSample.values[3].toDouble(),
        )

        return DriftResult(
            gyroSampleCount = gyroSamples.size,
            referenceSampleCount = referenceQuaternions.size,
            durationS = durationS,
            driftUncorrectedRad = GyroIntegrator.angleBetween(uncorrectedFinal, referenceFinal),
            driftCorrectedRad = correctedFinal?.let { GyroIntegrator.angleBetween(it, referenceFinal) },
        )
    }

    /** Zero-order-hold chaining: each interval integrates using the angular
     *  velocity measured at the START of that interval, held constant across it. */
    private fun integrateSeries(startQuaternion: DoubleArray, samples: List<SensorSample>, bias: DoubleArray?): DoubleArray {
        var q = startQuaternion
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val dtS = (curr.timestampNs - prev.timestampNs) / 1_000_000_000.0
            if (dtS <= 0) continue
            var wx = prev.values[0].toDouble()
            var wy = prev.values[1].toDouble()
            var wz = prev.values[2].toDouble()
            if (bias != null) {
                wx -= bias[0]; wy -= bias[1]; wz -= bias[2]
            }
            q = GyroIntegrator.integrateStep(q, doubleArrayOf(wx, wy, wz), dtS)
        }
        return q
    }
}
