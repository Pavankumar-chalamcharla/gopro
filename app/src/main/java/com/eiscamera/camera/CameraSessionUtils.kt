package com.eiscamera.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.util.Size
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

    /**
     * Picks an output size for a SurfaceTexture-backed CONTINUOUS preview.
     *
     * WHY THIS EXISTS: a bare `SurfaceTexture(textureId)` defaults to a
     * 1x1 pixel buffer until told otherwise. `TextureView` sets this
     * automatically to match its own on-screen size, which is why
     * V1.0a/b (TextureView-based) never needed this — but V1.0c-1's
     * hand-created SurfaceTexture (for the GL/OES render path) had no
     * equivalent, so Camera2 fell back to whatever tiny size was
     * compatible with an effectively-1x1 target, producing a real,
     * visible resolution drop. Call [android.graphics.SurfaceTexture
     * .setDefaultBufferSize] with this method's result BEFORE wrapping
     * the SurfaceTexture in a Surface and starting the capture session.
     *
     * TARGET: ~1280x720. This is a live, continuously re-rendered (and
     * from V1.0c-2 on, continuously GPU-transformed) preview, not a
     * final recording — the sensor's absolute maximum resolution is
     * neither necessary here nor guaranteed to even be a valid
     * SurfaceTexture-class streaming size on every device, unlike a
     * still-capture size.
     * SELECTION: the available SurfaceTexture-class size whose pixel
     * count is closest to the target's — deliberately queried via
     * `getOutputSizes(SurfaceTexture::class.java)`, not the
     * `ImageFormat.YUV_420_888` sizes V0.4's collector queries, which
     * can legitimately differ.
     * DEVICE-DEPENDENT: yes — the chosen size varies by camera; this
     * fixes the SELECTION STRATEGY, not a hardcoded resolution.
     */
    fun choosePreviewSize(
        characteristics: CameraCharacteristics,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
    ): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera has no stream configuration map")
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            ?: error("Camera has no usable SurfaceTexture output sizes")
        val targetArea = targetWidth.toLong() * targetHeight
        return sizes.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - targetArea) }
            ?: error("Camera reported an empty SurfaceTexture size list")
    }
}
