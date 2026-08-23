package com.eiscamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.eiscamera.camera.CameraInfo
import com.eiscamera.motion.LiveOrientationState
import com.eiscamera.rendering.CameraGlRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewScreen(viewModel: CameraPreviewViewModel, onBack: () -> Unit) {
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
    var selectedCameraId by remember { mutableStateOf<String?>(null) }

    // Stop the camera whenever this screen leaves composition (navigating back),
    // so the camera device is released rather than left open in the background —
    // the explicit resource-ownership boundary spec section 28 calls for. Known
    // gap: this does not yet hook the Activity's onPause/onStop, so backgrounding
    // the whole app (not just navigating within it) won't release the camera —
    // a real limitation to close in a later V1.0 refinement, not silently ignored.
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Preview (V1.0)") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stop()
                        onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !hasPermission -> PreviewPermissionCard(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
                selectedCameraId == null -> PreviewCameraPickerCard(
                    cameras = viewModel.availableCameras,
                    onSelect = { id -> selectedCameraId = id },
                )
                else -> LivePreviewContent(
                    viewModel = viewModel,
                    cameraId = selectedCameraId!!,
                    state = state,
                    onSwitchCamera = {
                        viewModel.stop()
                        selectedCameraId = null
                    },
                )
            }
        }
    }
}

@Composable
private fun PreviewPermissionCard(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Camera Permission Needed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "This shows a live, continuously-running camera preview. Nothing is recorded or saved.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant Camera Permission") }
    }
}

@Composable
private fun PreviewCameraPickerCard(cameras: List<CameraInfo>, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Choose a camera to preview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Unlike the earlier tests, this preview keeps running continuously until you leave " +
                "this screen — no stabilization applied yet, this is just the raw live feed.",
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
private fun LivePreviewContent(
    viewModel: CameraPreviewViewModel,
    cameraId: String,
    state: CameraPreviewUiState,
    onSwitchCamera: () -> Unit,
) {
    val orientationState by viewModel.orientationState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        val renderer = CameraGlRenderer(
                            onSurfaceTextureReady = { surfaceTexture ->
                                viewModel.start(cameraId, Surface(surfaceTexture))
                            },
                        )
                        renderer.attachTo(this)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    }
                },
                onRelease = { viewModel.stop() },
            )
            when (state) {
                is CameraPreviewUiState.Starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is CameraPreviewUiState.Failed -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Preview failed: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> {}
            }
            if (state is CameraPreviewUiState.Running) {
                OrientationDebugOverlay(
                    orientationState = orientationState,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                when (state) {
                    is CameraPreviewUiState.Running -> "Live - camera $cameraId - no stabilization yet"
                    is CameraPreviewUiState.Starting -> "Starting..."
                    is CameraPreviewUiState.Failed -> "Failed"
                    is CameraPreviewUiState.Idle -> "Idle"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onSwitchCamera) { Text("Switch Camera") }
        }
    }
}

/**
 * V1.0b: shows the orientation pipeline running alongside the preview is
 * actually keeping up in real time — gyro rate should sit near the ~199Hz
 * V0.3 measured on this device, and sample count should climb steadily
 * with no long pauses. compensationAngle is what a future stabilization
 * transform would need to cancel; it does nothing to the image yet.
 */
@Composable
private fun OrientationDebugOverlay(orientationState: LiveOrientationState, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                "V1.0b orientation pipeline",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Gyro: " + (orientationState.gyroRateHz?.let { "%.0f Hz".format(it) } ?: "..."),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Compensation: %.2f deg".format(Math.toDegrees(orientationState.compensationAngleRad)),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Samples: ${orientationState.sampleCount}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                if (orientationState.biasCorrectionApplied) "Bias-corrected (V0.3)" else "No bias correction (run V0.3 first)",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
