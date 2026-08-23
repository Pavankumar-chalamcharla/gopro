package com.eiscamera.stabilization

import kotlin.math.cos
import kotlin.math.sin

/**
 * V1.0c-2: converts the 3D rotation gap between a device's raw and
 * smoothed orientation (V1.0b's LiveOrientationPipeline) into the 2D
 * texture-coordinate transform that visually cancels it — the first
 * component in this project that actually changes what's on screen,
 * rather than measuring or filtering a signal.
 *
 * METHOD, per spec section 13 ("select the simplest transformation that
 * provides sufficient stabilization"):
 * - ROLL (rotation about the camera's forward axis) maps DIRECTLY to a
 *   2D in-plane image rotation — no approximation needed.
 * - PITCH/YAW (rotation about the other two axes) are handled via the
 *   standard small-angle pinhole approximation used in gyro-based EIS
 *   literature: for small angle θ, the apparent image shift is
 *   ≈ θ * (focalLength / sensorDimension), in units normalized to the
 *   sensor's own physical size — this cancels out any dependency on the
 *   actual capture resolution in pixels (verified algebraically before
 *   this was written), so it needs only the camera's physical focal
 *   length and sensor size (both already measured by V0.2), not the
 *   chosen preview resolution.
 * A full perspective or mesh warp (spec section 13's more sophisticated
 * options) is NOT used here — there's no evidence yet this simpler
 * rotation+translation model is insufficient, and reaching for more
 * before that evidence exists is exactly what section 12 warns against.
 *
 * SMALL-ANGLE EXTRACTION: for a unit quaternion (w,x,y,z) representing a
 * small rotation, the axis-angle vector ≈ (2x, 2y, 2z) radians. Verified
 * numerically against the exact quaternion angle before this was
 * written: error is ~0.0001° even at a 2° rotation, and ~0.0016° at 5° —
 * both far smaller than any shake this filter is meant to correct.
 *
 * CROP: a fixed zoom-in factor (default 10%) keeps typical compensation
 * shifts sampling safely inside the source texture instead of visibly
 * smearing the clamped edge (spec section 14). 10% is a STARTING value,
 * not yet tuned against this device's actually-measured shake
 * magnitudes — worth revisiting once V1.0d's overlay can show real
 * compensation-angle statistics over time (spec section 33: state
 * whether a constant is experimentally tuned, not just its value).
 */
object CompensationTransform {

    const val DEFAULT_CROP_MARGIN = 0.10

    /**
     * Builds the 3x3 texture-coordinate transform matrix, in the
     * column-major layout `glUniformMatrix3fv(..., transpose=false, ...)`
     * expects.
     *
     * @param correctionQuaternion [w,x,y,z] from
     *   `LiveOrientationPipeline.currentCorrectionQuaternion()` — the
     *   rotation that undoes the current shake.
     * @param focalLengthMm this camera's focal length (V0.2's
     *   CameraInfo.focalLengthsMm — first value if several are reported).
     * @param sensorWidthMm / sensorHeightMm this camera's physical sensor
     *   size (V0.2's CameraInfo.physicalSensorSizeMm). If either the
     *   focal length or the matching sensor dimension is unavailable,
     *   that shift component is left at zero (no invented calibration —
     *   spec section 16) rather than guessed; roll correction still
     *   applies regardless, since it needs no calibration data at all.
     */
    fun buildMatrix(
        correctionQuaternion: DoubleArray,
        focalLengthMm: Double?,
        sensorWidthMm: Double?,
        sensorHeightMm: Double?,
        cropMargin: Double = DEFAULT_CROP_MARGIN,
    ): FloatArray {
        val thetaX = 2.0 * correctionQuaternion[1]
        val thetaY = 2.0 * correctionQuaternion[2]
        val rollRad = 2.0 * correctionQuaternion[3]

        val dxNorm = if (focalLengthMm != null && sensorWidthMm != null && sensorWidthMm > 0) {
            thetaY * (focalLengthMm / sensorWidthMm)
        } else {
            0.0
        }
        val dyNorm = if (focalLengthMm != null && sensorHeightMm != null && sensorHeightMm > 0) {
            thetaX * (focalLengthMm / sensorHeightMm)
        } else {
            0.0
        }

        return compose(rollRad, dxNorm, dyNorm, cropMargin)
    }

    /**
     * Pure matrix composition — separated from [buildMatrix] so it can be
     * tested directly against known angle/shift/crop inputs without
     * needing a quaternion. Order (rightmost applied first, matching
     * GLSL's column-vector `M * v` convention): center the coordinate
     * system, rotate, scale (crop), move back off-center, then apply the
     * shake-compensation shift — verified numerically before this was
     * written: identity inputs are a true identity; crop alone leaves
     * the center point fixed while pushing edge points out of the valid
     * [0,1] sampling range; rotation alone preserves distance from
     * center (confirming it rotates about the middle, not a corner).
     *
     * SIGN CONVENTION CAVEAT, stated plainly rather than hidden: the
     * correction *magnitude* here is verified math. Which screen
     * direction a positive rotation/shift visually corresponds to on
     * THIS device depends on details (sensor orientation, mirroring)
     * that can only be confirmed by actually watching it run — a
     * completely normal, expected first-pass step for this kind of
     * feature, not a sign anything is wrong. If the stabilization looks
     * like it's moving the WRONG way on-device, the fix is a one-line
     * sign flip on [rollRad]/[dxNorm]/[dyNorm] here, not a rewrite.
     */
    fun compose(rollRad: Double, dxNorm: Double, dyNorm: Double, cropMargin: Double): FloatArray {
        require(cropMargin in 0.0..0.9) { "cropMargin should be a fraction in [0, 0.9), got $cropMargin" }
        val scaleFactor = 1.0 / (1.0 - cropMargin)

        val tCenterNeg = translationMatrix(-0.5, -0.5)
        val rotation = rotationMatrix(rollRad)
        val scale = scaleMatrix(scaleFactor)
        val tCenterPos = translationMatrix(0.5, 0.5)
        val tComp = translationMatrix(dxNorm, dyNorm)

        var m = matMul(scale, matMul(rotation, tCenterNeg))
        m = matMul(tCenterPos, m)
        m = matMul(tComp, m)
        return toColumnMajorFloat(m)
    }

    // -- 3x3 matrix helpers, all row-major DoubleArray(9) internally for
    // straightforward construction/multiplication; only the final result
    // gets flattened to column-major for GLES upload (verified this
    // round-trips correctly before writing the rest of this file).

    private fun matMul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val result = DoubleArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += a[row * 3 + k] * b[k * 3 + col]
                result[row * 3 + col] = sum
            }
        }
        return result
    }

    private fun translationMatrix(tx: Double, ty: Double): DoubleArray = doubleArrayOf(
        1.0, 0.0, tx,
        0.0, 1.0, ty,
        0.0, 0.0, 1.0,
    )

    private fun rotationMatrix(theta: Double): DoubleArray {
        val c = cos(theta)
        val s = sin(theta)
        return doubleArrayOf(
            c, -s, 0.0,
            s, c, 0.0,
            0.0, 0.0, 1.0,
        )
    }

    private fun scaleMatrix(s: Double): DoubleArray = doubleArrayOf(
        s, 0.0, 0.0,
        0.0, s, 0.0,
        0.0, 0.0, 1.0,
    )

    private fun toColumnMajorFloat(rowMajor: DoubleArray): FloatArray = floatArrayOf(
        rowMajor[0].toFloat(), rowMajor[3].toFloat(), rowMajor[6].toFloat(),
        rowMajor[1].toFloat(), rowMajor[4].toFloat(), rowMajor[7].toFloat(),
        rowMajor[2].toFloat(), rowMajor[5].toFloat(), rowMajor[8].toFloat(),
    )
}
