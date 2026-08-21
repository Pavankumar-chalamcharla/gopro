package com.eiscamera.sensors

/**
 * A single raw sample from a 3-or-4-value motion sensor (gyroscope's
 * [x,y,z] angular velocity, or a rotation-vector quaternion [w,x,y,z]).
 *
 * Timestamps are Android sensor timestamps: nanoseconds since boot, in the
 * SAME clock domain as android.os.SystemClock.elapsedRealtimeNanos() — NOT
 * wall-clock/System.currentTimeMillis(). This matters because it means
 * timestamps from two DIFFERENT sensors on the same device are directly
 * comparable without any clock-offset estimation (unlike the eventual
 * camera<->gyro synchronization problem in spec section 7, which involves
 * genuinely different clock domains).
 *
 * Deliberately NOT a wrapper around android.hardware.SensorEvent, so that
 * SensorQualityAnalyzer's math can be unit tested on the plain JVM without
 * Robolectric or a device/emulator.
 */
data class SensorSample(
    val timestampNs: Long,
    val values: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensorSample) return false
        return timestampNs == other.timestampNs && values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + values.contentHashCode()
        return result
    }
}
