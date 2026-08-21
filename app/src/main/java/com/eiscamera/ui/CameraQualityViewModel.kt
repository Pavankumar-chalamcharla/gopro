package com.eiscamera.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eiscamera.camera.CameraInfo
import com.eiscamera.camera.CameraStreamQualityAnalyzer
import com.eiscamera.camera.CameraStreamQualityCollector
import com.eiscamera.camera.CameraStreamQualitySnapshot
import com.eiscamera.capability.CapabilityEngine
import com.eiscamera.capability.CapabilityResult
import com.eiscamera.deviceprofile.CapabilityResultSnapshot
import com.eiscamera.deviceprofile.DeviceProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CameraQualityUiState {
    data object SelectingCamera : CameraQualityUiState
    data class Collecting(val cameraId: String, val secondsRemaining: Int) : CameraQualityUiState
    data class Done(
        val snapshot: CameraStreamQualitySnapshot,
        val refreshedCapability: CapabilityResult?,
        val saved: Boolean,
    ) : CameraQualityUiState
    data class Failed(val message: String) : CameraQualityUiState
}

/**
 * Drives the V0.4 Camera Stream Quality Test: pick a camera (from the
 * cameras V0.2 already inventoried), open it for a fixed window, and
 * measure real frame timing. Re-runs [CapabilityEngine] on completion with
 * the newly measured data, same pattern as [SensorQualityViewModel] for
 * V0.3 — see that class's kdoc for why the returned level doesn't change
 * yet even though the reasoning does.
 */
class CameraQualityViewModel(application: Application) : AndroidViewModel(application) {

    private val collector = CameraStreamQualityCollector(application)
    private val repository = DeviceProfileRepository(application)

    private val _state = MutableStateFlow<CameraQualityUiState>(CameraQualityUiState.SelectingCamera)
    val state: StateFlow<CameraQualityUiState> = _state.asStateFlow()

    /** Cameras available to test, sourced from the last V0.2 scan. */
    val availableCameras: List<CameraInfo> get() = repository.load()?.cameras ?: emptyList()

    companion object {
        private const val DURATION_MS = 5000L
    }

    fun testCamera(cameraId: String) {
        viewModelScope.launch {
            _state.value = CameraQualityUiState.Collecting(cameraId, (DURATION_MS / 1000L).toInt())
            try {
                val frames = collector.collect(cameraId, DURATION_MS) { remaining ->
                    _state.value = CameraQualityUiState.Collecting(cameraId, remaining)
                }
                if (frames.size < 2) {
                    _state.value = CameraQualityUiState.Failed(
                        "Not enough frames were captured (${frames.size}). The camera may have " +
                            "failed to start, or the test window was too short."
                    )
                    return@launch
                }

                val analyzed = CameraStreamQualityAnalyzer.analyze(frames)
                val snapshot = CameraStreamQualitySnapshot(
                    cameraId = cameraId,
                    testTimestampMs = System.currentTimeMillis(),
                    frameCount = analyzed.frameCount,
                    measuredFps = analyzed.measuredFps,
                    frameIntervalJitterMs = analyzed.frameIntervalJitterMs,
                    minIntervalMs = analyzed.minIntervalMs,
                    maxIntervalMs = analyzed.maxIntervalMs,
                    likelyDroppedFrames = analyzed.likelyDroppedFrames,
                    meanExposureTimeNs = analyzed.meanExposureTimeNs,
                    meanFrameDurationNs = analyzed.meanFrameDurationNs,
                )

                val existingProfile = repository.load()
                val refreshedCapability = existingProfile?.let { profile ->
                    val mergedCameraQuality = profile.cameraQuality.filterNot { it.cameraId == cameraId } + snapshot
                    CapabilityEngine().classify(
                        sensors = profile.sensors,
                        missingCriticalSensors = profile.missingCriticalSensors,
                        cameras = profile.cameras,
                        processing = profile.processing,
                        sensorQuality = profile.sensorQuality,
                        cameraQuality = mergedCameraQuality,
                    )
                }

                _state.value = CameraQualityUiState.Done(snapshot, refreshedCapability, saved = false)
            } catch (e: Exception) {
                _state.value = CameraQualityUiState.Failed(e.message ?: "Unknown error during camera capture")
            }
        }
    }

    /** Persists this camera's snapshot into the existing DeviceProfile, merging with any others already saved. */
    fun saveToProfile() {
        val current = _state.value
        if (current !is CameraQualityUiState.Done) return

        val profile = repository.load()
        if (profile == null) {
            // No V0.2 scan has completed yet in this session — nothing to attach to.
            // Known limitation: run the main scan first.
            return
        }

        val mergedCameraQuality = profile.cameraQuality.filterNot { it.cameraId == current.snapshot.cameraId } +
            current.snapshot

        val updatedCapability = current.refreshedCapability?.let {
            CapabilityResultSnapshot(
                level = it.level.name,
                reasons = it.reasons,
                evidenceStatus = if (it.fullyEvidenced) "FULLY_EVIDENCED" else "PROVISIONAL",
            )
        } ?: profile.capability

        repository.save(
            profile.copy(
                cameraQuality = mergedCameraQuality,
                capability = updatedCapability,
                scanTimestampMs = System.currentTimeMillis(),
            )
        )
        _state.value = current.copy(saved = true)
    }

    fun reset() {
        _state.value = CameraQualityUiState.SelectingCamera
    }
}
