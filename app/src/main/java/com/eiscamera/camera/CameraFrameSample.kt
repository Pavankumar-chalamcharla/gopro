package com.eiscamera.camera

/**
 * A single captured frame's timing metadata from Camera2's CaptureResult.
 * Plain data class (no Camera2 dependency in the values themselves) so
 * CameraStreamQualityAnalyzer's math is unit-testable on the JVM.
 *
 * [sensorTimestampNs] is CaptureResult.SENSOR_TIMESTAMP: the sensor's
 * exposure-start timestamp. Its clock domain depends on the camera's
 * SENSOR_INFO_TIMESTAMP_SOURCE characteristic (REALTIME vs UNKNOWN) — that
 * distinction matters for the eventual gyro<->camera synchronization work
 * (spec section 7, roadmap V0.7), not for this file's own frame-rate/
 * jitter measurement, which only needs consistent relative timing
 * frame-to-frame.
 */
data class CameraFrameSample(
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long?,
    val frameDurationNs: Long?,
)
