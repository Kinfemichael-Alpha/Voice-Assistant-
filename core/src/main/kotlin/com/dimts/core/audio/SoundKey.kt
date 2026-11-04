package com.dimts.core.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * An enrolled "key" sound: a few templates of the same sound plus the distance below
 * which a new sound counts as the same one.
 *
 * It can be a spoken word, a whistle, a clap pattern, a tone... anything the user can
 * reproduce consistently. No neural network is involved: matching is plain DSP.
 *
 * @property threshold maximum normalised DTW distance that still counts as a match.
 */
class SoundKey(
    val name: String,
    val features: FeatureConfig,
    val templates: List<Array<DoubleArray>>,
    val threshold: Double,
) {
    init {
        require(templates.isNotEmpty()) { "A key needs at least one template" }
        require(threshold > 0.0) { "threshold must be positive" }
    }

    val minFrames: Int = templates.minOf { it.size }
    val maxFrames: Int = templates.maxOf { it.size }

    /**
     * Smallest DTW distance from [candidate] to any template, or [Double.POSITIVE_INFINITY]
     * if its length is implausible for this key. [abandonAbove] is forwarded to [Dtw].
     */
    fun distance(candidate: Array<DoubleArray>, abandonAbove: Double = Double.POSITIVE_INFINITY): Double {
        var best = abandonAbove
        var result = Double.POSITIVE_INFINITY
        for (template in templates) {
            val ratio = candidate.size.toDouble() / template.size
            if (ratio < MIN_LENGTH_RATIO || ratio > MAX_LENGTH_RATIO) continue
            val d = Dtw.distance(candidate, template, abandonAbove = best)
            if (d < result) {
                result = d
                best = min(best, d)
            }
        }
        return result
    }

    /** Copy of this key with a different threshold (used for sensitivity tuning). */
    fun withThreshold(newThreshold: Double): SoundKey = SoundKey(name, features, templates, newThreshold)

    fun write(output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(name)
        out.writeInt(features.sampleRate)
        out.writeInt(features.frameSize)
        out.writeInt(features.hopSize)
        out.writeInt(features.numFilters)
        out.writeInt(features.numCoeffs)
        out.writeDouble(features.lowHz)
        out.writeDouble(features.highHz)
        out.writeDouble(features.preEmphasis)
        out.writeDouble(features.dynamicRangeDb)
        out.writeDouble(threshold)
        out.writeInt(templates.size)
        for (template in templates) {
            out.writeInt(template.size)
            for (frame in template) for (v in frame) out.writeFloat(v.toFloat())
        }
        out.flush()
    }

    fun toByteArray(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        write(bytes)
        return bytes.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x44544B31 // "DTK1"
        private const val VERSION = 1

        /** Length ratio (candidate / template) outside this range is rejected without running DTW. */
        const val MIN_LENGTH_RATIO = 0.5
        const val MAX_LENGTH_RATIO = 2.0

        /**
         * Reads a key written by [write].
         * @throws IOException if the data is corrupt or was made with different [FeatureConfig] values.
         */
        fun read(input: InputStream, expected: FeatureConfig = FeatureConfig()): SoundKey {
            val inp = DataInputStream(input)
            if (inp.readInt() != MAGIC) throw IOException("Not a Dimts key file")
            val version = inp.readInt()
            if (version != VERSION) throw IOException("Unsupported key version $version")
            val name = inp.readUTF()
            val stored = try {
                expected.copy(
                    sampleRate = inp.readInt(),
                    frameSize = inp.readInt(),
                    hopSize = inp.readInt(),
                    numFilters = inp.readInt(),
                    numCoeffs = inp.readInt(),
                    lowHz = inp.readDouble(),
                    highHz = inp.readDouble(),
                    preEmphasis = inp.readDouble(),
                    dynamicRangeDb = inp.readDouble(),
                )
            } catch (e: IllegalArgumentException) {
                throw IOException("Corrupt key (invalid audio settings)", e)
            }
            if (stored != expected) throw IOException("Key was recorded with different audio settings; please re-enrol it")
            val threshold = inp.readDouble()
            val count = inp.readInt()
            if (count !in 1..64) throw IOException("Corrupt key (template count $count)")
            val templates = List(count) {
                val frames = inp.readInt()
                if (frames !in 1..10_000) throw IOException("Corrupt key (frame count $frames)")
                Array(frames) { DoubleArray(expected.numCoeffs) { inp.readFloat().toDouble() } }
            }
            return SoundKey(name, expected, templates, threshold)
        }

        fun fromByteArray(bytes: ByteArray, expected: FeatureConfig = FeatureConfig()): SoundKey =
            read(bytes.inputStream(), expected)

        /** Inclusive plausible frame-count window for a segment that could match any of [keys]. */
        fun acceptableFrames(keys: List<SoundKey>): IntRange {
            val lo = keys.minOf { it.minFrames } * MIN_LENGTH_RATIO
            val hi = keys.maxOf { it.maxFrames } * MAX_LENGTH_RATIO
            return max(3, lo.toInt())..max(4, hi.toInt() + 1)
        }
    }
}
