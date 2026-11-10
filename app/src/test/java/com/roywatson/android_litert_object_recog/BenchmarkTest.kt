package com.roywatson.android_litert_object_recog

import com.roywatson.android_litert_object_recog.vision.*
import org.junit.Assert.*
import org.junit.Test

class BenchmarkTest {
    @Test fun throughputUsesWallTimeInsteadOfReciprocalLatency() {
        val result = BenchmarkMath.summarize(listOf(
            FrameTiming(5.0, 10.0, 1_000_000_000),
            FrameTiming(15.0, 20.0, 1_100_000_000),
            FrameTiming(10.0, 15.0, 1_200_000_000),
        ))
        assertEquals(10.0, result.fps, .0001)
        assertEquals(10.0, result.inferenceMeanMs, .0001)
        assertEquals(10.0, result.inferenceP50Ms, .0001)
        assertEquals(15.0, result.inferenceP95Ms, .0001)
        assertEquals(15.0, result.processingMeanMs, .0001)
    }
    @Test fun singleFrameHasNoThroughputEstimate() {
        assertEquals(0.0, BenchmarkMath.summarize(listOf(FrameTiming(2.0, 3.0, 1))).fps, 0.0)
    }
    @Test fun csvEscapesCommasQuotesAndNewlines() {
        assertEquals("\"device,1\",\"a\"\"b\",\"x\ny\"", csvRow("device,1", "a\"b", "x\ny"))
    }
}
