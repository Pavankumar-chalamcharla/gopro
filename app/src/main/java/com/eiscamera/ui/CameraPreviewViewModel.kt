package com.eiscamera.ui

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eiscamera.camera.CameraInfo
import com.eiscamera.camera.CameraSessionUtils
import com.eiscamera.deviceprofile.DeviceProfileRepository
import com.eiscamera.logging.EisLog
import com.eiscamera.motion.LiveOrientationPipeline
import com.eiscamera.motion.LiveOrientationState
import com.eiscamera.recording.EncoderSession
import com.eiscamera.recording.MediaStoreVideoOutput
import com.eiscamera.rendering.CameraGlRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed interface CameraPreviewUiState {
    data object Idle : CameraPreviewUiState
    data object Starting : CameraPreviewUiState
    data class Running(val cameraId: String) : CameraPreviewUiState
    data class Failed(val message: String) : CameraPreviewUiState
}

/**
 * V1.1a built the state machine with no actual recording. V1.1b-1 proved
 * MediaCodec + manual EGL + MediaMuxer work at all, in isolation, with a
 * solid-color test pattern (deliberately not the camera feed). V1.1b-2
 * (this version) is the real thing: [startRecording] now drives an
 * open-ended [EncoderSession] and tells the live [CameraGlRenderer] to
 * draw the SAME stabilized frame it's already showing on screen into the
 * encoder too, via [CameraGlRenderer.beginRecording] — genuine open-ended
 * start/stop control, the limitation V1.1b-1 explicitly left for this
 * stage.
 *
 * RENDERER REGISTRATION: [CameraGlRenderer] and its [GLSurfaceView] are
 * created inside CameraPreviewScreen's Compose factory, not by this
 * ViewModel — [registerRenderer]/[unregisterRenderer] are how the screen
 * hands over live references so recording can reach the GL thread via
 * [GLSurfaceView.queueEvent]. If a recording is somehow still active when
 * [unregisterRenderer] is called (the screen going away mid-recording),
 * it's stopped first rather than leaking the encoder.
 *
 * STOP ORDERING: releasing the encoder's input Surface while
 * CameraGlRenderer still holds an EGLSurface wrapping it is unsafe —
 * [stopRecording] uses [suspendCancellableCoroutine] to genuinely wait
 * for [CameraGlRenderer.endRecording] to finish running on the GL thread
 * (queueEvent alone doesn't provide that confirmation) before calling
 * [EncoderSession.stop].
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

    private var activeRenderer: CameraGlRenderer? = null
    private var activeGlSurfaceView: GLSurfaceView? = null
    private var activeEncoderSession: EncoderSession? = null
    private var activePfd: ParcelFileDescriptor? = null
    private var activePendingUri: Uri? = null
    private var activeDisplayName: String? = null

    /** Called once the live preview's GLSurfaceView/renderer exist, so
     *  recording can reach them. See class kdoc. */
    fun registerRenderer(renderer: CameraGlRenderer, glSurfaceView: GLSurfaceView) {
        activeRenderer = renderer
        activeGlSurfaceView = glSurfaceView
    }

    /** Called when the preview's view is going away. Stops any active
     *  recording first rather than leaking the encoder. */
    fun unregisterRenderer() {
        if (activeEncoderSession != null) stopRecording()
        activeRenderer = null
        activeGlSurfaceView = null
    }

    /** Starts an open-ended recording of the live stabilized preview.
     *  No-op if already recording, the preview isn't running, or the
     *  renderer isn't registered yet. */
    fun startRecording() {
        if (_state.value !is CameraPreviewUiState.Running) return
        if (_recordingState.value is RecordingUiState.Recording) return
        val renderer = activeRenderer
        val glView = activeGlSurfaceView
        if (renderer == null || glView == null) {
            _recordingState.value = RecordingUiState.Stopped(0, "Failed: preview not ready yet")
            return
        }

        val context = getApplication<Application>()
        val displayName = "eiscamera_${System.currentTimeMillis()}.mp4"

        recordingTickerJob?.cancel()
        recordingTickerJob = viewModelScope.launch {
            var elapsed = 0
            _recordingState.value = RecordingUiState.Recording(elapsed)

            val pending = MediaStoreVideoOutput.createPending(context, displayName)
            if (pending == null) {
                _recordingState.value = RecordingUiState.Stopped(0, "Failed: could not create output via MediaStore")
                return@launch
            }
            val pfd = context.contentResolver.openFileDescriptor(pending.uri, "w")
            if (pfd == null) {
                MediaStoreVideoOutput.deletePending(context, pending.uri)
                _recordingState.value = RecordingUiState.Stopped(0, "Failed: could not open output for writing")
                return@launch
            }
            val encoderSession = withContext(Dispatchers.Default) { EncoderSession.start(pfd.fileDescriptor) }
            if (encoderSession == null) {
                runCatching { pfd.close() }
                MediaStoreVideoOutput.deletePending(context, pending.uri)
                _recordingState.value = RecordingUiState.Stopped(0, "Failed: could not start encoder")
                return@launch
            }

            activeEncoderSession = encoderSession
            activePfd = pfd
            activePendingUri = pending.uri
            activeDisplayName = displayName
            glView.queueEvent { renderer.beginRecording(encoderSession) }
            EisLog.i(EisLog.Tag.ENCODER, "Recording started: $displayName")

            val tickerJob = launch {
                while (true) {
                    delay(1000)
                    elapsed++
                    _recordingState.value = RecordingUiState.Recording(elapsed)
                }
            }
            try {
                awaitCancellation()
            } finally {
                tickerJob.cancel()
            }
        }
    }

    /** Stops an active recording and finalizes the saved file. Safe to
     *  call even if not recording. */
    fun stopRecording() {
        val encoderSession = activeEncoderSession ?: return
        val renderer = activeRenderer
        val glView = activeGlSurfaceView
        val pfd = activePfd
        val pendingUri = activePendingUri
        val displayName = activeDisplayName ?: "recording"
        val elapsed = (_recordingState.value as? RecordingUiState.Recording)?.elapsedSeconds ?: 0

        activeEncoderSession = null
        activePfd = null
        activePendingUri = null
        activeDisplayName = null
        recordingTickerJob?.cancel()
        recordingTickerJob = null

        viewModelScope.launch(Dispatchers.Default) {
            if (renderer != null && glView != null) {
                // Genuinely WAIT for endRecording to finish on the GL thread —
                // see class kdoc's STOP ORDERING notes for why this can't be
                // fire-and-forget.
                suspendCancellableCoroutine { cont ->
                    glView.queueEvent {
                        renderer.endRecording()
                        cont.resume(Unit)
                    }
                }
            }
            encoderSession.stop()
            runCatching { pfd?.close() }
            val context = getApplication<Application>()
            if (pendingUri != null) MediaStoreVideoOutput.finalizePending(context, pendingUri)
            EisLog.i(EisLog.Tag.ENCODER, "Recording stopped: $displayName")
            _recordingState.value = RecordingUiState.Stopped(
                elapsedSeconds = elapsed,
                note = "Saved to Movies/EisCamera as $displayName",
            )
        }
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
        if (activeEncoderSession != null) stopRecording()
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

/**
 * V1.1a built the state machine with no actual recording. V1.1b-1 wired
 * in a real encoder proven on an isolated test pattern. V1.1b-2 (this
 * version) drives real, open-ended recording of the actual stabilized
 * camera feed — see [CameraPreviewViewModel]'s kdoc for the full
 * start/stop coordination this required.
 */
sealed interface RecordingUiState {
    data object Idle : RecordingUiState
    data class Recording(val elapsedSeconds: Int) : RecordingUiState
    data class Stopped(val elapsedSeconds: Int, val note: String) : RecordingUiState
}
