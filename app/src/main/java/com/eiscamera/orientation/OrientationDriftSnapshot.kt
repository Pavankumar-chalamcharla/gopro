package com.eiscamera.orientation

import kotlinx.serialization.Serializable

@Serializable
data class OrientationDriftSnapshot(
    val testTimestampMs: Long,
    val durationS: Double,
    val gyroSampleCount: Int,
    val referenceSampleCount: Int,
    val biasCorrectionApplied: Boolean,
    val driftUncorrectedDegrees: Double,
    val driftCorrectedDegrees: Double?,
)
