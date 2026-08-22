package com.eiscamera.orientation

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
            QuaternionMath.normalize(doubleArrayOf(1.0, 0.5 * wx * dtSeconds, 0.5 * wy * dtSeconds, 0.5 * wz * dtSeconds))
        } else {
            val half = angle / 2.0
            val s = sin(half) / magnitude
            doubleArrayOf(cos(half), wx * s, wy * s, wz * s)
        }

        return QuaternionMath.normalize(QuaternionMath.hamiltonProduct(q, deltaQ))
    }

    /**
     * Angle (radians, [0, π]) between two orientation quaternions, robust
     * to the double-cover sign ambiguity (q and -q are the identical
     * orientation) — same handling as V0.3's dynamic-response fix and
     * V0.7's SLERP. Delegates to QuaternionMath; kept here as the public
     * entry point this class's callers already use.
     */
    fun angleBetween(q1: DoubleArray, q2: DoubleArray): Double = QuaternionMath.angleBetween(q1, q2)
}
