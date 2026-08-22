package com.eiscamera.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tests for OrientationSmoothingFilter per spec section 29 ("Filtering:
 * known input signal -> expected frequency response"). The attenuation
 * values asserted here were independently verified in Python against the
 * theoretical single-pole low-pass formula |H(f)| = 1/sqrt(1+(f/f_c)^2)
 * before this file was written — see the project's math-verification notes.
 */
class OrientationSmoothingFilterTest {

    private fun qz(angle: Double): DoubleArray = doubleArrayOf(cos(angle / 2), 0.0, 0.0, sin(angle / 2))

    /** Recovers the signed rotation angle from a pure-Z-rotation quaternion. */
    private fun signedAngleZ(q: DoubleArray): Double = 2.0 * atan2(q[3], q[0])

    @Test
    fun `alphaForCutoff and cutoffForAlpha are inverses`() {
        val dt = 1.0 / 200.0
        for (cutoff in listOf(0.5, 1.0, 2.0, 5.0, 10.0)) {
            val alpha = OrientationSmoothingFilter.alphaForCutoff(cutoff, dt)
            val recovered = OrientationSmoothingFilter.cutoffForAlpha(alpha, dt)
            assertEquals(cutoff, recovered, 1e-9)
        }
    }

    @Test
    fun `filter attenuation matches the theoretical single-pole frequency response`() {
        val sampleHz = 200.0
        val dt = 1.0 / sampleHz
        val cutoffHz = 2.0
        val amplitudeRad = Math.toRadians(3.0)
        val durationS = 5.0
        val n = (durationS * sampleHz).toInt()
        val alpha = OrientationSmoothingFilter.alphaForCutoff(cutoffHz, dt)

        // (signal frequency Hz) to (expected attenuation ratio) — matches the
        // Python verification: well below cutoff -> near 1.0 (preserved);
        // at cutoff -> ~0.707 (-3dB); well above cutoff -> substantially attenuated.
        val cases = listOf(0.5 to 0.970, 2.0 to 0.707, 8.0 to 0.243)

        for ((sigHz, expectedRatio) in cases) {
            var qSmooth = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
            val rawAngles = mutableListOf<Double>()
            val smoothAngles = mutableListOf<Double>()
            for (i in 0 until n) {
                val t = i * dt
                val angle = amplitudeRad * sin(2 * PI * sigHz * t)
                val qRaw = qz(angle)
                qSmooth = OrientationSmoothingFilter.step(qSmooth, qRaw, alpha)
                rawAngles += angle
                smoothAngles += signedAngleZ(qSmooth)
            }
            val skip = sampleHz.toInt() // skip first second (filter startup transient)
            val inAmp = (rawAngles.drop(skip).max() - rawAngles.drop(skip).min()) / 2
            val outAmp = (smoothAngles.drop(skip).max() - smoothAngles.drop(skip).min()) / 2
            val ratio = outAmp / inAmp
            assertEquals("signal at ${sigHz}Hz", expectedRatio, ratio, 0.02)
        }
    }

    @Test
    fun `filterStream reports zero compensation on the very first sample`() {
        val samples = listOf(
            0L to doubleArrayOf(1.0, 0.0, 0.0, 0.0),
            10_000_000L to qz(0.01),
        )
        val result = OrientationSmoothingFilter.filterStream(samples, cutoffHz = 2.0)
        assertEquals(0.0, result.first().compensationAngleRad, 1e-12)
    }

    @Test
    fun `filterStream handles an empty stream`() {
        assertTrue(OrientationSmoothingFilter.filterStream(emptyList(), cutoffHz = 2.0).isEmpty())
    }
}
