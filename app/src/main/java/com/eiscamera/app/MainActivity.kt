package com.eiscamera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eiscamera.ui.DiagnosticScreen
import com.eiscamera.ui.ScanViewModel
import com.eiscamera.ui.SensorQualityScreen
import com.eiscamera.ui.SensorQualityViewModel
import com.eiscamera.ui.theme.EisCameraTheme

/**
 * V0.1-V0.3 entry point: launches the Device Capability Scanner and shows
 * its results, with an optional detour into the V0.3 Sensor Quality Test.
 * There is no camera preview or recording yet — that begins at V0.6 per
 * docs/ROADMAP.md. Navigation is deliberately a single boolean toggle
 * rather than a Navigation-Compose graph — two screens don't justify that
 * dependency yet (spec section 27: avoid unnecessary abstraction layers).
 */
class MainActivity : ComponentActivity() {

    private val scanViewModel: ScanViewModel by viewModels()
    private val sensorQualityViewModel: SensorQualityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EisCameraTheme {
                var showSensorQualityTest by remember { mutableStateOf(false) }
                if (showSensorQualityTest) {
                    SensorQualityScreen(
                        viewModel = sensorQualityViewModel,
                        onBack = { showSensorQualityTest = false },
                    )
                } else {
                    DiagnosticScreen(
                        viewModel = scanViewModel,
                        onRunSensorQualityTest = { showSensorQualityTest = true },
                    )
                }
            }
        }
    }
}
