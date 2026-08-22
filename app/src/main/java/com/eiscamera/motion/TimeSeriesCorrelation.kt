package com.eiscamera.motion

import kotlin.math.sqrt

/**
 * Shared time-series alignment math: linear interpolation onto a common
 * grid, and a brute-force lag search that maximizes Pearson correlation.
 *
 * Originally built for V0.3's gyro-vs-rotation-vector dynamic response
 * cross-check (see sensors/SensorQualityAnalyzer.kt) and reused as-is for
 * V0.7's gyro-vs-camera-motion clock offset estimation (see
 * synchronization/SyncAnalyzer.kt) — the underlying math is identical in
 * both cases; only which two signals get compared differs. Pulling it out
 * here avoids maintaining two copies of tested, occasionally subtle math
 * (see docs/ROADMAP.md for the V0.3 quaternion double-cover bug found via
 * real-device testing — exactly the kind of thing worth not re-deriving).
 */
object TimeSeriesCorrelation {

    /**
     * Linear interpolation of a (time_ms, value) series onto arbitrary
     * query times. Returns NaN for query points outside the series' range.
     */
    fun interpolateLinear(series: List<Pair<Double, Double>>, queryTimesMs: DoubleArray): DoubleArray {
        val result = DoubleArray(queryTimesMs.size) { Double.NaN }
        var idx = 0
        for (i in queryTimesMs.indices) {
            val t = queryTimesMs[i]
            while (idx < series.size - 1 && series[idx + 1].first < t) idx++
            if (idx >= series.size - 1) {
                if (t == series.last().first) result[i] = series.last().second
                continue
            }
            val (t0, v0) = series[idx]
            val (t1, v1) = series[idx + 1]
            if (t < t0 || t > t1) continue
            val frac = if (t1 > t0) (t - t0) / (t1 - t0) else 0.0
            result[i] = v0 + frac * (v1 - v0)
        }
        return result
    }

    data class LagResult(val bestLagSteps: Int, val bestCorrelation: Double)

    /**
     * Finds the integer sample-shift (lag) that maximizes the Pearson
     * correlation coefficient between two equal-length series `a` and `b`.
     * `a[i]` is compared against `b[i + lag]`; a positive best lag means
     * `a` leads `b`. NaN entries (from interpolation gaps) are excluded
     * pairwise. Sign convention verified against synthetic data with a
     * known injected delay — see SensorQualityAnalyzerTest / SyncAnalyzerTest.
     */
    fun crossCorrelationLag(a: DoubleArray, b: DoubleArray, maxLagSteps: Int): LagResult {
        require(a.size == b.size) { "Series must be the same length" }
        var bestLag = 0
        var bestCorr = Double.NEGATIVE_INFINITY
        for (lag in -maxLagSteps..maxLagSteps) {
            val corr = pearsonAtLag(a, b, lag)
            if (!corr.isNaN() && corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        return LagResult(bestLag, if (bestCorr.isFinite()) bestCorr else 0.0)
    }

    fun pearsonAtLag(a: DoubleArray, b: DoubleArray, lag: Int): Double {
        val n = a.size
        val pairsA = ArrayList<Double>()
        val pairsB = ArrayList<Double>()
        for (i in 0 until n) {
            val j = i + lag
            if (j !in 0 until n) continue
            val av = a[i]; val bv = b[j]
            if (av.isNaN() || bv.isNaN()) continue
            pairsA += av; pairsB += bv
        }
        if (pairsA.size < 10) return Double.NaN
        val meanA = pairsA.average()
        val meanB = pairsB.average()
        var num = 0.0; var denA = 0.0; var denB = 0.0
        for (i in pairsA.indices) {
            val da = pairsA[i] - meanA; val db = pairsB[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val denom = sqrt(denA * denB)
        return if (denom > 0) num / denom else Double.NaN
    }
}
