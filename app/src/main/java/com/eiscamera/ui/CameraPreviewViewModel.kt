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
import com.eiscamera.motion.LiveOrientationPipeline
import com.eiscamera.motion.LiveOrientationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface CameraPreviewUiState {
    data object Idle : CameraPreviewUiState
    data object Starting : CameraPreviewUiState
    data class Running(val cameraId: String) : CameraPreviewUiState
    data class Failed(val message: String) : CameraPreviewUiState
}

/**
 * V1.1a: recording state machine ONLY — no video is actually saved yet.
 * This exists to prove the UI/state side works (start/stop, a real
 * elapsed-time counter) before V1.1b adds the substantially harder part:
 * a second EGL surface targeting a MediaCodec encoder's input, drawing
 * the SAME stabilized frame there as well as to the screen. That's a
 * comparable jump in complexity to V1.0c's GL work, which needed several
 * real-device rounds to get right — not something to wire in blind
 * alongside everything else here. [Stopped.note] says so explicitly
 * rather than implying a file was saved when none was (spec section 42:
 * never claim a capability without evidence).
 */
sealed interface RecordingUiState {
    data object Idle : RecordingUiState
    data class Recording(val elapsedSeconds: Int) : RecordingUiState
    data class Stopped(val elapsedSeconds: Int, val note: String) : RecordingUiState
}

/**
 * Manages a CONTINUOUS Camera2 preview session (V1.0a) plus, since V1.0b,
 * a continuously-running orientation pipeline alongside it — the first
 * time V0.8's integrator and V0.9's filter run outside a bounded test
 * window. The two are started/stopped together deliberately: proving
 * they can run concurrently without one stalling the other is V1.0b's
 * actual goal. V1.0c-2 applies the resulting compensation live; V1.0d
 * adds measured performance numbers. V1.1a adds recording STATE only
 * (see RecordingUiState kdoc) — no video is saved yet. See
 * docs/ROADMAP.md for the full breakdown.
 */
class CameraPreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val repository = DeviceProfileRepository(application)

    private val orientationPipeline = LiveOrientationPipeline(
        context = application,
        biasRadS = repository.load()?.sensorQuality?.let { doubleArrayOf(it.biasXRadS, it.biasYRadS, it.biasZRadS) },
    )
    val orientationState: StateFlow<LiveOrientationState> = orientationPipeline.state

    /** V1.0c-2: the GL renderer's per-frame read path into the orientation
     *  pipeline — see LiveOrientationPipeline.currentCorrectionQuaternion
     *  for the thread-safety and sign-convention notes. */
    fun currentCorrectionQuaternion(): DoubleArray = orientationPipeline.currentCorrectionQuaternion()

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val _state = MutableStateFlow<CameraPreviewUiState>(CameraPreviewUiState.Idle)
    val state: StateFlow<CameraPreviewUiState> = _state.asStateFlow()

    /** Cameras available to preview, sourced from the last V0.2 scan. */
    val availableCameras: List<CameraInfo> get() = repository.load()?.cameras ?: emptyList()

    private val _recordingState = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val recordingState: StateFlow<RecordingUiState> = _recordingState.asStateFlow()
    private var recordingTickerJob: Job? = null

    /** V1.1a: starts the elapsed-time counter only — no encoder yet (see
     *  RecordingUiState kdoc). No-op if already recording or the preview
     *  itself isn't running. */
    fun startRecording() {
        if (_state.value !is CameraPreviewUiState.Running) return
        if (_recordingState.value is RecordingUiState.Recording) return
        recordingTickerJob?.cancel()
        recordingTickerJob = viewModelScope.launch {
            var elapsed = 0
            _recordingState.value = RecordingUiState.Recording(elapsed)
            while (true) {
                delay(1000)
                elapsed++
                _recordingState.value = RecordingUiState.Recording(elapsed)
            }
        }
    }

    /** Stops the counter. Safe to call even if not recording. */
    fun stopRecording() {
        val elapsed = (_recordingState.value as? RecordingUiState.Recording)?.elapsedSeconds ?: 0
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _recordingState.value = RecordingUiState.Stopped(
            elapsedSeconds = elapsed,
            note = "Encoding not implemented yet (V1.1b) — no file was saved.",
        )
    }

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

                orientationPipeline.start()

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
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _recordingState.value = RecordingUiState.Idle
        orientationPipeline.stop()
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
