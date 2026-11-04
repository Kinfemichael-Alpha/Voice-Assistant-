package com.dimts.core.audio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dynamic time warping between two feature sequences.
 *
 * This is what lets a word spoken a little faster or slower still match the enrolled
 * template. Uses the symmetric step pattern (diagonal steps cost double) with a
 * Sakoe-Chiba band around the scaled diagonal, and returns a length-normalised cost so
 * that distances are comparable between sequences of different duration.
 */
object Dtw {
    /**
     * @param bandRatio half-width of the allowed warping band, as a fraction of the longer sequence.
     * @param abandonAbove stop early and return [Double.POSITIVE_INFINITY] once the result is
     *        guaranteed to exceed this value (normalised units). Saves CPU when rejecting.
     */
    fun distance(
        a: Array<DoubleArray>,
        b: Array<DoubleArray>,
        bandRatio: Double = 0.3,
        abandonAbove: Double = Double.POSITIVE_INFINITY,
    ): Double {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Double.POSITIVE_INFINITY

        // The band must be at least as wide as the per-row slope, or it becomes disconnected.
        val slope = ceil(max(n, m).toDouble() / min(n, m)).toInt() + 1
        val radius = max(max(2, slope), ceil(bandRatio * max(n, m)).toInt())
        val norm = (n + m).toDouble()
        val limit = if (abandonAbove.isInfinite()) Double.POSITIVE_INFINITY else abandonAbove * norm

        var prev = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var curr = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        prev[0] = 0.0

        for (i in 1..n) {
            java.util.Arrays.fill(curr, Double.POSITIVE_INFINITY)
            val center = i.toDouble() * m / n
            val jStart = max(1, (center - radius).toInt())
            val jEnd = min(m, ceil(center + radius).toInt())
            var rowMin = Double.POSITIVE_INFINITY
            val ai = a[i - 1]
            for (j in jStart..jEnd) {
                val d = euclidean(ai, b[j - 1])
                val best = min(prev[j - 1] + 2.0 * d, min(prev[j] + d, curr[j - 1] + d))
                curr[j] = best
                if (best < rowMin) rowMin = best
            }
            if (rowMin > limit) return Double.POSITIVE_INFINITY
            val tmp = prev; prev = curr; curr = tmp
            // After the swap, prev holds row i; curr is scratch for row i+1.
            // Row 0 only has a valid start at column 0.
            prev[0] = Double.POSITIVE_INFINITY
        }
        val total = prev[m]
        return if (total.isInfinite()) total else total / norm
    }

    private fun euclidean(x: DoubleArray, y: DoubleArray): Double {
        var s = 0.0
        for (k in x.indices) {
            val d = x[k] - y[k]
            s += d * d
        }
        return sqrt(s)
    }
}
