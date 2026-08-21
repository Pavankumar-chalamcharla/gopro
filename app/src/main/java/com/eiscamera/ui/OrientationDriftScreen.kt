package com.eiscamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrientationDriftScreen(viewModel: OrientationDriftViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Orientation Drift Test (V0.8)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is OrientationDriftUiState.Ready -> DriftInstructionCard(onStart = { viewModel.start() })
                is OrientationDriftUiState.Collecting -> CountdownCard(
                    message = "Move the phone around naturally - rotate it in different directions...",
                    secondsRemaining = s.secondsRemaining,
                )
                is OrientationDriftUiState.Failed -> FailedCard(s.message) { viewModel.reset() }
                is OrientationDriftUiState.Done -> DriftResultsView(s, onSave = { viewModel.saveToProfile() })
            }
        }
    }
}

@Composable
private fun DriftInstructionCard(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Orientation Drift Test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "This integrates the gyroscope's raw angular velocity into an orientation estimate over " +
                "10 seconds, then compares it against the phone's own fused orientation sensor to " +
                "measure how much it has drifted. Move the phone around naturally during the test - " +
                "rotate it in different directions, don't just hold it still.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text("Start (10 seconds)") }
    }
}

@Composable
private fun DriftResultsView(state: OrientationDriftUiState.Done, onSave: () -> Unit) {
    val s = state.snapshot
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Orientation Drift (measured)") {
                KeyValueRow("Test duration", "%.1f s".format(s.durationS))
                KeyValueRow("Gyro samples", s.gyroSampleCount.toString())
                KeyValueRow("Reference samples", s.referenceSampleCount.toString())
                KeyValueRow("Drift (uncorrected)", "%.2f°".format(s.driftUncorrectedDegrees))
                if (s.driftCorrectedDegrees != null) {
                    KeyValueRow("Drift (bias-corrected)", "%.2f°".format(s.driftCorrectedDegrees))
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No saved V0.3 bias data was found, so bias correction wasn't applied. Run " +
                            "the Sensor Quality Test first for a corrected comparison.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "This measures how far a PURE gyro-integrated orientation drifts from the " +
                        "phone's own fused reference over this specific test window - a real number " +
                        "for real motion on this device right now, not a general spec.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.refreshedCapability?.let { cap ->
            item {
                SectionCard(title = "Updated Capability Reasoning") {
                    Text(cap.level.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    cap.reasons.forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        item {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.saved) "Saved ✓" else "Save to Device Profile")
            }
        }
    }
}
