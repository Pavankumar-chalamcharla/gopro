package com.eiscamera.motion

import com.eiscamera.orientation.QuaternionMath
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/**
 * SLERP-based low-pass filter for an orientation quaternion stream (spec
 * section 12, roadmap V0.9): separates LOW-FREQUENCY intentional motion
 * (panning, tilting — should be PRESERVED) from HIGH-FREQUENCY unwanted
 * motion (hand shake, vibration — should be CANCELLED by the eventual
 * stabilization transform, roadmap V1.0+).
 *
 * METHOD: at each new orientation sample q_raw[n], the smoothed estimate
 * updates as:
 *
 *   q_smooth[n] = SLERP(q_smooth[n-1], q_raw[n], α)
 *
 * This is the quaternion generalization of the classic single-pole IIR
 * low-pass filter y[n] = α·x[n] + (1-α)·y[n-1] — SLERP is used instead of
 * linear blending so the result stays on the unit-quaternion manifold
 * without a separate re-normalization step that would itself distort the
 * result for larger rotations.
 *
 * CHOICE OF ALGORITHM (spec section 12: "do not choose sophisticated
 * algorithms without evidence that they improve the system"): this is
 * deliberately the SIMPLEST correct option — a single-pole low-pass —
 * rather than a One Euro Filter or similar adaptive scheme. Those add
 * real value (reducing lag during fast intentional motion) but are a
 * refinement to evaluate AFTER this baseline is proven on real data, not
 * a default starting point.
 *
 * α ↔ CUTOFF FREQUENCY: for a filter running at a fixed sample interval,
 * α relates to a cutoff frequency (Hz) via the standard discretization of
 * a continuous RC low-pass filter with time constant τ = 1/(2π·f_c):
 *
 *   α = 1 - exp(-Δt / τ) = 1 - exp(-Δt · 2π · f_c)
 *
 * Verified numerically against the theoretical single-pole frequency
 * response |H(f)| = 1/√(1+(f/f_c)²) — including the exact -3dB point at
 * f=f_c — across a sinusoidal test sweep from 0.2Hz to 15Hz, before this
 * file was written. See OrientationSmoothingFilterTest.
 */
object OrientationSmoothingFilter {

    /**
     * MEANING: converts a desired cutoff frequency into the per-sample
     *   SLERP blend factor α this filter actually uses.
     * UNITS: cutoffHz in Hz, sampleIntervalS in seconds; returns
     *   dimensionless α in (0, 1].
     * LOWER cutoffHz → smaller α → heavier smoothing (more of the signal
     *   is treated as "shake" and removed; slower motion needed to count
     *   as "intentional").
     * HIGHER cutoffHz → larger α → lighter smoothing (more motion passes
     *   through as "intentional," less gets cancelled).
     * DEVICE-DEPENDENT: no — this is signal-processing math, not a
     *   hardware property. The right cutoff for THIS device's actual
     *   camera/gyro characteristics is a later, evidence-based tuning
     *   question (spec section 12's caution against picking sophistication
     *   without evidence applies here too) — not resolved by this file.
     */
    fun alphaForCutoff(cutoffHz: Double, sampleIntervalS: Double): Double {
        require(cutoffHz > 0) { "cutoffHz must be positive" }
        require(sampleIntervalS > 0) { "sampleIntervalS must be positive" }
        return (1.0 - exp(-sampleIntervalS * 2.0 * PI * cutoffHz)).coerceIn(0.0, 1.0)
    }

    /** Inverse of [alphaForCutoff] — recovers the effective cutoff frequency
     *  (Hz) a given α corresponds to, at a given sample interval. Useful for
     *  reporting/diagnostics when α was chosen directly rather than derived. */
    fun cutoffForAlpha(alpha: Double, sampleIntervalS: Double): Double {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1]" }
        require(sampleIntervalS > 0) { "sampleIntervalS must be positive" }
        return -ln(1.0 - alpha) / (2.0 * PI * sampleIntervalS)
    }

    /**
     * Applies one filter step. [qSmoothPrev] is the filter's running state
     * (w,x,y,z), [qRaw] is the new raw orientation sample, [alpha] is the
     * blend factor from [alphaForCutoff]. Returns the updated smoothed
     * orientation.
     */
    fun step(qSmoothPrev: DoubleArray, qRaw: DoubleArray, alpha: Double): DoubleArray {
        return QuaternionMath.slerp(qSmoothPrev, qRaw, alpha)
    }

    data class FilteredSample(
        val timestampNs: Long,
        val smoothed: DoubleArray,
        /** Angle (radians) between the raw and smoothed orientation at this
         *  sample — the magnitude of what would be CANCELLED by stabilization
         *  if this sample were used as-is (the "shake" the filter identified). */
        val compensationAngleRad: Double,
    )

    /**
     * Runs the filter over a full stream of (timestamp, orientation)
     * samples, starting from the first sample as the initial smoothed
     * state (zero compensation at t=0 by construction).
     *
     * @param orientations pairs of (timestampNs, quaternion [w,x,y,z]),
     *   ordered by time — e.g. the output of repeatedly calling
     *   GyroIntegrator.integrateStep, or a rotation-vector sample stream.
     */
    fun filterStream(
        orientations: List<Pair<Long, DoubleArray>>,
        cutoffHz: Double,
    ): List<FilteredSample> {
        if (orientations.isEmpty()) return emptyList()
        val result = mutableListOf<FilteredSample>()
        var qSmooth = orientations.first().second
        result += FilteredSample(orientations.first().first, qSmooth, 0.0)

        for (i in 1 until orientations.size) {
            val (tPrevNs, _) = orientations[i - 1]
            val (tNs, qRaw) = orientations[i]
            val dtS = (tNs - tPrevNs) / 1_000_000_000.0
            if (dtS <= 0) continue
            val alpha = alphaForCutoff(cutoffHz, dtS)
            qSmooth = step(qSmooth, qRaw, alpha)
            result += FilteredSample(tNs, qSmooth, QuaternionMath.angleBetween(qSmooth, qRaw))
        }
        return result
    }
}
