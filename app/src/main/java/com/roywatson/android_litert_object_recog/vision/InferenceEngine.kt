package com.roywatson.android_litert_object_recog.vision

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.roywatson.android_litert_object_recog.BuildConfig
import java.io.File
import java.security.MessageDigest

/** A configuration is never called active until its engine is successfully constructed. */
interface InferenceEngine : AutoCloseable {
    val executionEvidence: String
    fun infer(input: FloatArray): FloatArray
}

class KotlinEngine(context: Context, backend: Backend) : InferenceEngine {
    private var model: CompiledModel? = null
    private var inputs: List<TensorBuffer> = emptyList()
    private var outputs: List<TensorBuffer> = emptyList()
    override val executionEvidence = if (backend == Backend.CPU) "CPU_ONLY_REQUESTED" else "GPU_ONLY_REQUESTED"
    init {
        require(backend != Backend.NPU)
        try {
            model = CompiledModel.create(context.assets, VisionSession.MODEL,
                CompiledModel.Options(if (backend == Backend.GPU) Accelerator.GPU else Accelerator.CPU),
                SharedEnvironment.get(context))
            inputs = model!!.createInputBuffers()
            outputs = model!!.createOutputBuffers()
            require(inputs.size == 1 && outputs.size == 1) { "Unexpected tensor count" }
        } catch (failure: Throwable) { close(); throw failure }
    }
    override fun infer(input: FloatArray): FloatArray {
        inputs.single().writeFloat(input)
        model!!.run(inputs, outputs)
        return outputs.single().readFloat()
    }
    override fun close() {
        outputs.forEach { it.close() }; outputs = emptyList()
        inputs.forEach { it.close() }; inputs = emptyList()
        model?.close(); model = null
    }
    private object SharedEnvironment {
        private var environment: Environment? = null
        // Process lifetime avoids accelerator library reloads on activity/backend changes.
        @Synchronized fun get(context: Context): Environment = environment
            ?: Environment.create(context.applicationContext).also { environment = it }
    }
}

object QualcommNpu {
    fun preflight(context: Context): String? {
        if (!BuildConfig.QUALCOMM_NPU_ENABLED) return "Qualcomm support is not included in this build"
        if (Build.VERSION.SDK_INT < 31) return "Qualcomm NPU requires Android 12 or later"
        if (!Build.SOC_MANUFACTURER.equals("QTI", true) && !Build.SOC_MANUFACTURER.equals("Qualcomm", true))
            return "This build supports Qualcomm chips only"
        val version = when (Build.SOC_MODEL.uppercase()) {
            "SM8450" -> 69
            "SM8750" -> 79
            else -> return "This build has been configured for SM8450 and SM8750 only"
        }
        val required = listOf("libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so",
            "libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so",
            "libQnnHtpV${version}Stub.so", "libQnnHtpV${version}Skel.so")
        val missing = required.filter { !File(context.applicationInfo.nativeLibraryDir, it).isFile }
        return if (missing.isEmpty()) null else "Required Qualcomm libraries are missing: ${missing.joinToString()}"
    }
}

class NativeNpuEngine(context: Context) : InferenceEngine {
    private var handle = 0L
    // Native create requires NPU-only selection AND the public runtime's full non-CPU offload check.
    // This describes model graph placement, not preprocessing, transfers, or measured accuracy.
    override val executionEvidence = "NPU_ONLY_NON_CPU_FULLY_ACCELERATED"
    init {
        QualcommNpu.preflight(context)?.let { error(it) }
        val cache = File(context.cacheDir, "qairt").apply { mkdirs() }
        val model = modelFile(context)
        handle = NativeNpuBridge.create(model.absolutePath, context.applicationInfo.nativeLibraryDir, cache.absolutePath)
        check(handle != 0L) { "Native NPU model creation failed" }
    }
    override fun infer(input: FloatArray): FloatArray {
        check(handle != 0L) { "NPU engine is closed" }
        return NativeNpuBridge.run(handle, input)
    }
    override fun close() {
        if (handle != 0L) { NativeNpuBridge.destroy(handle); handle = 0 }
    }
    companion object {
        private const val MODEL_SHA = "6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0"
        private var verifiedPath: File? = null
        @Synchronized private fun modelFile(context: Context): File {
            verifiedPath?.takeIf { it.isFile }?.let { return it }
            val destination = File(context.filesDir, VisionSession.MODEL)
            val temporary = File(context.filesDir, "${VisionSession.MODEL}.tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.assets.open(VisionSession.MODEL).use { source ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA) { "Model checksum mismatch" }
                check(temporary.renameTo(destination)) { "Could not install private model file" }
                return destination.also { verifiedPath = it }
            } finally { temporary.delete() }
        }
    }
}

@Keep
internal object NativeNpuBridge {
    init { System.loadLibrary("vision_npu") }
    external fun create(modelPath: String, libraryDir: String, cacheDir: String): Long
    external fun run(handle: Long, input: FloatArray): FloatArray
    external fun destroy(handle: Long)
}
