package com.eiscamera.synchronization

/**
 * A single camera frame's timing plus a crude "how much changed since the
 * last frame" motion-energy value (mean absolute luma difference — see
 * CameraMotionCollector). Used as an independent, camera-domain motion
 * signal to cross-correlate against the gyroscope for clock-offset
 * estimation (spec section 7, roadmap V0.7).
 */
data class CameraMotionSample(
    val timestampNs: Long,
    val motionEnergy: Double,
)
