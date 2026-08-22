package com.eiscamera.orientation

import com.eiscamera.sensors.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OrientationDriftAnalyzerTest {

    @Test
    fun `bias correction reduces measured drift to near zero`() {
        val trueOmegaZ = 2.0 // rad/s about Z only
        val bias = doubleArrayOf(0.05, -0.02, 0.01)
        val rateHz = 200.0
        val n = 200
        val dtNs = (1_000_000_000.0 / rateHz).toLong()

        val gyroSamples = (0 until n).map { i ->
            val mx = 0.0 + bias[0]
            val my = 0.0 + bias[1]
            val mz = trueOmegaZ + bias[2]
            SensorSample(i * dtNs, floatArrayOf(mx.toFloat(), my.toFloat(), mz.toFloat()))
        }

        // Ground truth computed from the ACTUAL integrated window (first to last
        // gyro sample), not an independently-assumed duration — keeps the
        // reference exactly consistent with what the integrator will produce.
        val actualIntegratedTimeS = (gyroSamples.last().timestampNs - gyroSamples.first().timestampNs) / 1e9
        val trueAngle = trueOmegaZ * actualIntegratedTimeS
        val trueFinalQ = floatArrayOf(cos(trueAngle / 2).toFloat(), 0f, 0f, sin(trueAngle / 2).toFloat())

        val referenceSamples = listOf(
            SensorSample(gyroSamples.first().timestampNs, floatArrayOf(1f, 0f, 0f, 0f)),
            SensorSample(gyroSamples.last().timestampNs, trueFinalQ),
        )

        val result = OrientationDriftAnalyzer.analyzeDrift(gyroSamples, referenceSamples, biasRadS = bias)
        requireNotNull(result)

        assertTrue("uncorrected drift should be noticeably nonzero, got ${result.driftUncorrectedRad}", result.driftUncorrectedRad > 0.01)
        // Threshold is 1e-3, not exactly 0: SensorSample.values is a FloatArray
        // (32-bit), matching real Android sensor data, which introduces roughly
        // 1e-4 radians of unavoidable rounding noise even with perfect bias
        // correction — verified numerically (see project math-verification
        // notes) before relaxing this from an unrealistic 1e-6. 1e-3 keeps a
        // wide margin above that noise floor while remaining ~50x tighter than
        // the uncorrected drift, so the test still meaningfully proves
        // correction is working, not just loosely passing.
        assertTrue("bias-corrected drift should be near zero, got ${result.driftCorrectedRad}", result.driftCorrectedRad!! < 1e-3)
    }

    @Test
    fun `analyzeDrift returns null with too few samples`() {
        val gyro = listOf(SensorSample(0L, floatArrayOf(0f, 0f, 0f)))
        val ref = listOf(SensorSample(0L, floatArrayOf(1f, 0f, 0f, 0f)))
        assertEquals(null, OrientationDriftAnalyzer.analyzeDrift(gyro, ref))
    }

    @Test
    fun `analyzeDrift without bias still returns an uncorrected result`() {
        val gyro = listOf(
            SensorSample(0L, floatArrayOf(0f, 0f, 1f)),
            SensorSample(500_000_000L, floatArrayOf(0f, 0f, 1f)),
        )
        val ref = listOf(
            SensorSample(0L, floatArrayOf(1f, 0f, 0f, 0f)),
            SensorSample(500_000_000L, floatArrayOf(1f, 0f, 0f, 0f)),
        )
        val result = OrientationDriftAnalyzer.analyzeDrift(gyro, ref, biasRadS = null)
        requireNotNull(result)
        assertEquals(null, result.driftCorrectedRad)
    }
}
