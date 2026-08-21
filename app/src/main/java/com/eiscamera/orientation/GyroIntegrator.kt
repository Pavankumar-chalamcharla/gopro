package com.eiscamera.orientation

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Integrates raw gyroscope angular-velocity samples into a continuous
 * orientation estimate (spec section 11, roadmap V0.8):
 *
 *   θ(t) = θ(t0) + ∫ω(t)dt
 *
 * where θ is orientation — represented here as a unit quaternion, not
 * Euler angles (spec section 11: "Prefer quaternions... avoid unnecessary
 * Euler-angle calculations") — ω is angular velocity (rad/s, in the
 * DEVICE's body frame, exactly as the gyroscope reports it), and t is
 * time (seconds).
 *
 * INTEGRATION METHOD: the "exponential map." Over a small time step dt,
 * the incremental rotation is computed as a proper axis-angle quaternion
 * (angle = |ω|·dt, axis = ω/|ω|), then composed with the running
 * orientation via Hamilton product. This is EXACT — not merely a good
 * approximation — for constant angular velocity over the step, unlike
 * naive Euler integration (q += 0.5·q⊗[0,ω]·dt, re-normalized), which
 * accrues error even for constant ω unless steps are infinitesimally
 * small. Verified numerically against closed-form constant-angular-
 * velocity rotation, at multiple step counts, before being written here
 * — see GyroIntegratorTest.
 *
 * LIMITATION — DRIFT: pure gyro integration has no absolute reference; any
 * uncorrected sensor bias accumulates into orientation error over time (a
 * constant 0.01 rad/s bias error becomes a 0.01 radian orientation error
 * after 1 second, roughly 0.6 radians after a minute). This is why
 * long-duration orientation tracking needs periodic correction from an
 * absolute reference (accelerometer/magnetometer) — exactly what
 * Android's own rotation-vector fusion does, and this integrator
 * deliberately does NOT. For gyro-based EIS specifically this matters
 * less than it sounds: stabilization only needs ACCURATE SHORT-INTERVAL
 * motion (the few milliseconds between adjacent video frames), not
 * long-term absolute orientation — see OrientationDriftAnalyzer for an
 * empirical, on-device measurement of how much THIS device's gyro
 * actually drifts over a real test window, rather than assuming.
 */
object GyroIntegrator {

    /**
     * Integrates one time step. [q] is the current orientation (w,x,y,z).
     * [omega] is angular velocity (x,y,z, rad/s) held constant over
     * [dtSeconds] (a "zero-order hold" between samples — reasonable given
     * the measured gyro rates this project has seen, ~200Hz; see
     * OrientationDriftAnalyzer for how successive samples are chained).
     * Returns the new orientation, always a unit quaternion.
     */
    fun integrateStep(q: DoubleArray, omega: DoubleArray, dtSeconds: Double): DoubleArray {
        val wx = omega[0]; val wy = omega[1]; val wz = omega[2]
        val magnitude = sqrt(wx * wx + wy * wy + wz * wz)
        val angle = magnitude * dtSeconds

        val deltaQ = if (angle < 1e-8) {
            // Small-angle approximation avoids dividing by a near-zero magnitude.
            normalize(doubleArrayOf(1.0, 0.5 * wx * dtSeconds, 0.5 * wy * dtSeconds, 0.5 * wz * dtSeconds))
        } else {
            val half = angle / 2.0
            val s = sin(half) / magnitude
            doubleArrayOf(cos(half), wx * s, wy * s, wz * s)
        }

        return normalize(hamiltonProduct(q, deltaQ))
    }

    /**
     * Angle (radians, [0, π]) between two orientation quaternions, robust
     * to the double-cover sign ambiguity (q and -q are the identical
     * orientation) — same handling as V0.3's dynamic-response fix and
     * V0.7's SLERP.
     */
    fun angleBetween(q1: DoubleArray, q2: DoubleArray): Double {
        val dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]
        return 2.0 * acos(abs(dot).coerceAtMost(1.0))
    }

    private fun hamiltonProduct(a: DoubleArray, b: DoubleArray): DoubleArray {
        val w1 = a[0]; val x1 = a[1]; val y1 = a[2]; val z1 = a[3]
        val w2 = b[0]; val x2 = b[1]; val y2 = b[2]; val z2 = b[3]
        return doubleArrayOf(
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
        )
    }

    private fun normalize(q: DoubleArray): DoubleArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (n > 0) doubleArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n) else doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    }
}
