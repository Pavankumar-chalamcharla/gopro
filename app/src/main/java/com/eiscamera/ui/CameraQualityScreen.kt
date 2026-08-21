package com.eiscamera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eiscamera.camera.CameraInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraQualityScreen(viewModel: CameraQualityViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera Quality Test (V0.4)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasPermission) {
                PermissionCard(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
            } else {
                when (val s = state) {
                    is CameraQualityUiState.SelectingCamera -> CameraPickerCard(
                        cameras = viewModel.availableCameras,
                        onSelect = { id -> viewModel.testCamera(id) },
                    )
                    is CameraQualityUiState.Collecting -> CountdownCard(
                        message = "Recording from camera ${s.cameraId}…",
                        secondsRemaining = s.secondsRemaining,
                    )
                    is CameraQualityUiState.Failed -> FailedCard(s.message) { viewModel.reset() }
                    is CameraQualityUiState.Done -> CameraResultsView(
                        state = s,
                        onSave = { viewModel.saveToProfile() },
                        onTestAnother = { viewModel.reset() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Camera Permission Needed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "This test briefly opens the camera to measure its real frame timing — no photo, " +
                "video, or preview is recorded, saved, or shown.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant Camera Permission") }
    }
}

@Composable
private fun CameraPickerCard(cameras: List<CameraInfo>, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Choose a camera to test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Each camera is tested independently — a phone's rear and front cameras (or multiple " +
                "rear lenses) can behave very differently.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        if (cameras.isEmpty()) {
            Text(
                "No cameras found in the device profile yet — run the main scan first (back button).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cameras) { cam ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(cam.cameraId) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Camera ${cam.cameraId} — ${cam.lensFacing}", fontWeight = FontWeight.Bold)
                        Text(
                            "${cam.pixelArrayWidth}x${cam.pixelArrayHeight}, " +
                                "max declared FPS=${cam.maxDeclaredFps ?: "unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraResultsView(
    state: CameraQualityUiState.Done,
    onSave: () -> Unit,
    onTestAnother: () -> Unit,
) {
    val s = state.snapshot
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Camera ${s.cameraId} — Measured Stream Quality") {
                KeyValueRow("Frames captured", s.frameCount.toString())
                KeyValueRow("Measured FPS", s.measuredFps?.let { "%.1f".format(it) } ?: "unknown")
                KeyValueRow(
                    "Frame interval jitter",
                    s.frameIntervalJitterMs?.let { "%.3f ms".format(it) } ?: "unknown",
                )
                KeyValueRow(
                    "Min / max interval",
                    "${s.minIntervalMs?.let { "%.1f".format(it) } ?: "?"} / " +
                        "${s.maxIntervalMs?.let { "%.1f".format(it) } ?: "?"} ms",
                )
                KeyValueRow("Likely dropped frames", s.likelyDroppedFrames.toString())
                KeyValueRow(
                    "Mean exposure time",
                    s.meanExposureTimeNs?.let { "%.0f µs".format(it / 1000.0) } ?: "unavailable",
                )
                KeyValueRow(
                    "Mean frame duration",
                    s.meanFrameDurationNs?.let { "%.2f ms".format(it / 1_000_000.0) } ?: "unavailable",
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
        item {
            Button(onClick = onTestAnother, modifier = Modifier.fillMaxWidth()) {
                Text("Test Another Camera")
            }
        }
    }
}
