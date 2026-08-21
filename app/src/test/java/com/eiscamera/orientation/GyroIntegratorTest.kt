package com.eiscamera.orientation

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GyroIntegratorTest {

    @Test
    fun `integrateStep exactly matches closed-form for constant angular velocity, regardless of step count`() {
        val omega = doubleArrayOf(0.3, -0.7, 1.1) // arbitrary (non-axis-aligned) direction, rad/s
        val magnitude = sqrt(omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2])
        val totalTimeS = 2.0
        val trueAngle = magnitude * totalTimeS
        val axis = doubleArrayOf(omega[0] / magnitude, omega[1] / magnitude, omega[2] / magnitude)
        val trueQ = doubleArrayOf(
            cos(trueAngle / 2),
            axis[0] * sin(trueAngle / 2),
            axis[1] * sin(trueAngle / 2),
            axis[2] * sin(trueAngle / 2),
        )

        for (steps in listOf(1, 5, 50, 500)) {
            val dt = totalTimeS / steps
            var q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
            repeat(steps) { q = GyroIntegrator.integrateStep(q, omega, dt) }
            val err = sqrt((0..3).sumOf { (q[it] - trueQ[it]) * (q[it] - trueQ[it]) })
            assertEquals("steps=$steps", 0.0, err, 1e-9)
        }
    }

    @Test
    fun `angleBetween recovers zero for identical orientations (including double-cover) and pi for opposite`() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, GyroIntegrator.angleBetween(identity, identity), 1e-9)

        val signFlipped = doubleArrayOf(-1.0, 0.0, 0.0, 0.0) // same orientation as identity
        assertEquals(0.0, GyroIntegrator.angleBetween(identity, signFlipped), 1e-9)

        val halfTurn = doubleArrayOf(0.0, 1.0, 0.0, 0.0) // 180 degrees about X
        assertEquals(PI, GyroIntegrator.angleBetween(identity, halfTurn), 1e-6)
    }
}
