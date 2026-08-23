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
 * CROP: a fixed zoom-in factor (now 20%, see below) keeps compensation
 * shifts sampling safely inside the source texture instead of visibly
 * smearing the clamped edge (spec section 14).
 *
 * TWO ISSUES FOUND ON FIRST REAL-DEVICE TEST, both root-caused with
 * actual numbers before fixing (spec section 40's debugging methodology)
 * rather than guessed at:
 *
 * 1. WOBBLE ON SMALL/NO SHAKE. This device's gyroscope is a
 *    software-synthesized "virtual_gyro" (V0.2's finding) with real,
 *    already-measured drift when integrated — V0.8 found 11-15° of pure
 *    drift over just 10 stationary seconds, even bias-corrected. A fixed
 *    low-pass filter has no way to tell "genuine slow intentional pan"
 *    apart from "slow drift accumulated from integrated sensor noise" —
 *    both look identical in the frequency domain — so the smoothed
 *    reference itself inherits some of that drift, and the resulting
 *    gap (compensation) fluctuates even at rest. [deadbandRad]/
 *    [fullRampRad] suppress correction below a small threshold and ramp
 *    it in smoothly (not a hard on/off snap) above it — standard
 *    practice in gimbal/EIS control for exactly this reason. Values are
 *    STARTING points sized against this project's own V0.3 noise
 *    measurement, not yet tuned against a longer real capture.
 * 2. NO VISIBLE CORRECTION ON LARGE/FAST SHAKE. The original 10% crop
 *    margin allows only ~±4° of shift before sampling runs outside the
 *    source texture and hits the raw GL_CLAMP_TO_EDGE behavior — real
 *    shake routinely exceeds that, so correction simply ran out of room
 *    well before it could look like it was doing anything. This is a
 *    genuine physical tradeoff, not a bug to fully eliminate: bigger
 *    correction always costs more FOV (spec section 14). The margin is
 *    now 20% (~±8° before clamping) and [maxShiftNorm]/[maxRollRad]
 *    clamp explicitly and gracefully at that boundary, rather than
 *    leaving the cap to whatever GL's raw texture clamping happens to
 *    look like.
 */
object CompensationTransform {

    const val DEFAULT_CROP_MARGIN = 0.20
    const val DEFAULT_DEADBAND_DEG = 0.15
    const val DEFAULT_FULL_RAMP_DEG = 0.6
    const val DEFAULT_MAX_ROLL_DEG = 8.0

    /** Fraction of the crop margin reserved as the maximum safe shift —
     *  leaves headroom so a simultaneous roll rotation's own corner sweep
     *  doesn't push past the cropped region even when translation is
     *  already near its cap. Verified numerically (see math-verification
     *  notes) rather than derived from an exact corner-sweep formula —
     *  a deliberate simplification (spec section 12), revisit if V1.0d's
     *  overlay shows this capping more often than expected in practice. */
    private const val MAX_SHIFT_FRACTION_OF_CROP = 0.4

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
        deadbandRad: Double = Math.toRadians(DEFAULT_DEADBAND_DEG),
        fullRampRad: Double = Math.toRadians(DEFAULT_FULL_RAMP_DEG),
        maxRollRad: Double = Math.toRadians(DEFAULT_MAX_ROLL_DEG),
    ): FloatArray {
        val thetaX = 2.0 * correctionQuaternion[1]
        val thetaY = 2.0 * correctionQuaternion[2]
        var rollRad = 2.0 * correctionQuaternion[3]

        var dxNorm = if (focalLengthMm != null && sensorWidthMm != null && sensorWidthMm > 0) {
            thetaY * (focalLengthMm / sensorWidthMm)
        } else {
            0.0
        }
        var dyNorm = if (focalLengthMm != null && sensorHeightMm != null && sensorHeightMm > 0) {
            thetaX * (focalLengthMm / sensorHeightMm)
        } else {
            0.0
        }

        // Deadband: total rotation magnitude drives one scale factor applied
        // uniformly to roll/dx/dy, so direction stays intact and only
        // overall strength ramps — see class kdoc, issue 1.
        val totalAngleRad = 2.0 * kotlin.math.acos(correctionQuaternion[0].coerceIn(-1.0, 1.0))
        val strength = deadbandScale(totalAngleRad, deadbandRad, fullRampRad)
        rollRad *= strength
        dxNorm *= strength
        dyNorm *= strength

        // Graceful cap at the crop margin's safe boundary — see class kdoc, issue 2.
        val maxShiftNorm = cropMargin * MAX_SHIFT_FRACTION_OF_CROP
        dxNorm = dxNorm.coerceIn(-maxShiftNorm, maxShiftNorm)
        dyNorm = dyNorm.coerceIn(-maxShiftNorm, maxShiftNorm)
        rollRad = rollRad.coerceIn(-maxRollRad, maxRollRad)

        return compose(rollRad, dxNorm, dyNorm, cropMargin)
    }

    /**
     * MEANING: converts a total correction-angle magnitude into a [0,1]
     *   strength multiplier — 0 below [deadbandRad] (treated as noise,
     *   not real shake), ramping linearly to 1 at [fullRampRad].
     * LOWER deadbandRad → more small-angle wobble gets "corrected"
     *   (which, per this class's kdoc issue 1, tends to make wobble MORE
     *   visible, not less, since it's chasing noise).
     * HIGHER deadbandRad → more genuine small motion gets ignored too.
     * DEVICE-DEPENDENT: the right value scales with this device's own
     *   measured integration noise (V0.3) — not yet tuned per-device
     *   automatically; DEFAULT_DEADBAND_DEG is sized against this
     *   project's own V0.3 measurement as a starting point.
     */
    private fun deadbandScale(angleRad: Double, deadbandRad: Double, fullRampRad: Double): Double {
        if (angleRad <= deadbandRad) return 0.0
        if (angleRad >= fullRampRad) return 1.0
        return (angleRad - deadbandRad) / (fullRampRad - deadbandRad)
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
        // Sampling the CENTRAL (1-cropMargin) fraction of the source and
        // stretching it to fill the destination frame means scaling the
        // SOURCE offset-from-center DOWN by (1-cropMargin) -- e.g. a
        // destination edge at offset 0.5 from center should sample the
        // source at offset 0.5*(1-cropMargin), pulled IN from the true
        // edge, not push OUT beyond it. An earlier version of this file
        // used 1/(1-cropMargin) here instead — the reciprocal, which
        // zooms sampling OUT rather than in, the opposite of a crop —
        // confirmed as the cause of a real corner/edge blur on-device;
        // see math-verification notes for the corrected derivation.
        val scaleFactor = 1.0 - cropMargin

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
