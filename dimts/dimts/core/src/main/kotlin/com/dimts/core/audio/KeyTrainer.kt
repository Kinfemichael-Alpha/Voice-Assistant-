package com.dimts.core.audio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class TrainFailure {
    TOO_FEW_CLIPS,
    NO_SOUND_IN_CLIP,
    CLIP_TOO_SHORT,
    CLIP_TOO_LONG,
    INCONSISTENT,
}

/** Numbers behind a trained key, shown in the UI and useful when tuning. */
data class TrainReport(
    /** For each clip: distance to its closest sibling (leave-one-out). */
    val leaveOneOut: List<Double>,
    val threshold: Double,
    val frames: List<Int>,
)

sealed interface TrainResult {
    data class Success(val key: SoundKey, val report: TrainReport) : TrainResult

    /** [clipIndex] points at the offending recording when one can be singled out. */
    data class Failure(val reason: TrainFailure, val clipIndex: Int? = null) : TrainResult
}

/**
 * Builds a [SoundKey] from a handful of recordings of the same sound.
 *
 * The threshold is derived from the recordings themselves: the worst
 * "how far is each clip from its closest sibling" distance, times a safety [margin].
 * So a consistent sound gets a tight threshold, a variable one gets a looser one.
 */
object KeyTrainer {
    const val MIN_CLIPS = 3
    const val DEFAULT_MARGIN = 1.35

    /** Lowest threshold ever produced, so near-identical clips do not give an unusably strict key. */
    private const val MIN_THRESHOLD = 1.0

    fun train(
        name: String,
        clips: List<ShortArray>,
        features: FeatureConfig = FeatureConfig(),
        config: DetectorConfig = DetectorConfig(),
        margin: Double = DEFAULT_MARGIN,
    ): TrainResult {
        if (clips.size < MIN_CLIPS) return TrainResult.Failure(TrainFailure.TOO_FEW_CLIPS)

        val extractor = FeatureExtractor(features)
        val maxFrames = ceil(config.maxSegmentSeconds * 1000.0 / features.frameMillis).toInt()
        val templates = ArrayList<Array<DoubleArray>>()
        for ((index, clip) in clips.withIndex()) {
            when (val result = extractSound(clip, extractor, features, config)) {
                is Extracted.Ok -> {
                    if (result.frames.size > maxFrames) {
                        return TrainResult.Failure(TrainFailure.CLIP_TOO_LONG, index)
                    }
                    templates.add(result.frames)
                }
                is Extracted.Fail -> return TrainResult.Failure(result.reason, index)
            }
        }

        val count = templates.size
        val dist = Array(count) { DoubleArray(count) }
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                val ratio = templates[i].size.toDouble() / templates[j].size
                val d = if (ratio < SoundKey.MIN_LENGTH_RATIO || ratio > SoundKey.MAX_LENGTH_RATIO) {
                    Double.POSITIVE_INFINITY
                } else {
                    Dtw.distance(templates[i], templates[j])
                }
                dist[i][j] = d
                dist[j][i] = d
            }
        }
        val loo = List(count) { i -> (0 until count).filter { it != i }.minOf { dist[i][it] } }

        // A clip that matches nothing else is a different sound (or a cough, a door...).
        val worst = loo.indices.maxByOrNull { loo[it] }!!
        if (loo[worst].isInfinite()) return TrainResult.Failure(TrainFailure.INCONSISTENT, worst)
        val sorted = loo.sorted()
        val median = sorted[sorted.size / 2]
        if (loo[worst] > OUTLIER_FACTOR * median + OUTLIER_SLACK) {
            return TrainResult.Failure(TrainFailure.INCONSISTENT, worst)
        }

        val threshold = max(loo.max() * margin, MIN_THRESHOLD)
        val key = SoundKey(name, features, templates, threshold)
        return TrainResult.Success(key, TrainReport(loo, threshold, templates.map { it.size }))
    }

    private const val OUTLIER_FACTOR = 2.2
    private const val OUTLIER_SLACK = 1.0

    private sealed interface Extracted {
        class Ok(val frames: Array<DoubleArray>) : Extracted
        class Fail(val reason: TrainFailure) : Extracted
    }

    /**
     * Finds the sound in a deliberate recording (leading/trailing silence expected) and
     * returns its MFCC frames. If there are several bursts, the one with the most active
     * frames wins; bursts closer than the hangover are merged (two-word phrases).
     */
    private fun extractSound(
        clip: ShortArray,
        extractor: FeatureExtractor,
        features: FeatureConfig,
        config: DetectorConfig,
    ): Extracted {
        val frameSize = features.frameSize
        val hop = features.hopSize
        if (clip.size < frameSize + hop * 4) return Extracted.Fail(TrainFailure.CLIP_TOO_SHORT)

        val x = DoubleArray(clip.size) { clip[it] / 32768.0 }
        val frameCount = (x.size - frameSize) / hop + 1
        val db = DoubleArray(frameCount) { FeatureExtractor.levelDb(x, it * hop, frameSize) }

        val noise = db.sorted()[(frameCount * 0.1).toInt()]
        val gate = max(noise + config.onsetMarginDb, config.minAbsoluteDb)
        val active = (0 until frameCount).filter { db[it] > gate }
        if (active.size < config.onsetFrames) return Extracted.Fail(TrainFailure.NO_SOUND_IN_CLIP)

        // Group active frames into bursts separated by more than the hangover.
        val bursts = ArrayList<IntArray>() // [first, last, activeCount]
        var first = active[0]
        var last = active[0]
        var activeCount = 1
        for (k in 1 until active.size) {
            val f = active[k]
            if (f - last > config.hangoverFrames) {
                bursts.add(intArrayOf(first, last, activeCount))
                first = f
                activeCount = 0
            }
            last = f
            activeCount++
        }
        bursts.add(intArrayOf(first, last, activeCount))
        val best = bursts.maxByOrNull { it[2] }!!
        if (best[2] < config.onsetFrames) return Extracted.Fail(TrainFailure.NO_SOUND_IN_CLIP)

        val from = max(0, best[0] - config.preRollFrames)
        val to = min(frameCount - 1, best[1] + config.tailFrames)
        if (to - from + 1 < MIN_SOUND_FRAMES) return Extracted.Fail(TrainFailure.CLIP_TOO_SHORT)

        val frames = Array(to - from + 1) { extractor.extract(x, (from + it) * hop) }
        return Extracted.Ok(frames)
    }

    private const val MIN_SOUND_FRAMES = 4
}
