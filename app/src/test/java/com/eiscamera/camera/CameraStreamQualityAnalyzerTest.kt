package com.eiscamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStreamQualityAnalyzerTest {

    @Test
    fun `analyze computes fps and jitter for uniform frames`() {
        val startNs = 1_000_000_000L
        val intervalNs = 33_333_333L // ~30fps
        val samples = (0 until 30).map {
            CameraFrameSample(startNs + it * intervalNs, exposureTimeNs = 8_000_000L, frameDurationNs = intervalNs)
        }

        val result = CameraStreamQualityAnalyzer.analyze(samples)

        assertEquals(30, result.frameCount)
        assertEquals(30.0, result.measuredFps!!, 0.5)
        assertEquals(0.0, result.frameIntervalJitterMs!!, 1e-6)
        assertEquals(0, result.likelyDroppedFrames)
        assertEquals(8_000_000.0, result.meanExposureTimeNs!!, 1e-6)
        assertEquals(intervalNs.toDouble(), result.meanFrameDurationNs!!, 1e-6)
    }

    @Test
    fun `analyze detects a likely dropped frame from an interval outlier`() {
        val intervalNs = 33_333_333L
        val timestamps = mutableListOf(0L)
        repeat(9) { timestamps += timestamps.last() + intervalNs }
        // Inject one big gap simulating a dropped frame (~3x normal interval).
        timestamps += timestamps.last() + intervalNs * 3
        repeat(9) { timestamps += timestamps.last() + intervalNs }

        val samples = timestamps.map { CameraFrameSample(it, null, null) }
        val result = CameraStreamQualityAnalyzer.analyze(samples)

        assertEquals(1, result.likelyDroppedFrames)
    }

    @Test
    fun `analyze handles a single sample without crashing`() {
        val result = CameraStreamQualityAnalyzer.analyze(listOf(CameraFrameSample(0L, 1000L, 2000L)))
        assertEquals(1, result.frameCount)
        assertEquals(null, result.measuredFps)
    }

    @Test
    fun `analyze handles zero samples without crashing`() {
        val result = CameraStreamQualityAnalyzer.analyze(emptyList())
        assertEquals(0, result.frameCount)
        assertEquals(null, result.measuredFps)
    }
}
