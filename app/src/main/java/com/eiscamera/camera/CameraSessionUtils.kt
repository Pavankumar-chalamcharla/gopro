package com.eiscamera.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared Camera2 open/session-creation helpers, consolidated out of
 * CameraStreamQualityCollector (V0.4), CameraMotionCollector (V0.7), and
 * CameraPreviewViewModel (V1.0) — same reasoning as
 * motion/TimeSeriesCorrelation.kt and orientation/QuaternionMath.kt: avoid
 * a fourth copy of the same callback-to-coroutine bridging logic.
 */
object CameraSessionUtils {

    suspend fun openCamera(cameraManager: CameraManager, cameraId: String, handler: Handler): CameraDevice =
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

    @Suppress("DEPRECATION") // createCaptureSession(List<Surface>, ...) deprecated API30+; simpler & fine down to API 21.
    suspend fun createCaptureSession(
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
