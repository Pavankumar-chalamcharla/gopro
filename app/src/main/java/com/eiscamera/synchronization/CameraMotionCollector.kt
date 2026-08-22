package com.eiscamera.synchronization

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.eiscamera.logging.EisLog
import com.eiscamera.sensors.SensorSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

data class SyncCollectionResult(
    val gyroSamples: List<SensorSample>,
    val rotationVectorQuaternions: List<SensorSample>,
    val cameraMotionSamples: List<CameraMotionSample>,
    val cameraTimestampSource: String,
)

/**
 * Concurrently collects gyroscope samples, rotation-vector quaternions,
 * AND per-frame camera "motion energy" for a fixed window — the raw
 * material for the V0.7 gyro<->camera time-offset estimate and orientation
 * interpolation (spec section 7).
 *
 * Unlike V0.4's CameraStreamQualityCollector, this one DOES read pixel
 * data (Y/luma plane only, coarsely downsampled) — detecting motion is the
 * whole point here. Only a single scalar "how much changed since the last
 * frame" value is kept per frame; no image is stored or exposed.
 *
 * All Camera2 and sensor callbacks run on a dedicated background
 * HandlerThread, never the UI thread (spec section 28).
 */
class CameraMotionCollector(context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    suspend fun collect(
        cameraId: String,
        durationMs: Long,
        onTickRemainingSeconds: ((Int) -> Unit)? = null,
    ): SyncCollectionResult {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val timestampSource = when (characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            else -> "UNKNOWN"
        }

        val gyroBuffer = mutableListOf<SensorSample>()
        val rvBuffer = mutableListOf<SensorSample>()
        val motionBuffer = mutableListOf<CameraMotionSample>()
        val quaternionScratch = FloatArray(4)

        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val thread = HandlerThread("SyncTest-$cameraId").apply { start() }
        val handler = Handler(thread.looper)

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED ->
                        gyroBuffer += SensorSample(event.timestamp, event.values.copyOf(3))
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getQuaternionFromVector(quaternionScratch, event.values)
                        rvBuffer += SensorSample(event.timestamp, quaternionScratch.copyOf(4))
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        try {
            if (gyroSensor != null) {
                sensorManager.registerListener(sensorListener, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            } else {
                EisLog.w(EisLog.Tag.SYNC, "No gyroscope available for sync test")
            }
            if (rotationVectorSensor != null) {
                sensorManager.registerListener(sensorListener, rotationVectorSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            }

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Camera $cameraId has no stream configuration map")
            val outputSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { it.width.toLong() * it.height }
                ?: throw IllegalStateException("Camera $cameraId has no usable YUV output size")

            val imageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.YUV_420_888, 2)

            var previousLuma: ByteArray? = null
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer // Y (luma) plane only
                    val current = ByteArray(buffer.remaining())
                    buffer.get(current)

                    val prev = previousLuma
                    if (prev != null && prev.size == current.size) {
                        var sumDiff = 0L
                        var count = 0
                        val stride = 8 // coarse sampling keeps this cheap; a full motion-energy signal isn't needed
                        var i = 0
                        while (i < current.size) {
                            sumDiff += abs((current[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
                            count++
                            i += stride
                        }
                        val meanAbsDiff = if (count > 0) sumDiff.toDouble() / count else 0.0
                        motionBuffer += CameraMotionSample(image.timestamp, meanAbsDiff)
                    }
                    previousLuma = current
                } finally {
                    image.close()
                }
            }, handler)

            val camera = openCamera(cameraId, handler)
            try {
                val session = createCaptureSession(camera, listOf(imageReader.surface), handler)
                try {
                    val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val targetFpsRange = fpsRanges?.maxByOrNull { it.upper }
                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        targetFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }
                    // No CaptureCallback needed — Image#getTimestamp() already carries the
                    // same SENSOR_TIMESTAMP value the corresponding CaptureResult would have.
                    session.setRepeatingRequest(requestBuilder.build(), null, handler)

                    val totalSeconds = (durationMs / 1000L).toInt().coerceAtLeast(1)
                    for (elapsed in 0 until totalSeconds) {
                        onTickRemainingSeconds?.invoke(totalSeconds - elapsed)
                        delay(1000L)
                    }

                    session.stopRepeating()
                } finally {
                    session.close()
                }
            } finally {
                camera.close()
            }
            imageReader.close()
        } finally {
            sensorManager.unregisterListener(sensorListener)
            thread.quitSafely()
        }

        EisLog.i(
            EisLog.Tag.SYNC,
            "Sync collection: ${gyroBuffer.size} gyro, ${rvBuffer.size} rotation-vector, " +
                "${motionBuffer.size} camera-motion samples"
        )

        return SyncCollectionResult(
            gyroSamples = gyroBuffer.toList(),
            rotationVectorQuaternions = rvBuffer.toList(),
            cameraMotionSamples = motionBuffer.toList(),
            cameraTimestampSource = timestampSource,
        )
    }

    private suspend fun openCamera(cameraId: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (cont.isActive) cont.resume(camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera $cameraId disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera $cameraId error code $error"))
                    }
                }, handler)
            } catch (e: SecurityException) {
                cont.resumeWithException(IllegalStateException("CAMERA permission not granted", e))
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun createCaptureSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("Failed to configure capture session for camera"))
                }
            }
        }, handler)
    }
}
