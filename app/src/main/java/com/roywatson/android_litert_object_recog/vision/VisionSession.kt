package com.roywatson.android_litert_object_recog.vision

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import android.util.Log
import com.roywatson.android_litert_object_recog.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class Backend { CPU, GPU, NPU }
data class Prediction(val label: String, val confidence: Float)
data class VisionState(
    val requested: Backend = Backend.CPU,
    val active: Backend? = null,
    val executionEvidence: String = "NONE",
    val initializationMs: Double? = null,
    val fallbackReason: String = "",
    val message: String = "Preparing offline model…",
    val predictions: List<Prediction> = emptyList(),
    val timing: BenchmarkResult? = null,
    val capture: String = "",
    val capturing: Boolean = false,
    val csv: String? = null,
)

/** All native resources are created, invoked and closed on one thread (required for GPU). */
class VisionSession(context: Context, private val onState: (VisionState) -> Unit) {
    private val app = context.applicationContext
    val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var state = VisionState()
    private var model: InferenceEngine? = null
    private var firstInferenceMs: Double? = null
    private var preprocessor: FramePreprocessor? = null
    private var labels: List<String> = emptyList()
    private val recent = ArrayDeque<FrameTiming>()
    private val samples = mutableListOf<FrameTiming>()
    private var warmup = 0
    private var lastUiNs = 0L

    init { select(Backend.CPU) }

    fun select(backend: Backend) = executor.execute {
        if (closed.get()) return@execute
        releaseModel()
        recent.clear(); samples.clear()
        state = VisionState(requested = backend, message = "Preparing $backend…")
        publish()
        try {
            if (labels.isEmpty()) labels = app.assets.open("labels.txt").bufferedReader().use { it.readLines() }
            if (preprocessor == null) preprocessor = FramePreprocessor()
            openModel(backend)
            state = state.copy(active = backend, executionEvidence = model!!.executionEvidence,
                message = if (backend == Backend.NPU) "NPU • full model offload confirmed by LiteRT"
                    else "$backend • all processing stays on this phone")
        } catch (failure: Exception) {
            fallback(failure.message ?: failure.javaClass.simpleName)
        } catch (failure: LinkageError) {
            fallback(failure.message ?: "Runtime unavailable")
        }
        publish()
    }

    private fun openModel(backend: Backend) {
        val start = SystemClock.elapsedRealtimeNanos()
        firstInferenceMs = null
        model = if (backend == Backend.NPU) NativeNpuEngine(app) else KotlinEngine(app, backend)
        state = state.copy(initializationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
    }

    private fun fallback(reason: String) {
        Log.w("VisionNpu", "requested=${state.requested} fallback=CPU reason=$reason")
        releaseModel()
        recent.clear(); samples.clear()
        state = state.copy(active = null, executionEvidence = "NONE", initializationMs = null, predictions = emptyList(), timing = null,
            capturing = false, capture = "", csv = null, fallbackReason = reason)
        try {
            openModel(Backend.CPU)
            state = state.copy(active = Backend.CPU, executionEvidence = model!!.executionEvidence, message = "${state.requested} unavailable: $reason\nUsing CPU.")
        } catch (failure: Exception) {
            state = state.copy(message = "Model unavailable: ${failure.message}. Tap CPU to retry.")
        } catch (failure: LinkageError) {
            state = state.copy(message = "LiteRT unavailable on this device. Tap CPU to retry.")
        }
    }

    fun startCapture() = executor.execute {
        if (closed.get() || model == null) return@execute
        samples.clear(); recent.clear(); warmup = 30
        state = state.copy(capturing = true, capture = "Warmup: 30 frames", csv = null)
        publish()
    }

    fun cancelCapture() = executor.execute {
        if (closed.get()) return@execute
        recent.clear()
        if (state.capturing) {
            samples.clear()
            state = state.copy(capturing = false, capture = "Capture cancelled: app left foreground")
            publish()
        }
    }

    /** Called directly by CameraX on [executor]; always releases the camera frame. */
    fun analyze(image: ImageProxy) {
        try {
            if (closed.get() || model == null) return
            val start = SystemClock.elapsedRealtimeNanos()
            val tensor = preprocessor!!.prepare(image)
            val inferenceStart = SystemClock.elapsedRealtimeNanos()
            val scores = model!!.infer(tensor)
            val inferenceEnd = SystemClock.elapsedRealtimeNanos()
            if (firstInferenceMs == null) firstInferenceMs = (inferenceEnd - inferenceStart) / 1e6
            require(scores.all { it.isFinite() }) { "Non-finite model output" }
            require(scores.size == labels.size) { "Model/labels mismatch: ${scores.size}/${labels.size}" }
            val predictions = scores.indices.sortedByDescending { scores[it] }.take(3).map {
                Prediction(labels[it], scores[it])
            }
            val end = SystemClock.elapsedRealtimeNanos()
            val timing = FrameTiming((inferenceEnd - inferenceStart) / 1e6, (end - start) / 1e6, end)
            recent.addLast(timing)
            if (recent.size > 60) recent.removeFirst()
            state = state.copy(predictions = predictions, timing = BenchmarkMath.summarize(recent.toList()))
            if (state.capturing) {
                if (warmup > 0) {
                    warmup--
                    state = state.copy(capture = "Warmup: $warmup frames remaining")
                } else {
                    samples.add(timing)
                    state = state.copy(capture = "Measuring ${samples.size} / 300 frames")
                    if (samples.size == 300) finishCapture()
                }
            }
            if (end - lastUiNs >= 100_000_000) { lastUiNs = end; publish() }
        } catch (failure: Exception) {
            handleFrameFailure("Frame analysis failed: ${failure.message}")
            publish()
        } catch (failure: LinkageError) {
            handleFrameFailure("Accelerator execution failed: ${failure.message}")
            publish()
        } finally { image.close() }
    }

    private fun handleFrameFailure(reason: String) {
        if (state.active == Backend.CPU) {
            releaseModel()
            state = state.copy(active = null, executionEvidence = "NONE", capturing = false, predictions = emptyList(), timing = null,
                csv = null, capture = "", message = "$reason. Tap CPU to retry.")
        } else fallback(reason)
    }

    private fun finishCapture() {
        val result = BenchmarkMath.summarize(samples)
        val pssKb = Debug.getPss()
        val header = csvRow("manufacturer", "device", "android_api", "build", "model", "model_bytes",
            "runtime", "requested", "active", "warmup_frames", "samples", "inference_mean_ms",
            "inference_p50_ms", "inference_p95_ms", "processing_mean_ms", "analysis_fps", "process_pss_kb",
            "sample", "sample_inference_ms", "sample_processing_ms", "completed_elapsed_ns",
            "execution_evidence", "initialization_ms", "first_inference_ms", "qairt_version",
            "soc_model", "soc_manufacturer", "app_version", "build_type", "npu_support_bundled", "fallback_reason")
        val modelBytes = app.assets.openFd(MODEL).use { it.length }
        val rows = samples.mapIndexed { index, frame ->
            csvRow(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, Build.FINGERPRINT,
                MODEL, modelBytes, "LiteRT ${BuildConfig.LITERT_VERSION}", state.requested, state.active, 30, result.samples,
                result.inferenceMeanMs, result.inferenceP50Ms, result.inferenceP95Ms,
                result.processingMeanMs, result.fps, pssKb, index + 1,
                frame.inferenceMs, frame.processingMs, frame.completedNs,
                state.executionEvidence, state.initializationMs, firstInferenceMs, BuildConfig.QAIRT_VERSION,
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown",
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "unknown",
                BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE, BuildConfig.QUALCOMM_NPU_ENABLED, state.fallbackReason)
        }
        state = state.copy(capturing = false, capture = "300 frames captured • ready to export",
            csv = (listOf(header) + rows).joinToString("\n", postfix = "\n"))
        publish()
    }

    private fun publish() {
        val snapshot = state
        main.post { if (!closed.get()) onState(snapshot) }
    }
    private fun releaseModel() {
        model?.close(); model = null
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute { releaseModel(); preprocessor?.close() }
        executor.shutdown()
    }
    companion object { const val MODEL = "efficientnet-lite0-fp32.tflite" }
}
