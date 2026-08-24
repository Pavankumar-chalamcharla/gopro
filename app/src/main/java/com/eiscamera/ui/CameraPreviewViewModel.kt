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
import com.eiscamera.recording.TestPatternRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface CameraPreviewUiState {
    data object Idle : CameraPreviewUiState
    data object Starting : CameraPreviewUiState
    data class Running(val cameraId: String) : CameraPreviewUiState
    data class Failed(val message: String) : CameraPreviewUiState
}

/**
 * V1.1a built the state machine with no actual recording. V1.1b-1 wires
 * in a REAL encoder — but deliberately still not the real camera feed:
 * tapping Record now runs TestPatternRecorder, producing an actual
 * playable MP4 of a solid, slowly-cycling color, proving MediaCodec +
 * manual EGL + MediaMuxer genuinely work on this device before V1.1b-2
 * attempts the much harder part (feeding it real stabilized frames).
 * [Stopped.note] reports the real outcome — the saved file's path on
 * success, or the real error on failure — never a placeholder message
 * pretending to be more than it is (spec section 42).
 *
 * KNOWN LIMITATION, stated rather than hidden: TestPatternRecorder
 * currently runs a fixed short duration with no way to interrupt it
 * early once started (its internal loop isn't cancellation-aware) — so
 * during that window the Record/Stop button is disabled rather than
 * implying a "stop" that wouldn't actually shorten the clip. Real
 * open-ended start/stop control is V1.1b-2's job, once this is
 * redesigned around the continuously-running camera feed anyway.
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

    /** V1.1b-1: runs an actual (fixed-duration, camera-independent) test
     *  recording via TestPatternRecorder — see RecordingUiState kdoc for
     *  what this does and doesn't prove yet. No-op if already recording
     *  or the preview itself isn't running. */
    fun startRecording() {
        if (_state.value !is CameraPreviewUiState.Running) return
        if (_recordingState.value is RecordingUiState.Recording) return

        val context = getApplication<Application>()
        val outputDir = context.getExternalFilesDir("recordings")
        if (outputDir == null) {
            _recordingState.value = RecordingUiState.Stopped(0, "Failed: external storage unavailable")
            return
        }
        val outputFile = File(outputDir, "test_clip_${System.currentTimeMillis()}.mp4")

        recordingTickerJob?.cancel()
        recordingTickerJob = viewModelScope.launch {
            var elapsed = 0
            _recordingState.value = RecordingUiState.Recording(elapsed)
            val tickerJob = launch {
                while (true) {
                    delay(1000)
                    elapsed++
                    _recordingState.value = RecordingUiState.Recording(elapsed)
                }
            }
            val result = withContext(Dispatchers.Default) {
                TestPatternRecorder.recordTestClip(outputFile, durationSeconds = TEST_CLIP_DURATION_S)
            }
            tickerJob.cancel()
            _recordingState.value = if (result.success) {
                RecordingUiState.Stopped(
                    elapsedSeconds = TEST_CLIP_DURATION_S,
                    note = "Test clip saved: ${result.outputFile?.absolutePath}",
                )
            } else {
                RecordingUiState.Stopped(elapsedSeconds = elapsed, note = "Failed: ${result.error}")
            }
        }
    }

    /** See RecordingUiState kdoc's "KNOWN LIMITATION": this cancels UI-side
     *  tracking, but TestPatternRecorder's fixed-duration clip, once
     *  started, is not currently interruptible mid-recording. */
    fun stopRecording() {
        val current = _recordingState.value as? RecordingUiState.Recording ?: return
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _recordingState.value = RecordingUiState.Stopped(
            elapsedSeconds = current.elapsedSeconds,
            note = "UI tracking stopped, but the test clip itself keeps encoding to completion in the background (V1.1b-1 limitation — see kdoc).",
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

    companion object {
        /** V1.1b-1's TestPatternRecorder isn't cancellation-aware mid-clip
         *  (see RecordingUiState kdoc) — kept short so that limitation is a
         *  minor inconvenience, not a real problem, while it's still true. */
        private const val TEST_CLIP_DURATION_S = 5
    }
}
