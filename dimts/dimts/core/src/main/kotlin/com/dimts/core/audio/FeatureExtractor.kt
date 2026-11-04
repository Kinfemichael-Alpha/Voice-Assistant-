package com.dimts.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Audio front-end settings. They are stored inside every saved [SoundKey], so a key
 * recorded with different settings is rejected instead of silently never matching.
 */
data class FeatureConfig(
    val sampleRate: Int = 16_000,
    /** Analysis window in samples (power of two). 512 @ 16 kHz = 32 ms. */
    val frameSize: Int = 512,
    /** Step between windows in samples. 256 @ 16 kHz = 16 ms (62.5 frames/s). */
    val hopSize: Int = 256,
    val numFilters: Int = 26,
    /** Number of cepstral coefficients kept (c1..cN; c0 = loudness is dropped). */
    val numCoeffs: Int = 12,
    val lowHz: Double = 100.0,
    val highHz: Double = 7_000.0,
    val preEmphasis: Double = 0.97,
    /**
     * Mel bands more than this many dB below the strongest band of the same frame are
     * clamped. Background noise mostly lives in those weak bands, so clamping makes the
     * features much less sensitive to how noisy the room is.
     */
    val dynamicRangeDb: Double = 20.0,
) {
    init {
        require(frameSize >= 2 && (frameSize and (frameSize - 1)) == 0) { "frameSize must be a power of two" }
        require(hopSize in 1..frameSize) { "hopSize must be in 1..frameSize" }
        require(numCoeffs in 1 until numFilters) { "numCoeffs must be smaller than numFilters" }
        require(highHz <= sampleRate / 2.0 && lowHz < highHz) { "invalid frequency range" }
        require(dynamicRangeDb > 0.0) { "dynamicRangeDb must be positive" }
    }

    val frameMillis: Double get() = 1000.0 * hopSize / sampleRate
}

/**
 * Turns one window of audio into a small MFCC vector (log-mel filterbank + DCT).
 *
 * Dropping c0 makes the vector nearly independent of how loud the sound was, which
 * is what lets the same word match at different distances from the microphone.
 *
 * Not thread-safe: it owns scratch buffers. Use one instance per thread.
 */
class FeatureExtractor(val config: FeatureConfig = FeatureConfig()) {
    private val n = config.frameSize
    private val fft = Fft(n)
    private val window = DoubleArray(n) { 0.54 - 0.46 * cos(2.0 * PI * it / (n - 1)) }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val bins = n / 2 + 1
    private val melEnergy = DoubleArray(config.numFilters)
    private val logMel = DoubleArray(config.numFilters)
    private val rangeFactor = Math.pow(10.0, -config.dynamicRangeDb / 10.0)

    private val filterStart = IntArray(config.numFilters)
    private val filterWeights: Array<DoubleArray>
    private val dct: Array<DoubleArray>

    init {
        val lowMel = hzToMel(config.lowHz)
        val highMel = hzToMel(config.highHz)
        val points = DoubleArray(config.numFilters + 2) { i ->
            val mel = lowMel + (highMel - lowMel) * i / (config.numFilters + 1)
            melToHz(mel) * n / config.sampleRate // fractional FFT bin index
        }
        filterWeights = Array(config.numFilters) { m ->
            val left = points[m]
            val center = points[m + 1]
            val right = points[m + 2]
            val first = kotlin.math.ceil(left).toInt().coerceAtLeast(0)
            val last = kotlin.math.floor(right).toInt().coerceAtMost(bins - 1)
            filterStart[m] = first
            DoubleArray((last - first + 1).coerceAtLeast(0)) { k ->
                val bin = (first + k).toDouble()
                when {
                    bin <= left || bin >= right -> 0.0
                    bin <= center -> (bin - left) / (center - left)
                    else -> (right - bin) / (right - center)
                }
            }
        }
        // Orthonormal DCT-II rows for c1..cN (c0 is skipped on purpose).
        val scale = sqrt(2.0 / config.numFilters)
        dct = Array(config.numCoeffs) { c ->
            DoubleArray(config.numFilters) { m ->
                scale * cos(PI * (c + 1) * (m + 0.5) / config.numFilters)
            }
        }
    }

    val dims: Int get() = config.numCoeffs

    /**
     * Extracts MFCCs from [samples] (normalised to -1..1), reading [FeatureConfig.frameSize]
     * values starting at [offset]. Returns a freshly allocated vector of [dims] values.
     */
    fun extract(samples: DoubleArray, offset: Int = 0): DoubleArray {
        require(offset >= 0 && offset + n <= samples.size) { "Not enough samples for one frame" }
        val pre = config.preEmphasis
        re[0] = samples[offset] * window[0]
        for (i in 1 until n) {
            re[i] = (samples[offset + i] - pre * samples[offset + i - 1]) * window[i]
        }
        im.fill(0.0)
        fft.transform(re, im)

        var peak = 0.0
        for (m in 0 until config.numFilters) {
            val weights = filterWeights[m]
            val start = filterStart[m]
            var energy = 0.0
            for (k in weights.indices) {
                val b = start + k
                energy += weights[k] * (re[b] * re[b] + im[b] * im[b])
            }
            melEnergy[m] = energy
            if (energy > peak) peak = energy
        }
        val floor = maxOf(peak * rangeFactor, ENERGY_FLOOR)
        for (m in 0 until config.numFilters) {
            logMel[m] = ln(if (melEnergy[m] < floor) floor else melEnergy[m])
        }

        val out = DoubleArray(config.numCoeffs)
        for (c in 0 until config.numCoeffs) {
            val row = dct[c]
            var acc = 0.0
            for (m in 0 until config.numFilters) acc += row[m] * logMel[m]
            out[c] = acc
        }
        return out
    }

    companion object {
        private const val ENERGY_FLOOR = 1e-10

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        /** Mean-square level of one window in dB (relative to full scale = 0 dB). */
        fun levelDb(samples: DoubleArray, offset: Int, length: Int): Double {
            var sum = 0.0
            for (i in offset until offset + length) sum += samples[i] * samples[i]
            return 10.0 * log10(sum / length + 1e-12)
        }
    }
}
