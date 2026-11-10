package com.roywatson.android_litert_object_recog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.roywatson.android_litert_object_recog.vision.*
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB6F36B), onPrimary = Color(0xFF183500),
                secondaryContainer = Color(0xFF334728), onSecondaryContainer = Color(0xFFD4F5B6),
                surfaceVariant = Color(0xFF252D26),
                background = Color(0xFF101411), surface = Color(0xFF1B211C),
            )) { VisionLab() }
        }
    }
}

@Composable
private fun VisionLab() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var state by remember { mutableStateOf(VisionState()) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf("") }
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val session = remember { VisionSession(context) { state = it } }
    val fileExecutor = remember { Executors.newSingleThreadExecutor() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pendingCsv
        pendingCsv = null
        if (uri != null && csv != null) {
            fileExecutor.execute {
                val message = runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                        ?: error("Could not open destination")
                    "Benchmark CSV saved"
                }.getOrElse { "Export failed: ${it.message}" }
                ContextCompat.getMainExecutor(context).execute { exportMessage = message }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasCameraPermission(context)
            if (event == Lifecycle.Event.ON_STOP) session.cancelCapture()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            session.close()
            fileExecutor.shutdown()
        }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("VISION LAB", style = MaterialTheme.typography.headlineMedium)
                    Text("A1 / Live image classification", color = MaterialTheme.colorScheme.primary)
                }
                Surface(shape = RoundedCornerShape(50), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                    Text("OFFLINE", Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("EfficientNet-Lite0 · 1,000 ImageNet classes", style = MaterialTheme.typography.bodyMedium)
            Text(if (BuildConfig.DEBUG) "DEBUG BUILD" else "RELEASE BUILD",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Card(shape = RoundedCornerShape(24.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                    if (granted) CameraFeed(session) { cameraError = it }
                    else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("See what’s in front of you", style = MaterialTheme.typography.titleLarge)
                        Text("Allow camera access to classify the center square. Frames stay on your phone.")
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Enable camera") }
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")))
                        }) { Text("Open app settings") }
                    }
                    if (granted) Text("CENTER SQUARE • CLASSIFICATION", Modifier.align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = .65f)).padding(10.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
            cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Backend.entries.forEach { backend ->
                    FilterChip(selected = state.requested == backend,
                        onClick = { exportMessage = ""; session.select(backend) }, label = { Text(backend.name) })
                }
            }
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("TOP MATCHES", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    if (state.predictions.isEmpty()) Text("Point the camera at a single object")
                    state.predictions.forEachIndexed { index, prediction ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(prediction.label, Modifier.weight(1f),
                                style = if (index == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium)
                            Text("${number(prediction.confidence * 100.0)}%", Modifier.padding(start = 12.dp))
                        }
                        LinearProgressIndicator(progress = { prediction.confidence.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("INFERENCE", state.timing?.let { "${number(it.inferenceMeanMs)} ms" } ?: "—")
                Metric("FRAME", state.timing?.let { "${number(it.processingMeanMs)} ms" } ?: "—")
                Metric("ANALYSIS", state.timing?.let { "${number(it.fps)} fps" } ?: "—")
            }
            Text("Rolling 60 frames. Inference includes tensor transfer; frame includes preprocessing and ranking.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Measure this device", style = MaterialTheme.typography.titleLarge)
            Text("30 warmup frames, then 300 measured frames. Keep the scene and lighting steady.",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = granted && cameraError == null && state.active != null && !state.capturing,
                    onClick = { exportMessage = ""; session.startCapture() }) { Text("Capture benchmark") }
                OutlinedButton(enabled = state.csv != null, onClick = {
                    pendingCsv = state.csv
                    export.launch("vision-lab-${state.active?.name?.lowercase()}-${System.currentTimeMillis()}.csv")
                }) { Text("Export CSV") }
            }
            if (state.capture.isNotEmpty()) Text(state.capture, color = MaterialTheme.colorScheme.primary)
            if (exportMessage.isNotEmpty()) Text(exportMessage)
            Text("Classification names the whole crop; object boxes arrive in A2. Confidence is a model score, not a guarantee.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CameraFeed(session: VisionSession, onError: (String?) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    DisposableEffect(owner, session) {
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(session.executor, session::analyze)
        future.addListener({
            if (!disposed) {
                try {
                    provider = future.get()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    onError(null)
                } catch (failure: Exception) {
                    onError("Camera unavailable: ${failure.message}. Check camera access and reopen the app.")
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
        }
    }
}

private fun hasCameraPermission(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
private fun number(value: Double) = String.format(Locale.US, "%.1f", value)
