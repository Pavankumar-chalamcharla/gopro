package com.eiscamera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eiscamera.ui.CameraQualityScreen
import com.eiscamera.ui.CameraQualityViewModel
import com.eiscamera.ui.DiagnosticScreen
import com.eiscamera.ui.OrientationDriftScreen
import com.eiscamera.ui.OrientationDriftViewModel
import com.eiscamera.ui.ScanViewModel
import com.eiscamera.ui.SensorQualityScreen
import com.eiscamera.ui.SensorQualityViewModel
import com.eiscamera.ui.SyncTestScreen
import com.eiscamera.ui.SyncTestViewModel
import com.eiscamera.ui.theme.EisCameraTheme

/**
 * V0.1-V0.8 entry point: launches the Device Capability Scanner and offers
 * four optional detours — Sensor Quality (V0.3), Camera Quality (V0.4),
 * Sync Test (V0.7), and Orientation Drift (V0.8). There is no live preview
 * or recording pipeline yet — that begins at V0.9+ per docs/ROADMAP.md.
 * Navigation is a plain enum toggle rather than a Navigation-Compose graph
 * — five screens still don't justify that dependency (spec section 27:
 * avoid unnecessary abstraction layers).
 */
class MainActivity : ComponentActivity() {

    private val scanViewModel: ScanViewModel by viewModels()
    private val sensorQualityViewModel: SensorQualityViewModel by viewModels()
    private val cameraQualityViewModel: CameraQualityViewModel by viewModels()
    private val syncTestViewModel: SyncTestViewModel by viewModels()
    private val orientationDriftViewModel: OrientationDriftViewModel by viewModels()

    private enum class Screen { DIAGNOSTIC, SENSOR_QUALITY, CAMERA_QUALITY, SYNC_TEST, ORIENTATION_DRIFT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EisCameraTheme {
                var screen by remember { mutableStateOf(Screen.DIAGNOSTIC) }
                when (screen) {
                    Screen.DIAGNOSTIC -> DiagnosticScreen(
                        viewModel = scanViewModel,
                        onRunSensorQualityTest = { screen = Screen.SENSOR_QUALITY },
                        onRunCameraQualityTest = { screen = Screen.CAMERA_QUALITY },
                        onRunSyncTest = { screen = Screen.SYNC_TEST },
                        onRunOrientationDriftTest = { screen = Screen.ORIENTATION_DRIFT },
                    )
                    Screen.SENSOR_QUALITY -> SensorQualityScreen(
                        viewModel = sensorQualityViewModel,
                        onBack = { screen = Screen.DIAGNOSTIC },
                    )
                    Screen.CAMERA_QUALITY -> CameraQualityScreen(
                        viewModel = cameraQualityViewModel,
                        onBack = { screen = Screen.DIAGNOSTIC },
                    )
                    Screen.SYNC_TEST -> SyncTestScreen(
                        viewModel = syncTestViewModel,
                        onBack = { screen = Screen.DIAGNOSTIC },
                    )
                    Screen.ORIENTATION_DRIFT -> OrientationDriftScreen(
                        viewModel = orientationDriftViewModel,
                        onBack = { screen = Screen.DIAGNOSTIC },
                    )
                }
            }
        }
    }
}
