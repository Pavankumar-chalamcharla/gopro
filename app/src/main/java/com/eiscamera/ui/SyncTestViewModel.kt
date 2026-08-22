package com.eiscamera.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eiscamera.camera.CameraInfo
import com.eiscamera.capability.CapabilityEngine
import com.eiscamera.capability.CapabilityResult
import com.eiscamera.deviceprofile.CapabilityResultSnapshot
import com.eiscamera.deviceprofile.DeviceProfileRepository
import com.eiscamera.synchronization.CameraMotionCollector
import com.eiscamera.synchronization.SyncAnalyzer
import com.eiscamera.synchronization.SyncResultSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SyncTestUiState {
    data object SelectingCamera : SyncTestUiState
    data class Collecting(val cameraId: String, val secondsRemaining: Int) : SyncTestUiState
    data class Done(
        val snapshot: SyncResultSnapshot,
        val refreshedCapability: CapabilityResult?,
        val saved: Boolean,
    ) : SyncTestUiState
    data class Failed(val message: String) : SyncTestUiState
}

/**
 * Drives the V0.7 gyro-camera synchronization test: pick a camera, collect
 * gyro + rotation-vector + camera motion concurrently, estimate the clock
 * offset, and re-run [CapabilityEngine] — which, with this the third and
 * final measurement, can now actually reach LEVEL_2_ADVANCED if every
 * threshold across V0.3/V0.4/V0.7 is met. Same completion pattern as
 * [SensorQualityViewModel] and [CameraQualityViewModel].
 */
class SyncTestViewModel(application: Application) : AndroidViewModel(application) {

    private val collector = CameraMotionCollector(application)
    private val repository = DeviceProfileRepository(application)

    private val _state = MutableStateFlow<SyncTestUiState>(SyncTestUiState.SelectingCamera)
    val state: StateFlow<SyncTestUiState> = _state.asStateFlow()

    val availableCameras: List<CameraInfo> get() = repository.load()?.cameras ?: emptyList()

    companion object {
        // Slightly longer than V0.3/V0.4's 5s: this test needs enough real
        // motion in front of the camera AND enough gyro samples to overlap.
        private const val DURATION_MS = 6000L
    }

    fun testCamera(cameraId: String) {
        viewModelScope.launch {
            _state.value = SyncTestUiState.Collecting(cameraId, (DURATION_MS / 1000L).toInt())
            try {
                val collected = collector.collect(cameraId, DURATION_MS) { remaining ->
                    _state.value = SyncTestUiState.Collecting(cameraId, remaining)
                }

                val offset = SyncAnalyzer.estimateOffset(collected.gyroSamples, collected.cameraMotionSamples)

                val snapshot = SyncResultSnapshot(
                    cameraId = cameraId,
                    testTimestampMs = System.currentTimeMillis(),
                    cameraTimestampSource = collected.cameraTimestampSource,
                    gyroSampleCount = collected.gyroSamples.size,
                    cameraFrameCount = collected.cameraMotionSamples.size,
                    estimatedOffsetMs = offset?.estimatedOffsetMs,
                    correlation = offset?.correlation,
                )

                val existingProfile = repository.load()
                val refreshedCapability = existingProfile?.let { profile ->
                    CapabilityEngine().classify(
                        sensors = profile.sensors,
                        missingCriticalSensors = profile.missingCriticalSensors,
                        cameras = profile.cameras,
                        processing = profile.processing,
                        sensorQuality = profile.sensorQuality,
                        cameraQuality = profile.cameraQuality,
                        syncResult = snapshot,
                    )
                }

                _state.value = SyncTestUiState.Done(snapshot, refreshedCapability, saved = false)
            } catch (e: Exception) {
                _state.value = SyncTestUiState.Failed(e.message ?: "Unknown error during sync test")
            }
        }
    }

    fun saveToProfile() {
        val current = _state.value
        if (current !is SyncTestUiState.Done) return
        val profile = repository.load() ?: return

        val updatedCapability = current.refreshedCapability?.let {
            CapabilityResultSnapshot(
                level = it.level.name,
                reasons = it.reasons,
                evidenceStatus = if (it.fullyEvidenced) "FULLY_EVIDENCED" else "PROVISIONAL",
            )
        } ?: profile.capability

        repository.save(
            profile.copy(
                syncResult = current.snapshot,
                capability = updatedCapability,
                scanTimestampMs = System.currentTimeMillis(),
            )
        )
        _state.value = current.copy(saved = true)
    }

    fun reset() {
        _state.value = SyncTestUiState.SelectingCamera
    }
}
