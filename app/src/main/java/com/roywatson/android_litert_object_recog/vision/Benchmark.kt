package com.roywatson.android_litert_object_recog.vision

import kotlin.math.ceil

data class FrameTiming(val inferenceMs: Double, val processingMs: Double, val completedNs: Long)

data class BenchmarkResult(
    val samples: Int,
    val inferenceMeanMs: Double,
    val inferenceP50Ms: Double,
    val inferenceP95Ms: Double,
    val processingMeanMs: Double,
    val fps: Double,
)

/** Pure measurement math. FPS measures completed camera analyses, not 1000/inference time. */
object BenchmarkMath {
    fun summarize(frames: List<FrameTiming>): BenchmarkResult {
        require(frames.isNotEmpty())
        val sorted = frames.map { it.inferenceMs }.sorted()
        fun percentile(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
        val elapsed = frames.last().completedNs - frames.first().completedNs
        return BenchmarkResult(
            frames.size, sorted.average(), percentile(.5), percentile(.95),
            frames.map { it.processingMs }.average(),
            if (frames.size > 1 && elapsed > 0) (frames.size - 1) * 1e9 / elapsed else 0.0,
        )
    }
}

fun csvRow(vararg fields: Any?): String = fields.joinToString(",") {
    "\"${it.toString().replace("\"", "\"\"")}\""
}
