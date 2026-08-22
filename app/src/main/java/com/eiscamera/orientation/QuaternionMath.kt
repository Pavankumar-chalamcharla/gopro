package com.eiscamera.orientation

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Core quaternion primitives shared across every orientation-related part
 * of this project (GyroIntegrator's integration step, SyncAnalyzer's SLERP
 * interpolation, and OrientationSmoothingFilter's low-pass smoothing) —
 * pulled into one place for the same reason motion/TimeSeriesCorrelation.kt
 * exists: avoid maintaining multiple copies of tested, occasionally subtle
 * math (see the V0.3 quaternion double-cover bug in docs/ROADMAP.md for
 * why that's worth taking seriously).
 *
 * All quaternions here are DoubleArray(4) = [w, x, y, z].
 */
object QuaternionMath {

    fun hamiltonProduct(a: DoubleArray, b: DoubleArray): DoubleArray {
        val w1 = a[0]; val x1 = a[1]; val y1 = a[2]; val z1 = a[3]
        val w2 = b[0]; val x2 = b[1]; val y2 = b[2]; val z2 = b[3]
        return doubleArrayOf(
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
        )
    }

    fun normalize(q: DoubleArray): DoubleArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (n > 0) doubleArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n) else doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    }

    /** Angle (radians, [0, π]) between two orientation quaternions, robust to
     *  the double-cover sign ambiguity (q and -q are the identical orientation). */
    fun angleBetween(q1: DoubleArray, q2: DoubleArray): Double {
        val dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]
        return 2.0 * acos(abs(dot).coerceAtMost(1.0))
    }

    /**
     * Spherical linear interpolation between q1 and q2 by fraction [frac]
     * in [0,1]. Handles the double-cover sign ambiguity the same way
     * angleBetween does — verified numerically (exact fractional-angle
     * recovery, and immunity to a sign-flipped input) before this
     * consolidation; see GyroIntegratorTest / SyncAnalyzerTest, which
     * continue to exercise this function through their public APIs.
     */
    fun slerp(q1: DoubleArray, q2: DoubleArray, frac: Double): DoubleArray {
        val w1 = q1[0]; val x1 = q1[1]; val y1 = q1[2]; val z1 = q1[3]
        var w2 = q2[0]; var x2 = q2[1]; var y2 = q2[2]; var z2 = q2[3]

        var dot = w1 * w2 + x1 * x2 + y1 * y2 + z1 * z2
        if (dot < 0.0) {
            w2 = -w2; x2 = -x2; y2 = -y2; z2 = -z2
            dot = -dot
        }
        dot = dot.coerceAtMost(1.0)

        if (dot > 0.9995) {
            // Nearly identical orientations: linear interpolation + re-normalize
            // avoids numerical instability from dividing by sin(theta0) near 0.
            val w = w1 + frac * (w2 - w1)
            val x = x1 + frac * (x2 - x1)
            val y = y1 + frac * (y2 - y1)
            val z = z1 + frac * (z2 - z1)
            return normalize(doubleArrayOf(w, x, y, z))
        }

        val theta0 = acos(dot)
        val sinTheta0 = sin(theta0)
        val theta = theta0 * frac
        val s0 = cos(theta) - dot * sin(theta) / sinTheta0
        val s1 = sin(theta) / sinTheta0

        return doubleArrayOf(
            s0 * w1 + s1 * w2,
            s0 * x1 + s1 * x2,
            s0 * y1 + s1 * y2,
            s0 * z1 + s1 * z2,
        )
    }
}
