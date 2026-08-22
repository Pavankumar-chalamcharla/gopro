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
fun SyncTestScreen(viewModel: SyncTestViewModel, onBack: () -> Unit) {
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
                title = { Text("Sync Test (V0.7)") },
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
                SyncPermissionCard(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
            } else {
                when (val s = state) {
                    is SyncTestUiState.SelectingCamera -> SyncCameraPickerCard(
                        cameras = viewModel.availableCameras,
                        onSelect = { id -> viewModel.testCamera(id) },
                    )
                    is SyncTestUiState.Collecting -> SyncCollectingCard(s.secondsRemaining)
                    is SyncTestUiState.Failed -> FailedCard(s.message) { viewModel.reset() }
                    is SyncTestUiState.Done -> SyncResultsView(
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
private fun SyncPermissionCard(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Camera Permission Needed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "This test opens the camera to watch for motion, alongside the gyroscope, to estimate " +
                "how their clocks line up. No photo or video is recorded or saved.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant Camera Permission") }
    }
}

@Composable
private fun SyncCameraPickerCard(cameras: List<CameraInfo>, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Choose a camera to sync-test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "During the test, point the camera at something with visible detail (not a blank " +
                "wall) and give the phone a few slow, deliberate rotations - the camera needs to " +
                "actually see motion for this to work.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        if (cameras.isEmpty()) {
            Text(
                "No cameras found in the device profile yet - run the main scan first (back button).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cameras) { cam ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(cam.cameraId) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Camera ${cam.cameraId} - ${cam.lensFacing}", fontWeight = FontWeight.Bold)
                        Text(
                            "Declared timestamp source: ${cam.timestampSource}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncCollectingCard(secondsRemaining: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$secondsRemaining", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Slowly rotate the phone, keeping the camera pointed at something with detail...",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SyncResultsView(
    state: SyncTestUiState.Done,
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
            SectionCard(title = "Camera ${s.cameraId} - Sync Estimate") {
                KeyValueRow("Declared timestamp source", s.cameraTimestampSource)
                KeyValueRow("Gyro samples", s.gyroSampleCount.toString())
                KeyValueRow("Camera frames", s.cameraFrameCount.toString())
                KeyValueRow(
                    "Estimated offset",
                    s.estimatedOffsetMs?.let { "%.1f ms".format(it) } ?: "could not be estimated",
                )
                KeyValueRow("Correlation", s.correlation?.let { "%.3f".format(it) } ?: "unknown")
                if (s.cameraTimestampSource != "REALTIME") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This camera does not declare a REALTIME timestamp source, so this offset " +
                            "is a best-effort empirical estimate, not a platform guarantee.",
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
        item {
            Button(onClick = onTestAnother, modifier = Modifier.fillMaxWidth()) {
                Text("Test Another Camera")
            }
        }
    }
}
