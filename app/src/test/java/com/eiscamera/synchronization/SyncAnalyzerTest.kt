package com.eiscamera.synchronization

import com.eiscamera.sensors.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tests for SyncAnalyzer. The offset-estimation math (epoch difference
 * minus cross-correlation lag) and the SLERP formula were both verified
 * numerically in Python before this file was written — these tests
 * reproduce the same synthetic scenarios in Kotlin as permanent regression
 * coverage.
 */
class SyncAnalyzerTest {

    // -----------------------------------------------------------------
    // Offset estimation
    // -----------------------------------------------------------------

    private fun motionAt(tSeconds: Double): Double = 2.0 * (1.0 - cos(2.0 * PI * tSeconds / 3.0))

    @Test
    fun `estimateOffset recovers a known offset despite unrelated clock epochs`() {
        // Two ARBITRARY, unrelated clock epochs — this is the whole point
        // of the test: gyro and camera clocks may not share an epoch at all.
        val gyroEpochNs = 500_000_000_000L
        val cameraEpochNs = 12_300_000_000L
        val trueProcessingDelayNs = 45_000_000L // camera genuinely reports events 45ms "late"

        val gyroRateHz = 200.0
        val gyroSamples = (0 until (3.0 * gyroRateHz).toInt()).map { i ->
            val tPhys = i / gyroRateHz
            val ts = (tPhys * 1e9).toLong() + gyroEpochNs
            SensorSample(ts, floatArrayOf(0f, 0f, motionAt(tPhys).toFloat()))
        }

        val cameraRateHz = 50.0
        val cameraSamples = (0 until (3.0 * cameraRateHz).toInt()).map { i ->
            val tPhys = i / cameraRateHz
            val ts = (tPhys * 1e9).toLong() + cameraEpochNs + trueProcessingDelayNs
            CameraMotionSample(ts, motionAt(tPhys))
        }

        val result = SyncAnalyzer.estimateOffset(gyroSamples, cameraSamples)
        requireNotNull(result)

        val trueOffsetMs = (gyroEpochNs - cameraEpochNs - trueProcessingDelayNs) / 1_000_000.0

        assertTrue("expected high correlation, got ${result.correlation}", result.correlation >= 0.95)
        assertEquals(trueOffsetMs, result.estimatedOffsetMs, 15.0)
    }

    @Test
    fun `estimateOffset returns null with too few samples`() {
        val gyro = listOf(SensorSample(0L, floatArrayOf(0f, 0f, 0f)))
        val camera = listOf(CameraMotionSample(0L, 0.0))
        assertEquals(null, SyncAnalyzer.estimateOffset(gyro, camera))
    }

    // -----------------------------------------------------------------
    // SLERP interpolation
    // -----------------------------------------------------------------

    private fun qz(angle: Double): FloatArray =
        floatArrayOf(cos(angle / 2).toFloat(), 0f, 0f, sin(angle / 2).toFloat())

    @Test
    fun `slerp interpolates the correct fractional angle`() {
        val q1 = qz(0.0)
        val q2 = qz(1.0) // 1 radian rotation about Z

        for (frac in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val queryTimeMs = frac * 100.0
            val r = SyncAnalyzer.slerp(q1, 0.0, q2, 100.0, queryTimeMs)
            val recoveredAngle = 2.0 * kotlin.math.acos(abs(r[0]).coerceAtMost(1.0))
            assertEquals("frac=$frac", frac * 1.0, recoveredAngle, 0.01)
        }
    }

    @Test
    fun `slerp is immune to a sign-flipped second quaternion`() {
        val q1 = qz(0.0)
        val q2 = qz(1.0)
        val q2Flipped = floatArrayOf(-q2[0], -q2[1], -q2[2], -q2[3])

        val normal = SyncAnalyzer.slerp(q1, 0.0, q2, 100.0, 50.0)
        val flipped = SyncAnalyzer.slerp(q1, 0.0, q2Flipped, 100.0, 50.0)

        // Same rotation, possibly represented with an overall sign flip.
        val sameDirectly = normal.indices.all { abs(normal[it] - flipped[it]) < 1e-6 }
        val sameFlipped = normal.indices.all { abs(normal[it] + flipped[it]) < 1e-6 }
        assertTrue(sameDirectly || sameFlipped)
    }

    @Test
    fun `estimateOrientationAt returns null outside the collected range`() {
        val samples = listOf(
            SensorSample(1000L, qz(0.0)),
            SensorSample(2000L, qz(1.0)),
        )
        assertEquals(null, SyncAnalyzer.estimateOrientationAt(samples, 500L))
        assertEquals(null, SyncAnalyzer.estimateOrientationAt(samples, 2500L))
    }

    @Test
    fun `estimateOrientationAt interpolates within the collected range`() {
        val samples = listOf(
            SensorSample(0L, qz(0.0)),
            SensorSample(1_000_000_000L, qz(1.0)), // 1 second later, 1 radian rotated
        )
        val result = SyncAnalyzer.estimateOrientationAt(samples, 500_000_000L)
        requireNotNull(result)
        val recoveredAngle = 2.0 * kotlin.math.acos(abs(result[0]).coerceAtMost(1.0))
        assertEquals(0.5, recoveredAngle, 0.01)
    }
}
