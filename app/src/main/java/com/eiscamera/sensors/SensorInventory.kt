package com.eiscamera.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.eiscamera.logging.EisLog

/**
 * Enumerates every sensor the platform exposes via [SensorManager] that is
 * relevant to gyro-based EIS: gyroscope (calibrated + uncalibrated),
 * accelerometer, rotation vector, game rotation vector, gravity, magnetic
 * field, and linear acceleration.
 *
 * This is a STATIC inventory only (spec section 4). It does not subscribe
 * to sensor events and does not measure real sampling rate, jitter, noise,
 * or bias — that is the job of the not-yet-implemented SensorQualityTest
 * (spec section 5, roadmap V0.3).
 */
class SensorInventory(context: Context) {

    private val sensorManager: SensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val relevantTypes = linkedMapOf(
        Sensor.TYPE_GYROSCOPE to "Gyroscope (calibrated)",
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED to "Gyroscope (uncalibrated)",
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_ROTATION_VECTOR to "Rotation Vector",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "Game Rotation Vector",
        Sensor.TYPE_GRAVITY to "Gravity",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetic Field",
        Sensor.TYPE_LINEAR_ACCELERATION to "Linear Acceleration",
    )

    /**
     * Static info for every relevant sensor type actually present on this
     * device. Types with no backing sensor are OMITTED, not filled with
     * placeholders — absence is meaningful and is reported separately by
     * [missingCriticalSensors].
     */
    fun scan(): List<SensorInfo> {
        val results = mutableListOf<SensorInfo>()
        for ((type, label) in relevantTypes) {
            val sensor = sensorManager.getDefaultSensor(type) ?: continue
            results += sensor.toSensorInfo(label)
            EisLog.d(EisLog.Tag.SENSOR, "Found $label: ${sensor.name} (${sensor.vendor})")
        }
        if (results.none { it.type == Sensor.TYPE_GYROSCOPE || it.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED }) {
            EisLog.w(EisLog.Tag.SENSOR, "No gyroscope present — gyro-based EIS is impossible on this device")
        }
        return results
    }

    /** Critical = required for ANY gyro-based EIS level above UNSUPPORTED. */
    fun missingCriticalSensors(): List<String> {
        val missing = mutableListOf<String>()
        val hasGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) != null
        if (!hasGyro) missing += "Gyroscope"
        return missing
    }

    private fun Sensor.toSensorInfo(label: String): SensorInfo {
        val reportingModeStr = when (reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> "CONTINUOUS"
            Sensor.REPORTING_MODE_ON_CHANGE -> "ON_CHANGE"
            Sensor.REPORTING_MODE_ONE_SHOT -> "ONE_SHOT"
            Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "SPECIAL_TRIGGER"
            else -> "UNKNOWN($reportingMode)"
        }
        // minDelay is in microseconds and represents the shortest interval
        // the driver claims it can deliver samples at, i.e. the DECLARED
        // ceiling on sampling rate. 0 means "no fixed minimum declared."
        val declaredRate = if (minDelay > 0) 1_000_000.0 / minDelay else null
        return SensorInfo(
            type = type,
            typeName = label,
            name = name,
            vendor = vendor,
            version = version,
            resolution = resolution,
            maximumRange = maximumRange,
            minDelayUs = minDelay,
            maxDelayUs = maxDelay,
            reportingMode = reportingModeStr,
            isWakeUpSensor = isWakeUpSensor,
            power = power,
            declaredMaxRateHz = declaredRate,
        )
    }
}
