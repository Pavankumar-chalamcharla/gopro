package com.eiscamera.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests for SensorQualityAnalyzer per spec section 29 ("Filtering: known
 * input signal -> expected frequency response" / "Synchronization: known
 * timestamp offsets -> correct interpolation").
 *
 * The dynamic-response tests construct a fully synthetic, noiseless
 * physical scenario (a smooth rotation about a single axis, with a known
 * closed-form angular-velocity and orientation function) so the expected
 * answer is derivable by hand rather than by re-running the algorithm
 * under test. The core math here was independently cross-checked in
 * Python before this file was written; see project notes.
 */
class SensorQualityAnalyzerTest {

    // -----------------------------------------------------------------
    // Stationary phase
    // -----------------------------------------------------------------

    @Test
    fun `analyzeStationary computes rate, jitter, mean and stddev correctly`() {
        // 6 samples, exactly 10ms apart (100Hz), so rate/jitter are exact.
        val startNs = 1_000_000_000L
        val values = listOf(0.01f, 0.03f, -0.01f, 0.02f, 0.00f, 0.01f)
        val samples = values.mapIndexed { i, v ->
            SensorSample(startNs + i * 10_000_000L, floatArrayOf(v, 0f, 0f))
        }

        val result = SensorQualityAnalyzer.analyzeStationary(samples)

        assertEquals(6, result.sampleCount)
        assertEquals(100.0, result.measuredRateHz!!, 0.5)
        assertEquals(0.0, result.timestampJitterMs!!, 1e-6)
        assertTrue(result.timestampMonotonic)

        val expectedMean = values.map { it.toDouble() }.average()
        assertEquals(expectedMean, result.x.mean, 1e-6)

        val expectedVariance = values.sumOf { (it - expectedMean) * (it - expectedMean) } / values.size
        assertEquals(sqrt(expectedVariance), result.x.stdDev, 1e-6)
    }

    @Test
    fun `analyzeStationary detects non-monotonic timestamps`() {
        val samples = listOf(
            SensorSample(1000L, floatArrayOf(0f, 0f, 0f)),
            SensorSample(2000L, floatArrayOf(0f, 0f, 0f)),
            SensorSample(1500L, floatArrayOf(0f, 0f, 0f)), // goes backwards
            SensorSample(3000L, floatArrayOf(0f, 0f, 0f)),
        )
        val result = SensorQualityAnalyzer.analyzeStationary(samples)
        assertTrue(!result.timestampMonotonic)
        assertEquals(1, result.timestampNonMonotonicCount)
    }

    // -----------------------------------------------------------------
    // Dynamic phase — quaternion differentiation
    // -----------------------------------------------------------------

    @Test
    fun `quaternionAngularVelocityMagnitude recovers a known constant angular rate`() {
        // Rotation about Z at a constant 2 rad/s: q(t) = (cos(wt/2), 0, 0, sin(wt/2)).
        val omega = 2.0
        val t1 = 0.0
        val t2 = 0.5
        val q1 = floatArrayOf(cos(omega * t1 / 2).toFloat(), 0f, 0f, sin(omega * t1 / 2).toFloat())
        val q2 = floatArrayOf(cos(omega * t2 / 2).toFloat(), 0f, 0f, sin(omega * t2 / 2).toFloat())

        val recovered = SensorQualityAnalyzer.quaternionAngularVelocityMagnitude(q1, q2, t2 - t1)
        assertEquals(omega, recovered, 0.01)
    }

    /**
     * Regression test for a real bug found on the OPPO F31 5G (see
     * docs/ROADMAP.md V0.3 findings): unit quaternions have a "double
     * cover" (q and -q represent the identical orientation), and Android's
     * rotation-vector sensor does not guarantee a consistent sign between
     * consecutive samples. A naive angle calculation misreads a sign flip
     * between two nearly-identical orientations as a ~180° rotation,
     * producing an enormous, physically impossible angular-velocity spike
     * (observed on-device: ~1187 rad/s from what should have been a few
     * tens of rad/s at most). This test constructs exactly that scenario —
     * a small true rotation whose second quaternion sample is deliberately
     * sign-flipped — and asserts the SMALL true rate is still recovered.
     */
    @Test
    fun `quaternionAngularVelocityMagnitude is immune to a sign-flipped sample`() {
        val trueAngle = 0.10 // radians — a small, physically reasonable rotation
        val dt = 0.01 // seconds
        val expectedOmega = trueAngle / dt // 10 rad/s

        val q1 = floatArrayOf(1f, 0f, 0f, 0f) // identity
        val q2True = floatArrayOf(
            cos(trueAngle / 2).toFloat(), 0f, 0f, sin(trueAngle / 2).toFloat()
        )
        val q2Flipped = floatArrayOf(-q2True[0], -q2True[1], -q2True[2], -q2True[3])

        val recoveredFromFlipped = SensorQualityAnalyzer.quaternionAngularVelocityMagnitude(q1, q2Flipped, dt)
        val recoveredFromTrue = SensorQualityAnalyzer.quaternionAngularVelocityMagnitude(q1, q2True, dt)

        assertEquals(
            "sign-flipped sample must recover the same small rate as the non-flipped sample",
            expectedOmega, recoveredFromFlipped, 0.01
        )
        assertEquals(expectedOmega, recoveredFromTrue, 0.01)
    }

    // -----------------------------------------------------------------
    // Dynamic phase — end-to-end cross-correlation
    // -----------------------------------------------------------------

    /** Non-negative raised-cosine bump: peaks at 4.0 rad/s at t=1.5s, zero at t=0 and t=3. */
    private fun omegaAt(tSeconds: Double): Double = 2.0 * (1.0 - cos(2.0 * PI * tSeconds / 3.0))

    /** Closed-form integral of omegaAt from 0 to tSeconds (verified: d/dt matches omegaAt). */
    private fun thetaAt(tSeconds: Double): Double = 2.0 * tSeconds - (3.0 / PI) * sin(2.0 * PI * tSeconds / 3.0)

    private fun buildGyroSamples(rateHz: Double, durationS: Double, startNs: Long): List<SensorSample> {
        val dtNs = (1_000_000_000.0 / rateHz).toLong()
        val count = (durationS * rateHz).toInt()
        return (0 until count).map { i ->
            val t = i * dtNs / 1_000_000_000.0
            SensorSample(startNs + i * dtNs, floatArrayOf(0f, 0f, omegaAt(t).toFloat()))
        }
    }

    private fun buildRotationVectorSamples(
        rateHz: Double,
        durationS: Double,
        startNs: Long,
        delaySeconds: Double = 0.0,
    ): List<SensorSample> {
        val dtNs = (1_000_000_000.0 / rateHz).toLong()
        val count = (durationS * rateHz).toInt()
        return (0 until count).map { i ->
            val tSample = i * dtNs / 1_000_000_000.0
            val tTrue = (tSample - delaySeconds).coerceAtLeast(0.0)
            val theta = thetaAt(tTrue)
            val q = floatArrayOf(cos(theta / 2).toFloat(), 0f, 0f, sin(theta / 2).toFloat())
            SensorSample(startNs + i * dtNs, q)
        }
    }

    @Test
    fun `analyzeDynamicResponse finds near-zero lag and high correlation for consistent signals`() {
        val startNs = 5_000_000_000L
        val gyro = buildGyroSamples(rateHz = 200.0, durationS = 3.0, startNs = startNs)
        val rv = buildRotationVectorSamples(rateHz = 50.0, durationS = 3.0, startNs = startNs)

        val result = SensorQualityAnalyzer.analyzeDynamicResponse(gyro, rv)
        requireNotNull(result)

        assertTrue("expected |lag| small, got ${result.bestLagMs}", abs(result.bestLagMs) <= 15.0)
        assertTrue("expected high correlation, got ${result.bestCorrelation}", result.bestCorrelation >= 0.95)
    }

    @Test
    fun `analyzeDynamicResponse detects an artificially introduced lag`() {
        val startNs = 5_000_000_000L
        val gyro = buildGyroSamples(rateHz = 200.0, durationS = 3.0, startNs = startNs)
        val rv = buildRotationVectorSamples(rateHz = 50.0, durationS = 3.0, startNs = startNs, delaySeconds = 0.05)

        val result = SensorQualityAnalyzer.analyzeDynamicResponse(gyro, rv)
        requireNotNull(result)

        assertTrue(
            "expected lag near +50ms, got ${result.bestLagMs}",
            abs(result.bestLagMs - 50.0) <= 15.0
        )
        assertTrue("expected high correlation, got ${result.bestCorrelation}", result.bestCorrelation >= 0.85)
    }

    @Test
    fun `analyzeDynamicResponse returns null with too few samples`() {
        val gyro = listOf(SensorSample(0L, floatArrayOf(0f, 0f, 0f)))
        val rv = listOf(SensorSample(0L, floatArrayOf(1f, 0f, 0f, 0f)))
        assertEquals(null, SensorQualityAnalyzer.analyzeDynamicResponse(gyro, rv))
    }
}
