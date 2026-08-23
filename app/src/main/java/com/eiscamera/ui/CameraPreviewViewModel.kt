package com.eiscamera.ui

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eiscamera.camera.CameraInfo
import com.eiscamera.camera.CameraSessionUtils
import com.eiscamera.deviceprofile.DeviceProfileRepository
import com.eiscamera.logging.EisLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CameraPreviewUiState {
    data object Idle : CameraPreviewUiState
    data object Starting : CameraPreviewUiState
    data class Running(val cameraId: String) : CameraPreviewUiState
    data class Failed(val message: String) : CameraPreviewUiState
}

/**
 * Manages a CONTINUOUS Camera2 preview session — the first component in
 * this project that isn't a bounded, timed collection. Every prior camera
 * component (V0.4, V0.7) opened the camera, ran for a fixed number of
 * seconds, and closed it. This one runs until the screen is left. No
 * stabilization happens here yet (roadmap V1.0's first sub-stage) — this
 * is deliberately just the foundation: get a real-time preview on screen
 * with correct lifecycle management, before adding gyro integration,
 * filtering, and warping on top of it. See docs/ROADMAP.md.
 */
class CameraPreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val repository = DeviceProfileRepository(application)

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val _state = MutableStateFlow<CameraPreviewUiState>(CameraPreviewUiState.Idle)
    val state: StateFlow<CameraPreviewUiState> = _state.asStateFlow()

    /** Cameras available to preview, sourced from the last V0.2 scan. */
    val availableCameras: List<CameraInfo> get() = repository.load()?.cameras ?: emptyList()

    /** Starts a continuous preview targeting [surface]. No-op if already running/starting. */
    fun start(cameraId: String, surface: Surface) {
        if (_state.value is CameraPreviewUiState.Running || _state.value is CameraPreviewUiState.Starting) return
        viewModelScope.launch {
            _state.value = CameraPreviewUiState.Starting
            try {
                val t = HandlerThread("CameraPreview-$cameraId").apply { start() }
                thread = t
                val h = Handler(t.looper)
                handler = h

                val cam = CameraSessionUtils.openCamera(cameraManager, cameraId, h)
                camera = cam

                val sess = CameraSessionUtils.createCaptureSession(cam, listOf(surface), h)
                session = sess

                val requestBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                }
                sess.setRepeatingRequest(requestBuilder.build(), null, h)

                EisLog.i(EisLog.Tag.CAMERA, "Live preview started on camera $cameraId")
                _state.value = CameraPreviewUiState.Running(cameraId)
            } catch (e: Exception) {
                EisLog.e(EisLog.Tag.CAMERA, "Failed to start live preview", e)
                cleanup()
                _state.value = CameraPreviewUiState.Failed(e.message ?: "Unknown error starting preview")
            }
        }
    }

    /** Stops the preview and releases the camera. Safe to call even if not running —
     *  this is the resource-ownership boundary spec section 28 calls for: the camera
     *  must be explicitly released, not left for garbage collection to eventually close. */
    fun stop() {
        cleanup()
        _state.value = CameraPreviewUiState.Idle
    }

    private fun cleanup() {
        try {
            session?.stopRepeating()
        } catch (e: Exception) {
            // session may already be invalid (e.g. camera disconnected) — safe to ignore here.
        }
        session?.close()
        session = null
        camera?.close()
        camera = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onCleared() {
        cleanup()
        super.onCleared()
    }
}
