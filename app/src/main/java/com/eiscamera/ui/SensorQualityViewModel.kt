package com.eiscamera.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eiscamera.capability.CapabilityEngine
import com.eiscamera.capability.CapabilityResult
import com.eiscamera.deviceprofile.CapabilityResultSnapshot
import com.eiscamera.deviceprofile.DeviceProfileRepository
import com.eiscamera.sensors.SensorQualityAnalyzer
import com.eiscamera.sensors.SensorQualityCollector
import com.eiscamera.sensors.SensorQualitySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SensorQualityUiState {
    data object ReadyForStationary : SensorQualityUiState
    data class CollectingStationary(val secondsRemaining: Int) : SensorQualityUiState
    data object ReadyForDynamic : SensorQualityUiState
    data class CollectingDynamic(val secondsRemaining: Int) : SensorQualityUiState
    data object Analyzing : SensorQualityUiState
    data class Done(
        val stationary: SensorQualityAnalyzer.StationaryGyroResult,
        val dynamic: SensorQualityAnalyzer.DynamicResponseResult?,
        val refreshedCapability: CapabilityResult?,
        val snapshot: SensorQualitySnapshot,
        val saved: Boolean,
    ) : SensorQualityUiState
    data class Failed(val message: String) : SensorQualityUiState
}

/**
 * Drives the V0.3 Sensor Quality Test's two-phase flow (spec section 21
 * calibration-workflow style): a stationary noise/jitter measurement,
 * followed by a deliberate-motion cross-check against the rotation vector
 * sensor. On completion, re-runs [CapabilityEngine] with the newly
 * measured data so the person can see how the reasoning trail changes —
 * without the returned level itself changing yet (see CapabilityEngine
 * kdoc: Advanced+ still needs V0.4/V0.7).
 */
class SensorQualityViewModel(application: Application) : AndroidViewModel(application) {

    private val collector = SensorQualityCollector(application)
    private val repository = DeviceProfileRepository(application)

    private val _state = MutableStateFlow<SensorQualityUiState>(SensorQualityUiState.ReadyForStationary)
    val state: StateFlow<SensorQualityUiState> = _state.asStateFlow()

    private var stationaryResult: SensorQualityAnalyzer.StationaryGyroResult? = null

    companion object {
        private const val STATIONARY_DURATION_MS = 5000L
        private const val DYNAMIC_DURATION_MS = 5000L
    }

    fun startStationaryPhase() {
        viewModelScope.launch {
            try {
                val collected = collector.collect(STATIONARY_DURATION_MS) { remaining ->
                    _state.value = SensorQualityUiState.CollectingStationary(remaining)
                }
                if (collected.gyroSamples.size < 2) {
                    _state.value = SensorQualityUiState.Failed(
                        "Not enough gyroscope samples were collected (${collected.gyroSamples.size}). " +
                            "This device may not deliver gyroscope events reliably."
                    )
                    return@launch
                }
                stationaryResult = SensorQualityAnalyzer.analyzeStationary(collected.gyroSamples)
                _state.value = SensorQualityUiState.ReadyForDynamic
            } catch (e: Exception) {
                _state.value = SensorQualityUiState.Failed(e.message ?: "Unknown error during stationary phase")
            }
        }
    }

    fun startDynamicPhase() {
        viewModelScope.launch {
            try {
                val collected = collector.collect(DYNAMIC_DURATION_MS) { remaining ->
                    _state.value = SensorQualityUiState.CollectingDynamic(remaining)
                }
                _state.value = SensorQualityUiState.Analyzing

                val stationary = stationaryResult
                if (stationary == null) {
                    _state.value = SensorQualityUiState.Failed("Stationary phase result missing — restart the test.")
                    return@launch
                }

                val dynamic = SensorQualityAnalyzer.analyzeDynamicResponse(
                    gyroSamples = collected.gyroSamples,
                    rotationVectorQuaternions = collected.rotationVectorQuaternions,
                )

                val snapshot = SensorQualitySnapshot(
                    testTimestampMs = System.currentTimeMillis(),
                    stationarySampleCount = stationary.sampleCount,
                    measuredRateHz = stationary.measuredRateHz,
                    timestampJitterMs = stationary.timestampJitterMs,
                    timestampMonotonic = stationary.timestampMonotonic,
                    stationaryNoiseStdDevRadS = stationary.worstAxisStdDev,
                    stationaryBiasRadS = stationary.biasMagnitude,
                    dynamicTestAvailable = dynamic != null,
                    dynamicLagMs = dynamic?.bestLagMs,
                    dynamicCorrelation = dynamic?.bestCorrelation,
                    dynamicGyroPeakRadS = dynamic?.gyroPeakMagnitudeRadS,
                    dynamicRotationVectorPeakRadS = dynamic?.rotationVectorPeakMagnitudeRadS,
                )

                val existingProfile = repository.load()
                val refreshedCapability = existingProfile?.let { profile ->
                    CapabilityEngine().classify(
                        sensors = profile.sensors,
                        missingCriticalSensors = profile.missingCriticalSensors,
                        cameras = profile.cameras,
                        processing = profile.processing,
                        sensorQuality = snapshot,
                    )
                }

                _state.value = SensorQualityUiState.Done(
                    stationary = stationary,
                    dynamic = dynamic,
                    refreshedCapability = refreshedCapability,
                    snapshot = snapshot,
                    saved = false,
                )
            } catch (e: Exception) {
                _state.value = SensorQualityUiState.Failed(e.message ?: "Unknown error during dynamic phase")
            }
        }
    }

    /** Persists the snapshot (and refreshed capability reasoning, if any) into the existing DeviceProfile. */
    fun saveToProfile() {
        val current = _state.value
        if (current !is SensorQualityUiState.Done) return

        val profile = repository.load()
        if (profile == null) {
            // No V0.2 scan has completed yet in this session — nothing to attach the
            // sensor-quality result to. Known limitation: run the main scan first.
            return
        }

        val updatedCapability = current.refreshedCapability?.let {
            CapabilityResultSnapshot(
                level = it.level.name,
                reasons = it.reasons,
                evidenceStatus = if (it.fullyEvidenced) "FULLY_EVIDENCED" else "PROVISIONAL",
            )
        } ?: profile.capability

        repository.save(
            profile.copy(
                sensorQuality = current.snapshot,
                capability = updatedCapability,
                scanTimestampMs = System.currentTimeMillis(),
            )
        )
        _state.value = current.copy(saved = true)
    }

    fun reset() {
        stationaryResult = null
        _state.value = SensorQualityUiState.ReadyForStationary
    }
}
