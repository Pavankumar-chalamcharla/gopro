package com.eiscamera.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.eiscamera.logging.EisLog
import kotlinx.coroutines.delay

/**
 * Subscribes to the gyroscope and rotation-vector sensors simultaneously
 * and buffers raw samples for a fixed collection window. This is a
 * one-shot, timed collection for the V0.3 Sensor Quality Test — NOT a
 * continuous stream. Continuous recording for the live EIS pipeline is a
 * separate, later component (roadmap V0.5).
 *
 * Uses SENSOR_DELAY_FASTEST to request the highest rate the driver will
 * give us; the MEASURED rate (from SensorQualityAnalyzer) is what actually
 * matters, not what was requested (spec section 5).
 */
class SensorQualityCollector(context: Context) {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /**
     * Collects samples for [durationMs] milliseconds and returns them.
     * Suspends the calling coroutine for the duration; safe to call from
     * a background dispatcher (spec section 28 — never on the UI thread).
     * [onTickRemainingSeconds], if provided, is invoked roughly once per
     * second with the seconds remaining, for driving a UI countdown.
     */
    suspend fun collect(
        durationMs: Long,
        onTickRemainingSeconds: ((Int) -> Unit)? = null,
    ): CollectedSamples {
        val gyroBuffer = mutableListOf<SensorSample>()
        val rvBuffer = mutableListOf<SensorSample>()
        val quaternionScratch = FloatArray(4)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                        gyroBuffer += SensorSample(event.timestamp, event.values.copyOf(3))
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getQuaternionFromVector(quaternionScratch, event.values)
                        rvBuffer += SensorSample(event.timestamp, quaternionScratch.copyOf(4))
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        if (gyroSensor != null) {
            sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST)
        } else {
            EisLog.w(EisLog.Tag.SENSOR, "No gyroscope available; collection will yield zero gyro samples")
        }
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        val totalSeconds = (durationMs / 1000L).toInt().coerceAtLeast(1)
        for (elapsed in 0 until totalSeconds) {
            onTickRemainingSeconds?.invoke(totalSeconds - elapsed)
            delay(1000L)
        }

        sensorManager.unregisterListener(listener)

        EisLog.i(
            EisLog.Tag.SENSOR,
            "Collected ${gyroBuffer.size} gyro samples, ${rvBuffer.size} rotation-vector samples over ${durationMs}ms"
        )

        return CollectedSamples(gyroBuffer.toList(), rvBuffer.toList())
    }
}

data class CollectedSamples(
    val gyroSamples: List<SensorSample>,
    val rotationVectorQuaternions: List<SensorSample>,
)
