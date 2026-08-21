package com.eiscamera.camera

import kotlinx.serialization.Serializable

/**
 * Persisted V0.4 result for ONE camera id. Multiple cameras each get
 * their own snapshot — spec section 6: "do not assume all cameras on the
 * same phone have the same capabilities," evaluated independently.
 */
@Serializable
data class CameraStreamQualitySnapshot(
    val cameraId: String,
    val testTimestampMs: Long,
    val frameCount: Int,
    val measuredFps: Double?,
    val frameIntervalJitterMs: Double?,
    val minIntervalMs: Double?,
    val maxIntervalMs: Double?,
    val likelyDroppedFrames: Int,
    val meanExposureTimeNs: Double?,
    val meanFrameDurationNs: Double?,
)
