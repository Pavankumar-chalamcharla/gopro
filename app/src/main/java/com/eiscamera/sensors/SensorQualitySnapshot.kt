package com.eiscamera.sensors

import kotlinx.serialization.Serializable

/**
 * Persisted summary of a V0.3 Sensor Quality Test run — the stationary
 * measurement plus (if it produced a usable result) the dynamic-response
 * cross-check. Stored on DeviceProfile (schema v2+). All fields here are
 * MEASURED, never ESTIMATED or ASSUMED (spec section 41) — a run that
 * failed to collect enough samples is not persisted at all, rather than
 * being persisted with placeholder values.
 *
 * biasXRadS/biasYRadS/biasZRadS (added for V0.8) are the per-axis
 * stationary bias components — distinct from [stationaryBiasRadS], which
 * is only the MAGNITUDE of that same vector. Direction matters for actual
 * bias correction (subtracting a vector from another vector), which is
 * why V0.8's OrientationDriftAnalyzer needs these three separately rather
 * than just the magnitude.
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
    val biasXRadS: Double,
    val biasYRadS: Double,
    val biasZRadS: Double,
    val dynamicTestAvailable: Boolean,
    val dynamicLagMs: Double? = null,
    val dynamicCorrelation: Double? = null,
    val dynamicGyroPeakRadS: Double? = null,
    val dynamicRotationVectorPeakRadS: Double? = null,
)
