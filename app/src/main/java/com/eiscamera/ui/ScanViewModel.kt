package com.eiscamera.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eiscamera.deviceprofile.DeviceProfile
import com.eiscamera.deviceprofile.DeviceProfileRepository
import com.eiscamera.diagnostics.DeviceScanCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Scanning : ScanUiState
    data class Done(val profile: DeviceProfile, val fromCache: Boolean) : ScanUiState
    data class Failed(val message: String) : ScanUiState
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceProfileRepository(application)
    private val coordinator = DeviceScanCoordinator(application)

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    init {
        loadCachedOrScan()
    }

    private fun loadCachedOrScan() {
        val cached = repository.load()
        if (cached != null) {
            _state.value = ScanUiState.Done(cached, fromCache = true)
        } else {
            rescan()
        }
    }

    fun rescan() {
        _state.value = ScanUiState.Scanning
        viewModelScope.launch {
            try {
                val profile = coordinator.runFullScan()
                repository.save(profile)
                _state.value = ScanUiState.Done(profile, fromCache = false)
            } catch (e: Exception) {
                _state.value = ScanUiState.Failed(e.message ?: "Unknown error during scan")
            }
        }
    }
}
