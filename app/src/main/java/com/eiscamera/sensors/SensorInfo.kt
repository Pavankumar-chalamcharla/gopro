package com.eiscamera.sensors

import kotlinx.serialization.Serializable

/**
 * Static, declared properties of a single Android [android.hardware.Sensor].
 *
 * Everything here is AVAILABLE / DECLARED information — what the Sensor
 * object and SensorManager report about themselves. None of it is MEASURED
 * (spec section 41 terminology). Declared values, especially
 * [declaredMaxRateHz], are frequently optimistic relative to real-world
 * behavior; see the not-yet-implemented SensorQualityTest (spec section 5,
 * roadmap V0.3) for MEASURED sampling rate, jitter, noise, and bias.
 */
@Serializable
data class SensorInfo(
    val type: Int,
    val typeName: String,
    val name: String,
    val vendor: String,
    val version: Int,
    val resolution: Float,
    val maximumRange: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val reportingMode: String,
    val isWakeUpSensor: Boolean,
    val power: Float,
    /**
     * Nominal max sampling rate derived from minDelayUs (1e6 / minDelayUs),
     * in Hz. DECLARED, not MEASURED. Null when minDelayUs == 0, which means
     * the platform does not declare a fixed minimum delay for this sensor
     * (seen on some game-rotation-vector / synthetic sensor implementations)
     * — in that case this must NOT be assumed to be either fast or slow.
     */
    val declaredMaxRateHz: Double?,
)
