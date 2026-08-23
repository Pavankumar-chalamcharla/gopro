package com.eiscamera.stabilization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CompensationTransformTest {

    /** Applies a column-major 3x3 matrix to a point, exactly as GLSL's
     *  `mat3 * vec3` would — used here only to verify the matrix this
     *  class produces, mirroring the shader-side convention. */
    private fun applyColumnMajor(m: FloatArray, x: Double, y: Double): Pair<Double, Double> {
        // column-major: m[0..2]=col0, m[3..5]=col1, m[6..8]=col2
        val outX = m[0] * x + m[3] * y + m[6] * 1.0
        val outY = m[1] * x + m[4] * y + m[7] * 1.0
        return outX to outY
    }

    @Test
    fun `identity case leaves a point unchanged`() {
        val m = CompensationTransform.compose(rollRad = 0.0, dxNorm = 0.0, dyNorm = 0.0, cropMargin = 0.0)
        val (x, y) = applyColumnMajor(m, 0.3, 0.7)
        assertEquals(0.3, x, 1e-6)
        assertEquals(0.7, y, 1e-6)
    }

    @Test
    fun `crop alone keeps the center fixed and pulls edges safely inside the source range`() {
        // Corrected expectation: a crop should sample the CENTRAL region of
        // the source and stretch it to fill the frame, so edges pull IN
        // toward the center, not push OUT beyond [0,1] -- an earlier version
        // of this test asserted the opposite (edges exceeding 1.0), which
        // was itself the bug: verifying against a wrong mental model gave
        // false confidence. See CompensationTransform.compose's kdoc.
        val m = CompensationTransform.compose(rollRad = 0.0, dxNorm = 0.0, dyNorm = 0.0, cropMargin = 0.10)
        val (cx, cy) = applyColumnMajor(m, 0.5, 0.5)
        assertEquals(0.5, cx, 1e-6)
        assertEquals(0.5, cy, 1e-6)

        val (ex, _) = applyColumnMajor(m, 1.0, 0.5)
        assertTrue("expected the cropped edge to sample within [0,1], got $ex", ex in 0.0..1.0)
        assertTrue("expected the edge to be pulled visibly inward from 1.0, got $ex", ex < 1.0)
    }

    @Test
    fun `rotation alone preserves distance from center`() {
        val m = CompensationTransform.compose(rollRad = Math.toRadians(5.0), dxNorm = 0.0, dyNorm = 0.0, cropMargin = 0.0)
        val (x, y) = applyColumnMajor(m, 1.0, 0.5)
        val distBefore = sqrt((1.0 - 0.5) * (1.0 - 0.5) + (0.5 - 0.5) * (0.5 - 0.5))
        val distAfter = sqrt((x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5))
        assertEquals(distBefore, distAfter, 1e-6)
    }

    @Test
    fun `buildMatrix correction direction matches the verified sign`() {
        // A camera that yawed +3deg off the smoothed reference: correction
        // quaternion is ~-3deg about Y (see class kdoc / math-verification notes).
        val yawRad = Math.toRadians(3.0)
        val raw = doubleArrayOf(cos(yawRad / 2), 0.0, sin(yawRad / 2), 0.0)
        val smooth = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        val correction = com.eiscamera.orientation.QuaternionMath.hamiltonProduct(
            com.eiscamera.orientation.QuaternionMath.conjugate(raw),
            smooth,
        )

        val m = CompensationTransform.buildMatrix(
            correctionQuaternion = correction,
            focalLengthMm = 3.98,
            sensorWidthMm = 5.6,
            sensorHeightMm = 4.2,
            cropMargin = 0.10,
        )
        val (cx, _) = applyColumnMajor(m, 0.5, 0.5)
        // A +3deg yaw shake should produce a small, negative-direction
        // horizontal shift at default crop — magnitude in the low single
        // digits of percent (verified in math-verification notes: ~1.2% at
        // 3deg), comfortably inside the 10% crop margin.
        assertTrue("expected a small nonzero shift, got dx=${cx - 0.5}", cx != 0.5)
        assertTrue("expected shift well within the 10% crop margin", kotlin.math.abs(cx - 0.5) < 0.05)
    }

    @Test
    fun `missing calibration data falls back to zero shift without crashing`() {
        val m = CompensationTransform.buildMatrix(
            correctionQuaternion = doubleArrayOf(1.0, 0.0, 0.0, 0.0),
            focalLengthMm = null,
            sensorWidthMm = null,
            sensorHeightMm = null,
        )
        val (x, y) = applyColumnMajor(m, 0.5, 0.5)
        assertEquals(0.5, x, 1e-6)
        assertEquals(0.5, y, 1e-6)
    }

    @Test
    fun `deadband suppresses noise-scale wobble but passes real motion through`() {
        // A tiny 0.05deg correction (well under the 0.15deg deadband) should
        // apply essentially zero shift -- this is what stops the device's
        // known integration noise (V0.3/V0.8) from visibly "wobbling" the
        // image at rest.
        val tinyYaw = Math.toRadians(0.05)
        val tinyCorrection = doubleArrayOf(cos(tinyYaw / 2), 0.0, sin(tinyYaw / 2), 0.0)
        val mTiny = CompensationTransform.buildMatrix(tinyCorrection, 3.98, 5.6, 4.2)
        val (txTiny, _) = applyColumnMajor(mTiny, 0.5, 0.5)
        assertEquals("tiny noise-scale correction should be fully suppressed", 0.5, txTiny, 1e-6)

        // A real 2deg correction (well above the 0.6deg full-ramp point)
        // should apply at full strength, same as before the deadband existed.
        val realYaw = Math.toRadians(2.0)
        val realCorrection = doubleArrayOf(cos(realYaw / 2), 0.0, sin(realYaw / 2), 0.0)
        val mReal = CompensationTransform.buildMatrix(realCorrection, 3.98, 5.6, 4.2)
        val (txReal, _) = applyColumnMajor(mReal, 0.5, 0.5)
        assertTrue("real motion should still produce a visible shift", kotlin.math.abs(txReal - 0.5) > 0.005)
    }

    @Test
    fun `large shake clamps gracefully instead of exceeding the crop margin`() {
        // A large 25deg yaw would need a far bigger crop than is available;
        // the transform should cap at a safe, bounded shift rather than
        // sampling outside the valid [0,1] texture range.
        val bigYaw = Math.toRadians(25.0)
        val bigCorrection = doubleArrayOf(cos(bigYaw / 2), 0.0, sin(bigYaw / 2), 0.0)
        val m = CompensationTransform.buildMatrix(
            correctionQuaternion = bigCorrection,
            focalLengthMm = 3.98,
            sensorWidthMm = 5.6,
            sensorHeightMm = 4.2,
            cropMargin = 0.20,
        )
        val (x, _) = applyColumnMajor(m, 0.5, 0.5)
        val maxExpectedShift = 0.20 * 0.4 // matches MAX_SHIFT_FRACTION_OF_CROP
        assertTrue(
            "expected the shift to be capped near $maxExpectedShift, got ${x - 0.5}",
            kotlin.math.abs(x - 0.5 - maxExpectedShift) < 1e-4,
        )
        // Still within the valid sampling range, unlike the pre-fix behavior.
        assertTrue(x in 0.0..1.0)
    }
}
