package com.eiscamera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.eiscamera.ui.DiagnosticScreen
import com.eiscamera.ui.ScanViewModel
import com.eiscamera.ui.theme.EisCameraTheme

/**
 * V0.1/V0.2 entry point: launches the Device Capability Scanner and shows
 * its results. There is no camera preview or recording yet — that begins
 * at V0.6 per docs/ROADMAP.md.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EisCameraTheme {
                DiagnosticScreen(viewModel = viewModel)
            }
        }
    }
}
