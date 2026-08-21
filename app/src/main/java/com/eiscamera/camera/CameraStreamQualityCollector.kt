package com.eiscamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.eiscamera.logging.EisLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opens a single Camera2 camera device, runs a repeating capture request
 * for a fixed collection window, and records each frame's real timing
 * metadata (spec section 6, roadmap V0.4). This is a one-shot, timed
 * collection for the quality test — NOT the live preview pipeline (that
 * begins at V0.6). No image data is read, saved, or shown; only per-frame
 * CaptureResult timing metadata is kept.
 *
 * Requires the CAMERA runtime permission to already be granted — the
 * caller (CameraQualityScreen) is responsible for requesting it first.
 * All Camera2 callbacks run on a dedicated background HandlerThread, never
 * the UI thread (spec section 28).
 */
class CameraStreamQualityCollector(context: Context) {

    private val cameraManager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * @param cameraId a camera id as reported by CameraManager#getCameraIdList
     *   (already inventoried in V0.2's CameraInfo).
     * @param durationMs how long to collect frames for.
     */
    suspend fun collect(
        cameraId: String,
        durationMs: Long,
        onTickRemainingSeconds: ((Int) -> Unit)? = null,
    ): List<CameraFrameSample> {
        val thread = HandlerThread("CameraQualityTest-$cameraId").apply { start() }
        val handler = Handler(thread.looper)
        val samples = mutableListOf<CameraFrameSample>()

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Camera $cameraId has no stream configuration map")
            val outputSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { it.width.toLong() * it.height }
                ?: throw IllegalStateException("Camera $cameraId has no usable YUV output size")
            val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val targetFpsRange = fpsRanges?.maxByOrNull { it.upper }

            val imageReader = ImageReader.newInstance(
                outputSize.width, outputSize.height, ImageFormat.YUV_420_888, 3
            )
            // We only need frame TIMING (from CaptureCallback below), not pixel
            // data — just drain each image promptly so the pipeline doesn't stall.
            imageReader.setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                handler,
            )

            val camera = openCamera(cameraId, handler)
            try {
                val session = createCaptureSession(camera, listOf(imageReader.surface), handler)
                try {
                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader.surface)
                        targetFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }

                    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                            samples += CameraFrameSample(
                                sensorTimestampNs = ts,
                                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                            )
                        }
                    }

                    session.setRepeatingRequest(requestBuilder.build(), captureCallback, handler)

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
            thread.quitSafely()
        }

        EisLog.i(
            EisLog.Tag.CAMERA,
            "Collected ${samples.size} frame samples from camera $cameraId over ${durationMs}ms"
        )
        return samples.toList()
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
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("Camera $cameraId disconnected"))
                        }
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("Camera $cameraId error code $error"))
                        }
                    }
                }, handler)
            } catch (e: SecurityException) {
                cont.resumeWithException(IllegalStateException("CAMERA permission not granted", e))
            }
        }

    @Suppress("DEPRECATION") // createCaptureSession(List<Surface>, ...) deprecated API30+; simpler & fine down to API 21.
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
