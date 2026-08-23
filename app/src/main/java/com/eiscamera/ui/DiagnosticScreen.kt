package com.eiscamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.eiscamera.camera.CameraInfo
import com.eiscamera.deviceprofile.DeviceProfile
import com.eiscamera.sensors.SensorInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    viewModel: ScanViewModel,
    onRunSensorQualityTest: () -> Unit = {},
    onRunCameraQualityTest: () -> Unit = {},
    onRunSyncTest: () -> Unit = {},
    onRunOrientationDriftTest: () -> Unit = {},
    onRunLivePreview: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EIS Camera — Device Capability Scan") },
                actions = {
                    IconButton(onClick = { viewModel.rescan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan device")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is ScanUiState.Idle, is ScanUiState.Scanning -> ScanningIndicator()
                is ScanUiState.Failed -> ErrorView(s.message, onRetry = { viewModel.rescan() })
                is ScanUiState.Done -> ProfileView(s.profile, s.fromCache, onRunSensorQualityTest, onRunCameraQualityTest, onRunSyncTest, onRunOrientationDriftTest, onRunLivePreview)
            }
        }
    }
}

@Composable
private fun ScanningIndicator() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Scanning sensors, cameras, and processing capabilities…")
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Scan failed", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ProfileView(
    profile: DeviceProfile,
    fromCache: Boolean,
    onRunSensorQualityTest: () -> Unit,
    onRunCameraQualityTest: () -> Unit,
    onRunSyncTest: () -> Unit,
    onRunOrientationDriftTest: () -> Unit,
    onRunLivePreview: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (fromCache) {
            item {
                Text(
                    "Loaded from cached profile — pull Refresh to rescan",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        item {
            SectionCard(title = "Capability Result") {
                Text(
                    profile.capability.level,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Evidence status: ${profile.capability.evidenceStatus}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                profile.capability.reasons.forEach { reason ->
                    Text("• $reason", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        item {
            Button(onClick = onRunSensorQualityTest, modifier = Modifier.fillMaxWidth()) {
                Text("Run Sensor Quality Test (V0.3)")
            }
        }

        item {
            Button(onClick = onRunCameraQualityTest, modifier = Modifier.fillMaxWidth()) {
                Text("Run Camera Quality Test (V0.4)")
            }
        }

        item {
            Button(onClick = onRunSyncTest, modifier = Modifier.fillMaxWidth()) {
                Text("Run Sync Test (V0.7)")
            }
        }

        item {
            Button(onClick = onRunOrientationDriftTest, modifier = Modifier.fillMaxWidth()) {
                Text("Run Orientation Drift Test (V0.8)")
            }
        }

        item {
            Button(onClick = onRunLivePreview, modifier = Modifier.fillMaxWidth()) {
                Text("Live Preview (V1.0 — no stabilization yet)")
            }
        }

        profile.sensorQuality?.let { q ->
            item {
                SectionCard(title = "Last Sensor Quality Test") {
                    KeyValueRow("Measured rate", q.measuredRateHz?.let { "%.0f Hz".format(it) } ?: "unknown")
                    KeyValueRow("Timestamp jitter", q.timestampJitterMs?.let { "%.3f ms".format(it) } ?: "unknown")
                    KeyValueRow("Stationary noise (worst axis)", "%.5f rad/s".format(q.stationaryNoiseStdDevRadS))
                    KeyValueRow("Bias magnitude", "%.5f rad/s".format(q.stationaryBiasRadS))
                    if (q.dynamicTestAvailable && q.dynamicLagMs != null && q.dynamicCorrelation != null) {
                        KeyValueRow("Dynamic lag vs. rotation vector", "%.0f ms".format(q.dynamicLagMs))
                        KeyValueRow("Dynamic correlation", "%.3f".format(q.dynamicCorrelation))
                    }
                }
            }
        }

        if (profile.cameraQuality.isNotEmpty()) {
            item {
                SectionCard(title = "Last Camera Quality Tests") {
                    profile.cameraQuality.forEach { cq ->
                        Text("Camera ${cq.cameraId}", fontWeight = FontWeight.Bold)
                        KeyValueRow("Measured FPS", cq.measuredFps?.let { "%.1f".format(it) } ?: "unknown")
                        KeyValueRow("Jitter", cq.frameIntervalJitterMs?.let { "%.3f ms".format(it) } ?: "unknown")
                        KeyValueRow("Likely dropped frames", cq.likelyDroppedFrames.toString())
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        profile.syncResult?.let { sr ->
            item {
                SectionCard(title = "Last Sync Test") {
                    KeyValueRow("Camera", sr.cameraId)
                    KeyValueRow("Timestamp source", sr.cameraTimestampSource)
                    KeyValueRow("Estimated offset", sr.estimatedOffsetMs?.let { "%.1f ms".format(it) } ?: "unknown")
                    KeyValueRow("Correlation", sr.correlation?.let { "%.3f".format(it) } ?: "unknown")
                }
            }
        }

        profile.orientationDrift?.let { od ->
            item {
                SectionCard(title = "Last Orientation Drift Test") {
                    KeyValueRow("Duration", "%.1f s".format(od.durationS))
                    KeyValueRow("Drift (uncorrected)", "%.2f°".format(od.driftUncorrectedDegrees))
                    if (od.driftCorrectedDegrees != null) {
                        KeyValueRow("Drift (bias-corrected)", "%.2f°".format(od.driftCorrectedDegrees))
                    }
                }
            }
        }

        item {
            SectionCard(title = "Device") {
                KeyValueRow("Manufacturer", profile.identity.manufacturer)
                KeyValueRow("Model", profile.identity.model)
                KeyValueRow("Android", "${profile.identity.androidRelease} (API ${profile.identity.apiLevel})")
            }
        }

        item {
            SectionCard(title = "Sensors (${profile.sensors.size} found)") {
                if (profile.missingCriticalSensors.isNotEmpty()) {
                    Text(
                        "Missing critical: ${profile.missingCriticalSensors.joinToString()}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        items(profile.sensors) { sensor -> SensorCard(sensor) }

        item { SectionCard(title = "Cameras (${profile.cameras.size} found)") {} }
        items(profile.cameras) { camera -> CameraCard(camera) }

        item {
            SectionCard(title = "Processing / GPU") {
                KeyValueRow("CPU cores", profile.processing.cpuCoreCount.toString())
                KeyValueRow("ABIs", profile.processing.supportedAbis.joinToString())
                KeyValueRow("Low-RAM device", profile.processing.lowRamDevice.toString())
                KeyValueRow("GPU renderer", profile.processing.glRenderer ?: "unavailable")
                KeyValueRow("GPU vendor", profile.processing.glVendor ?: "unavailable")
                KeyValueRow("GL version", profile.processing.glVersion ?: "unavailable")
            }
        }

        item {
            SectionCard(title = "Hardware Video Encoders (${profile.processing.hardwareVideoEncoders.size})") {
                profile.processing.hardwareVideoEncoders.forEach { codec ->
                    Text(
                        "${codec.name} — ${codec.mimeType} " +
                            "(${if (codec.isHardwareAccelerated) "HW" else "SW/unknown"}, " +
                            "max ${codec.maxSupportedWidth}x${codec.maxSupportedHeight})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SensorCard(sensor: SensorInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(sensor.typeName, fontWeight = FontWeight.Bold)
            KeyValueRow("Name", sensor.name)
            KeyValueRow("Vendor", sensor.vendor)
            KeyValueRow("Reporting mode", sensor.reportingMode)
            KeyValueRow(
                "Declared max rate",
                sensor.declaredMaxRateHz?.let { "%.0f Hz".format(it) } ?: "not declared",
            )
            KeyValueRow("Max range", "${sensor.maximumRange}")
            KeyValueRow("Resolution", "${sensor.resolution}")
        }
    }
}

@Composable
private fun CameraCard(camera: CameraInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Camera ${camera.cameraId} — ${camera.lensFacing}", fontWeight = FontWeight.Bold)
            KeyValueRow("Hardware level", camera.hardwareLevel)
            KeyValueRow("Resolution", "${camera.pixelArrayWidth}x${camera.pixelArrayHeight}")
            KeyValueRow("Sensor orientation", "${camera.sensorOrientationDegrees}°")
            KeyValueRow("Focal lengths (mm)", camera.focalLengthsMm.joinToString())
            KeyValueRow("Apertures", camera.apertures.joinToString())
            KeyValueRow("OIS available", camera.opticalStabilizationAvailable.toString())
            KeyValueRow("Digital EIS (Camera2)", camera.digitalStabilizationAvailable.toString())
            KeyValueRow("Max declared FPS", camera.maxDeclaredFps?.toString() ?: "unknown")
            KeyValueRow("Logical multi-camera", camera.isLogicalMultiCamera.toString())
            if (camera.physicalCameraIds.isNotEmpty()) {
                KeyValueRow("Physical camera IDs", camera.physicalCameraIds.joinToString())
            }
        }
    }
}

@Composable
internal fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$key: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
