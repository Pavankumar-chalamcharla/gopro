package com.eiscamera.sensors

import kotlinx.serialization.Serializable

/**
 * Persisted summary of a V0.3 Sensor Quality Test run — the stationary
 * measurement plus (if it produced a usable result) the dynamic-response
 * cross-check. Stored on DeviceProfile (schema v2). All fields here are
 * MEASURED, never ESTIMATED or ASSUMED (spec section 41) — a run that
 * failed to collect enough samples is not persisted at all, rather than
 * being persisted with placeholder values.
 */
@Serializable
data class SensorQualitySnapshot(
    val testTimestampMs: Long,
    val stationarySampleCount: Int,
    val measuredRateHz: Double?,
    val timestampJitterMs: Double?,
    val timestampMonotonic: Boolean,
    val stationaryNoiseStdDevRadS: Double,
    val stationaryBiasRadS: Double,
    val dynamicTestAvailable: Boolean,
    val dynamicLagMs: Double? = null,
    val dynamicCorrelation: Double? = null,
    val dynamicGyroPeakRadS: Double? = null,
    val dynamicRotationVectorPeakRadS: Double? = null,
)
