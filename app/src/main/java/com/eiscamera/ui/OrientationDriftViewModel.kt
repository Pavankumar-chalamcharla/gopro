package com.eiscamera.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eiscamera.capability.CapabilityEngine
import com.eiscamera.capability.CapabilityResult
import com.eiscamera.deviceprofile.CapabilityResultSnapshot
import com.eiscamera.deviceprofile.DeviceProfileRepository
import com.eiscamera.orientation.OrientationDriftAnalyzer
import com.eiscamera.orientation.OrientationDriftSnapshot
import com.eiscamera.sensors.SensorQualityCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OrientationDriftUiState {
    data object Ready : OrientationDriftUiState
    data class Collecting(val secondsRemaining: Int) : OrientationDriftUiState
    data class Done(
        val snapshot: OrientationDriftSnapshot,
        val refreshedCapability: CapabilityResult?,
        val saved: Boolean,
    ) : OrientationDriftUiState
    data class Failed(val message: String) : OrientationDriftUiState
}

/**
 * Drives the V0.8 Orientation Drift Test: reuses [SensorQualityCollector]
 * (V0.3) to gather gyro + rotation-vector samples concurrently, then runs
 * [OrientationDriftAnalyzer] to empirically measure how far pure gyro
 * integration drifts from the phone's fused reference over a real window
 * — with bias correction applied automatically if a V0.3 result is already
 * saved. Re-runs CapabilityEngine on completion for transparency, but
 * (deliberately) this does not change the returned CapabilityLevel — see
 * CapabilityEngine kdoc.
 */
class OrientationDriftViewModel(application: Application) : AndroidViewModel(application) {

    private val collector = SensorQualityCollector(application)
    private val repository = DeviceProfileRepository(application)

    private val _state = MutableStateFlow<OrientationDriftUiState>(OrientationDriftUiState.Ready)
    val state: StateFlow<OrientationDriftUiState> = _state.asStateFlow()

    companion object {
        // Longer than V0.3's 5s: drift needs real elapsed time and real
        // motion to become a meaningful, non-trivial number.
        private const val DURATION_MS = 10000L
    }

    fun start() {
        viewModelScope.launch {
            _state.value = OrientationDriftUiState.Collecting((DURATION_MS / 1000L).toInt())
            try {
                val collected = collector.collect(DURATION_MS) { remaining ->
                    _state.value = OrientationDriftUiState.Collecting(remaining)
                }
                if (collected.gyroSamples.size < 2 || collected.rotationVectorQuaternions.size < 2) {
                    _state.value = OrientationDriftUiState.Failed(
                        "Not enough samples were collected (gyro=${collected.gyroSamples.size}, " +
                            "rotation vector=${collected.rotationVectorQuaternions.size})."
                    )
                    return@launch
                }

                val existingProfile = repository.load()
                val bias = existingProfile?.sensorQuality?.let {
                    doubleArrayOf(it.biasXRadS, it.biasYRadS, it.biasZRadS)
                }

                val result = OrientationDriftAnalyzer.analyzeDrift(
                    gyroSamples = collected.gyroSamples,
                    referenceQuaternions = collected.rotationVectorQuaternions,
                    biasRadS = bias,
                )
                if (result == null) {
                    _state.value = OrientationDriftUiState.Failed(
                        "Could not compute a drift estimate from the collected samples."
                    )
                    return@launch
                }

                val snapshot = OrientationDriftSnapshot(
                    testTimestampMs = System.currentTimeMillis(),
                    durationS = result.durationS,
                    gyroSampleCount = result.gyroSampleCount,
                    referenceSampleCount = result.referenceSampleCount,
                    biasCorrectionApplied = bias != null,
                    driftUncorrectedDegrees = Math.toDegrees(result.driftUncorrectedRad),
                    driftCorrectedDegrees = result.driftCorrectedRad?.let { Math.toDegrees(it) },
                )

                val refreshedCapability = existingProfile?.let { profile ->
                    CapabilityEngine().classify(
                        sensors = profile.sensors,
                        missingCriticalSensors = profile.missingCriticalSensors,
                        cameras = profile.cameras,
                        processing = profile.processing,
                        sensorQuality = profile.sensorQuality,
                        cameraQuality = profile.cameraQuality,
                        syncResult = profile.syncResult,
                        orientationDrift = snapshot,
                    )
                }

                _state.value = OrientationDriftUiState.Done(snapshot, refreshedCapability, saved = false)
            } catch (e: Exception) {
                _state.value = OrientationDriftUiState.Failed(e.message ?: "Unknown error during drift test")
            }
        }
    }

    fun saveToProfile() {
        val current = _state.value
        if (current !is OrientationDriftUiState.Done) return
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
                orientationDrift = current.snapshot,
                capability = updatedCapability,
                scanTimestampMs = System.currentTimeMillis(),
            )
        )
        _state.value = current.copy(saved = true)
    }

    fun reset() {
        _state.value = OrientationDriftUiState.Ready
    }
}
