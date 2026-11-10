package com.roywatson.android_litert_object_recog

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roywatson.android_litert_object_recog.vision.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Numerical smoke validation, not an ImageNet accuracy evaluation or camera benchmark. */
@RunWith(AndroidJUnit4::class)
class NpuValidationTest {
    @Test fun cpuParityAndFullOffload() {
        assumeTrue("Use the Qualcomm build for NPU validation", BuildConfig.QUALCOMM_NPU_ENABLED)
        assumeTrue("Qualcomm validation requires one of the target phones", Build.MODEL in listOf("SM-S908U", "SM-S938U"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = JSONObject().put("device", Build.MODEL).put("soc_model", Build.SOC_MODEL)
            .put("android_api", Build.VERSION.SDK_INT).put("build", Build.FINGERPRINT)
            .put("runtime", BuildConfig.LITERT_VERSION).put("qairt", BuildConfig.QAIRT_VERSION)
            .put("build_type", BuildConfig.BUILD_TYPE).put("process_id", Process.myPid())
            .put("absolute_tolerance", 0.02).put("l1_tolerance", 0.10)
        val cases = JSONArray()
        report.put("cases", cases)
        instrumentation.sendStatus(2, Bundle().apply { putString("vision_npu_pid", Process.myPid().toString()) })
        val output = File(context.filesDir, "npu-validation.json")
        try {
            assertNull("NPU preflight", QualcommNpu.preflight(context))
            KotlinEngine(context, Backend.CPU).use { cpu ->
                val initializationStart = SystemClock.elapsedRealtimeNanos()
                NativeNpuEngine(context).use { npu ->
                    report.put("initialization_ms", (SystemClock.elapsedRealtimeNanos() - initializationStart) / 1e6)
                    report.put("execution_evidence", npu.executionEvidence)
                    assertEquals("NPU_ONLY_NON_CPU_FULLY_ACCELERATED", npu.executionEvidence)
                    val processor = FramePreprocessor()
                    try {
                        for (fixture in 0..2) {
                            val width = 320; val height = 240; val stride = width * 4 + 16
                            val rgba = ByteBuffer.allocate(stride * height)
                            for (y in 0 until height) for (x in 0 until width) {
                                val base = y * stride + x * 4
                                val channels = when (fixture) {
                                    0 -> intArrayOf(x * 255 / (width - 1), y * 255 / (height - 1), 128)
                                    1 -> if ((x / 24 + y / 24) % 2 == 0) intArrayOf(255, 32, 0) else intArrayOf(0, 128, 255)
                                    else -> intArrayOf(40, 160, 80)
                                }
                                for (c in 0..2) rgba.put(base + c, channels[c].toByte())
                                rgba.put(base + 3, 255.toByte())
                            }
                            // Same preprocessed tensor for both paths; exercises non-square padded RGBA + rotation.
                            val input = processor.prepareRgba(rgba, width, height, stride, 4,
                                Rect(0, 0, width, height), 90)
                            val reference = cpu.infer(input)
                            val firstStart = SystemClock.elapsedRealtimeNanos()
                            val actual = npu.infer(input)
                            val firstMs = (SystemClock.elapsedRealtimeNanos() - firstStart) / 1e6
                            assertEquals(1000, actual.size)
                            assertTrue(actual.all { it.isFinite() && it >= 0f && it <= 1f })
                            assertEquals(1.0, actual.sum().toDouble(), .002)
                            val differences = actual.indices.map { abs(actual[it] - reference[it]).toDouble() }
                            val maxError = differences.maxOrNull()!!
                            val l1 = differences.sum()
                            val refTop = reference.indices.sortedByDescending { reference[it] }
                            val actualTop = actual.indices.maxByOrNull { actual[it] }!!
                            val record = JSONObject().put("fixture", fixture).put("max_absolute_error", maxError)
                                .put("l1_error", l1).put("cpu_top1", refTop[0]).put("npu_top1", actualTop)
                                .put("cpu_top1_margin", reference[refTop[0]] - reference[refTop[1]])
                                .put("inference_ms", firstMs)
                            cases.put(record)
                            assertTrue("fixture=$fixture max error=$maxError", maxError <= .02)
                            assertTrue("fixture=$fixture L1 error=$l1", l1 <= .10)
                            if (reference[refTop[0]] - reference[refTop[1]] > .04f)
                                assertEquals("Decisive top1 changed", refTop[0], actualTop)
                        }
                    } finally { processor.close() }
                }
                // Recreate on the same thread/environment, then invoke. Detect stale buffers and reload regressions.
                NativeNpuEngine(context).use { reopened ->
                    try {
                        reopened.infer(FloatArray(3))
                        fail("Invalid input was accepted")
                    } catch (_: IllegalStateException) { /* expected native validation */ }
                    assertEquals(1000, reopened.infer(FloatArray(224 * 224 * 3)).size)
                }
            }
            report.put("result", "PASS")
            Log.i("VisionNpuValidation", "PASS: numerical smoke parity and full non-CPU model offload")
        } catch (failure: Throwable) {
            report.put("result", "FAIL").put("error", "${failure.javaClass.simpleName}: ${failure.message}")
            Log.e("VisionNpuValidation", "NPU validation failed", failure)
            throw failure
        } finally {
            output.writeText(report.toString(2))
            // Release apps disallow run-as; return this run's report through instrumentation too.
            instrumentation.sendStatus(2, Bundle().apply { putString("vision_npu_report", report.toString()) })
        }
    }
}
