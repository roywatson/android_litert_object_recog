package com.roywatson.android_litert_object_recog

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.roywatson.android_litert_object_recog.vision.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VisionPipelineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledModelProduces1000ProbabilitiesOnCpu() {
        CompiledModel.create(context.assets, VisionSession.MODEL, CompiledModel.Options(Accelerator.CPU)).use { model ->
            val input = model.createInputBuffers()
            val output = model.createOutputBuffers()
            try {
                input.single().writeFloat(FloatArray(224 * 224 * 3))
                model.run(input, output)
                val scores = output.single().readFloat()
                assertEquals(1000, scores.size)
                assertTrue(scores.all { it.isFinite() && it in 0f..1f })
                assertEquals(1.0, scores.sum().toDouble(), .001)
                assertEquals(scores.size, context.assets.open("labels.txt").bufferedReader().use { it.readLines().size })
            } finally { output.forEach { it.close() }; input.forEach { it.close() } }
        }
    }

    @Test fun rgbaHonorsRowPaddingRotationAndNormalization() {
        val data = ByteBuffer.allocate(24)
        // 2x2: red left / blue right, with four bytes of padding per row.
        for (y in 0..1) for (x in 0..1) {
            val offset = y * 12 + x * 4
            data.put(offset, if (x == 0) 255.toByte() else 0)
            data.put(offset + 1, 0)
            data.put(offset + 2, if (x == 1) 255.toByte() else 0)
            data.put(offset + 3, 255.toByte())
        }
        val processor = FramePreprocessor()
        try {
            for (rotation in listOf(0, 90, 180, 270)) {
                val rgb = processor.prepareRgba(data, 2, 2, 12, 4, Rect(0, 0, 2, 2), rotation)
                val redPoint = when (rotation) { 0 -> 20 to 112; 90 -> 112 to 20; 180 -> 200 to 112; else -> 112 to 200 }
                val index = (redPoint.second * 224 + redPoint.first) * 3
                assertEquals("rotation=$rotation red", 1f, rgb[index], .01f)
                assertEquals(-127f / 128f, rgb[index + 1], .01f)
                assertEquals(-127f / 128f, rgb[index + 2], .01f)
            }
        } finally { processor.close() }
    }

    @Test fun centerCropExcludesOuterColumns() {
        val data = ByteBuffer.allocate(32)
        for (y in 0..1) for (x in 0..3) {
            val offset = (y * 4 + x) * 4
            data.put(offset, if (x == 0 || x == 3) 255.toByte() else 0)
            data.put(offset + 1, if (x == 1 || x == 2) 255.toByte() else 0)
            data.put(offset + 3, 255.toByte())
        }
        val processor = FramePreprocessor()
        try {
            val rgb = processor.prepareRgba(data, 4, 2, 16, 4, Rect(0, 0, 4, 2), 0)
            val center = (112 * 224 + 112) * 3
            assertEquals(-127f / 128f, rgb[center], .01f)
            assertEquals(1f, rgb[center + 1], .01f)
        } finally { processor.close() }
    }

    @Test fun npuSelectionReportsOffloadOrExplicitFallback() {
        val ready = CountDownLatch(1)
        var result: VisionState? = null
        val session = VisionSession(context) { state ->
            if (state.requested == Backend.NPU && state.active != null) { result = state; ready.countDown() }
        }
        try {
            session.select(Backend.NPU)
            assertTrue("NPU fallback timed out", ready.await(30, TimeUnit.SECONDS))
            val snapshot = requireNotNull(result)
            if (QualcommNpu.preflight(context) == null) {
                assertEquals(Backend.NPU, snapshot.active)
                assertEquals("NPU_ONLY_NON_CPU_FULLY_ACCELERATED", snapshot.executionEvidence)
            } else {
                assertEquals(Backend.CPU, snapshot.active)
                assertTrue(snapshot.message.contains("NPU unavailable"))
                assertTrue(snapshot.fallbackReason.isNotBlank())
            }
        } finally { session.close() }
    }
}
