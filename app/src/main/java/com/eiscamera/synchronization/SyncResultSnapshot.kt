package com.eiscamera.synchronization

import kotlinx.serialization.Serializable

/**
 * Persisted V0.7 result for one camera<->gyro synchronization test.
 * cameraTimestampSource is carried alongside the estimate because it
 * directly affects how much to trust [estimatedOffsetMs] — see
 * SyncAnalyzer.estimateOffset kdoc.
 */
@Serializable
data class SyncResultSnapshot(
    val cameraId: String,
    val testTimestampMs: Long,
    val cameraTimestampSource: String,
    val gyroSampleCount: Int,
    val cameraFrameCount: Int,
    val estimatedOffsetMs: Double?,
    val correlation: Double?,
)
