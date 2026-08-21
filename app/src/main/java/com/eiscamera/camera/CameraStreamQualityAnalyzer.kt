package com.eiscamera.camera

import kotlin.math.sqrt

/**
 * Pure-math analysis for the V0.4 Camera Stream Quality Test (spec
 * section 6, roadmap V0.4). Takes a list of [CameraFrameSample]s
 * collected from a real Camera2 capture session and computes the ACTUAL
 * sustained stream behavior — not the DECLARED characteristics V0.2 could
 * only read statically from CameraCharacteristics.
 *
 * No Android framework dependency, so this is unit-testable on the plain
 * JVM (see CameraStreamQualityAnalyzerTest).
 */
object CameraStreamQualityAnalyzer {

    private const val DROPPED_FRAME_INTERVAL_MULTIPLIER = 1.8

    data class Result(
        val frameCount: Int,
        val measuredFps: Double?,
        val frameIntervalJitterMs: Double?,
        val minIntervalMs: Double?,
        val maxIntervalMs: Double?,
        /**
         * Count of inter-frame intervals more than
         * DROPPED_FRAME_INTERVAL_MULTIPLIER times the MEDIAN interval — a
         * heuristic signal for a likely dropped/skipped frame. Camera2
         * does not expose an explicit platform-guaranteed "frames
         * dropped" counter, so this is ESTIMATED, not MEASURED (spec
         * section 41), and is documented as such wherever it's surfaced.
         */
        val likelyDroppedFrames: Int,
        val meanExposureTimeNs: Double?,
        val meanFrameDurationNs: Double?,
    )

    fun analyze(samples: List<CameraFrameSample>): Result {
        if (samples.size < 2) {
            return Result(
                frameCount = samples.size,
                measuredFps = null,
                frameIntervalJitterMs = null,
                minIntervalMs = null,
                maxIntervalMs = null,
                likelyDroppedFrames = 0,
                meanExposureTimeNs = samples.firstOrNull()?.exposureTimeNs?.toDouble(),
                meanFrameDurationNs = samples.firstOrNull()?.frameDurationNs?.toDouble(),
            )
        }

        val intervalsMs = DoubleArray(samples.size - 1) { i ->
            (samples[i + 1].sensorTimestampNs - samples[i].sensorTimestampNs) / 1_000_000.0
        }

        val totalDurationS = (samples.last().sensorTimestampNs - samples.first().sensorTimestampNs) / 1_000_000_000.0
        val measuredFps = if (totalDurationS > 0) (samples.size - 1) / totalDurationS else null

        val sortedIntervals = intervalsMs.sorted()
        val median = if (sortedIntervals.isEmpty()) 0.0 else sortedIntervals[sortedIntervals.size / 2]
        val droppedThreshold = median * DROPPED_FRAME_INTERVAL_MULTIPLIER
        val likelyDropped = if (median > 0) intervalsMs.count { it > droppedThreshold } else 0

        val exposureTimes = samples.mapNotNull { it.exposureTimeNs?.toDouble() }
        val frameDurations = samples.mapNotNull { it.frameDurationNs?.toDouble() }

        return Result(
            frameCount = samples.size,
            measuredFps = measuredFps,
            frameIntervalJitterMs = stdDev(intervalsMs),
            minIntervalMs = intervalsMs.minOrNull(),
            maxIntervalMs = intervalsMs.maxOrNull(),
            likelyDroppedFrames = likelyDropped,
            meanExposureTimeNs = if (exposureTimes.isNotEmpty()) exposureTimes.average() else null,
            meanFrameDurationNs = if (frameDurations.isNotEmpty()) frameDurations.average() else null,
        )
    }

    private fun stdDev(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }
}
