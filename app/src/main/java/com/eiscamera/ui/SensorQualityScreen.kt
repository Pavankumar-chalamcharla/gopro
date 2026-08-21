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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorQualityScreen(viewModel: SensorQualityViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensor Quality Test (V0.3)") },
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
                is SensorQualityUiState.ReadyForStationary -> InstructionCard(
                    title = "Phase 1 — Stationary Test",
                    body = "Place your phone on a flat, stable surface (like a table) and don't touch it. " +
                        "We'll measure the gyroscope's real sampling rate, timestamp jitter, and noise floor " +
                        "while it's perfectly still.",
                    buttonText = "Start (5 seconds)",
                    onStart = { viewModel.startStationaryPhase() },
                )
                is SensorQualityUiState.CollectingStationary -> CountdownCard(
                    message = "Keep the phone still…",
                    secondsRemaining = s.secondsRemaining,
                )
                is SensorQualityUiState.ReadyForDynamic -> InstructionCard(
                    title = "Phase 2 — Motion Test",
                    body = "Now pick up your phone. When you tap Start, give it a few sharp, deliberate " +
                        "flicks or twists for the next few seconds — this checks how well the gyroscope's " +
                        "signal matches the phone's own fused orientation sensor during real motion.",
                    buttonText = "Start (5 seconds)",
                    onStart = { viewModel.startDynamicPhase() },
                )
                is SensorQualityUiState.CollectingDynamic -> CountdownCard(
                    message = "Flick / rotate the phone now!",
                    secondsRemaining = s.secondsRemaining,
                )
                is SensorQualityUiState.Analyzing -> LoadingCard("Analyzing…")
                is SensorQualityUiState.Failed -> FailedCard(s.message) { viewModel.reset() }
                is SensorQualityUiState.Done -> ResultsView(s, onSave = { viewModel.saveToProfile() })
            }
        }
    }
}

@Composable
private fun InstructionCard(title: String, body: String, buttonText: String, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text(buttonText) }
    }
}

@Composable
internal fun CountdownCard(message: String, secondsRemaining: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$secondsRemaining", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun LoadingCard(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message)
    }
}

@Composable
internal fun FailedCard(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Test failed", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Start Over") }
    }
}

@Composable
private fun ResultsView(state: SensorQualityUiState.Done, onSave: () -> Unit) {
    val stationary = state.stationary
    val dynamic = state.dynamic

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Stationary Phase (measured)") {
                KeyValueRow("Samples collected", stationary.sampleCount.toString())
                KeyValueRow(
                    "Measured rate",
                    stationary.measuredRateHz?.let { "%.0f Hz".format(it) } ?: "unknown"
                )
                KeyValueRow(
                    "Timestamp jitter",
                    stationary.timestampJitterMs?.let { "%.3f ms".format(it) } ?: "unknown"
                )
                KeyValueRow("Timestamps monotonic", stationary.timestampMonotonic.toString())
                KeyValueRow("Worst-axis noise stddev", "%.5f rad/s".format(stationary.worstAxisStdDev))
                KeyValueRow("Bias magnitude", "%.5f rad/s".format(stationary.biasMagnitude))
            }
        }

        item {
            SectionCard(title = "Dynamic Response Phase (measured)") {
                if (dynamic != null) {
                    KeyValueRow("Lag vs. rotation vector", "%.0f ms".format(dynamic.bestLagMs))
                    KeyValueRow("Correlation", "%.3f".format(dynamic.bestCorrelation))
                    KeyValueRow("Gyro peak", "%.2f rad/s".format(dynamic.gyroPeakMagnitudeRadS))
                    KeyValueRow("Rotation-vector-derived peak", "%.2f rad/s".format(dynamic.rotationVectorPeakMagnitudeRadS))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A high correlation here is CONSISTENT WITH — but does not by itself PROVE — " +
                            "the gyroscope signal being derived from the same fusion pipeline as the " +
                            "rotation vector, rather than an independent physical measurement. Compare " +
                            "against the gyroscope's declared name/vendor on the main scan screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "Not enough overlapping samples were collected to compute this — try Phase 2 again with more motion.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
