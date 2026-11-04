package com.dimts.core.audio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Tunables for sound segmentation and matching. */
data class DetectorConfig(
    /** A frame is "active" when it is this many dB above the tracked noise floor... */
    val onsetMarginDb: Double = 10.0,
    /** ...and counts as silence again when it falls below noise floor + this margin. */
    val offsetMarginDb: Double = 6.0,
    /** Never treat anything quieter than this (dB re full scale) as a sound. */
    val minAbsoluteDb: Double = -55.0,
    /** Consecutive active frames needed to start a segment. */
    val onsetFrames: Int = 2,
    /** Silent frames that end a segment (20 frames = 320 ms; bridges pauses between words). */
    val hangoverFrames: Int = 20,
    /** Frames kept from before the onset so soft consonants are not cut off. */
    val preRollFrames: Int = 3,
    /** Frames kept after the last loud frame. */
    val tailFrames: Int = 2,
    /** Hard cap on segment length; longer sounds are ignored (music, TV, traffic). */
    val maxSegmentSeconds: Double = 4.0,
    /** Multiplies every key's threshold. Above 1 = easier to trigger, below 1 = stricter. */
    val thresholdScale: Double = 1.0,
) {
    init {
        require(onsetFrames >= 1 && hangoverFrames >= 1 && preRollFrames >= 0 && tailFrames >= 0)
        require(thresholdScale > 0.0)
    }
}

/** A successful match. */
data class Detection(
    val keyName: String,
    val distance: Double,
    val threshold: Double,
    val frames: Int,
)

/**
 * Diagnostic for every sound segment the detector evaluated, matched or not.
 * The UI shows this so the user can see how close a try was and tune the sensitivity.
 */
data class SegmentReport(
    val frames: Int,
    val bestKey: String?,
    /** Smallest distance found, or [Double.POSITIVE_INFINITY] when nothing was comparable. */
    val distance: Double,
    val threshold: Double,
    val matched: Boolean,
)

/**
 * Streaming "key sound" detector: always-on listening at almost no cost.
 *
 * Per audio frame (16 ms) it first computes one cheap loudness number. The FFT, MFCCs
 * and DTW matching only run while a sound is actually happening, so in a quiet room it
 * is close to a no-op. No ML model and no allocation per quiet frame.
 *
 * Pipeline: loudness gate -> segment (start/stop by energy, with hangover) -> MFCC
 * sequence -> DTW against each enrolled [SoundKey] -> match if distance <= threshold.
 *
 * Feed it raw 16 kHz mono PCM in chunks of any size via [feed]. Not thread-safe.
 */
class KeyDetector(
    keys: List<SoundKey>,
    private val config: DetectorConfig = DetectorConfig(),
    private val features: FeatureConfig = FeatureConfig(),
) {
    /** Called (on the feeding thread) for every evaluated segment. */
    var listener: ((SegmentReport) -> Unit)? = null

    private val keys: List<SoundKey> = keys.toList()
    private val extractor = FeatureExtractor(features)
    private val frameSize = features.frameSize
    private val hop = features.hopSize

    private val acceptable: IntRange =
        if (this.keys.isEmpty()) IntRange.EMPTY else SoundKey.acceptableFrames(this.keys)

    private val maxSegmentFrames: Int = min(
        ceil(config.maxSegmentSeconds * 1000.0 / features.frameMillis).toInt(),
        if (acceptable.isEmpty()) 0 else acceptable.last + config.hangoverFrames + 1,
    )

    private enum class State { IDLE, IN_SEGMENT, WAIT_QUIET }

    private var state = State.IDLE
    private var noiseDb = 0.0
    private var noiseInitialised = false

    // Input accumulation (samples as -1..1 doubles).
    private var buf = DoubleArray(frameSize * 8)
    private var bufLen = 0

    // Pre-roll ring of raw windows, so the start of a sound is not lost to the onset delay.
    private val ringSize = config.preRollFrames + config.onsetFrames
    private val ring = Array(ringSize) { DoubleArray(frameSize) }
    private var ringHead = 0
    private var ringCount = 0
    private var onsetRun = 0

    // Current segment.
    private val segment = ArrayList<DoubleArray>()
    private var lastActive = 0
    private var silentRun = 0
    private var quietRun = 0

    /** Forget everything (call after stopping and restarting the microphone). */
    fun reset() {
        noiseInitialised = false
        state = State.IDLE
        bufLen = 0
        ringCount = 0
        ringHead = 0
        onsetRun = 0
        segment.clear()
        silentRun = 0
        quietRun = 0
    }

    /**
     * Processes [length] samples from [samples]. Returns the first [Detection] found in this
     * chunk, or null. Keeps consuming the rest of the chunk either way.
     */
    fun feed(samples: ShortArray, length: Int = samples.size): Detection? {
        if (keys.isEmpty() || length <= 0) return null
        require(length <= samples.size)
        ensureCapacity(bufLen + length)
        for (i in 0 until length) buf[bufLen + i] = samples[i] / 32768.0
        bufLen += length

        var pos = 0
        var found: Detection? = null
        while (bufLen - pos >= frameSize) {
            val detection = processFrame(pos)
            if (detection != null && found == null) found = detection
            pos += hop
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, bufLen - pos)
            bufLen -= pos
        }
        return found
    }

    private fun ensureCapacity(needed: Int) {
        if (needed > buf.size) buf = buf.copyOf(max(needed, buf.size * 2))
    }

    private fun processFrame(pos: Int): Detection? {
        val db = FeatureExtractor.levelDb(buf, pos, frameSize)
        return when (state) {
            State.IDLE -> onIdle(db, pos)
            State.IN_SEGMENT -> onSegment(db, pos)
            State.WAIT_QUIET -> onWaitQuiet(db)
        }
    }

    private fun onIdle(db: Double, pos: Int): Detection? {
        if (!noiseInitialised) {
            noiseDb = db
            noiseInitialised = true
        } else if (db < noiseDb) {
            noiseDb += 0.2 * (db - noiseDb) // follow drops quickly
        } else if (db < noiseDb + config.offsetMarginDb) {
            noiseDb += 0.02 * (db - noiseDb) // follow slow drift upwards
        } else {
            noiseDb += 0.0005 * (db - noiseDb) // loud frames barely move the floor
        }

        // Keep the raw window; costs one 2 KB copy per quiet frame.
        System.arraycopy(buf, pos, ring[ringHead], 0, frameSize)
        ringHead = (ringHead + 1) % ringSize
        if (ringCount < ringSize) ringCount++

        val active = db > max(noiseDb + config.onsetMarginDb, config.minAbsoluteDb)
        onsetRun = if (active) onsetRun + 1 else 0
        if (onsetRun >= config.onsetFrames) startSegment()
        return null
    }

    private fun startSegment() {
        segment.clear()
        for (i in 0 until ringCount) {
            val idx = (ringHead - ringCount + i + ringSize * 2) % ringSize
            segment.add(extractor.extract(ring[idx], 0))
        }
        lastActive = segment.size - 1
        silentRun = 0
        onsetRun = 0
        ringCount = 0
        state = State.IN_SEGMENT
    }

    private fun onSegment(db: Double, pos: Int): Detection? {
        segment.add(extractor.extract(buf, pos))
        if (db < noiseDb + config.offsetMarginDb) {
            silentRun++
        } else {
            silentRun = 0
            lastActive = segment.size - 1
        }

        if (segment.size > maxSegmentFrames) {
            // Too long to be any of our keys: music, a conversation, a vehicle. Wait for quiet.
            segment.clear()
            quietRun = 0
            state = State.WAIT_QUIET
            return null
        }
        if (silentRun >= config.hangoverFrames) return finishSegment()
        return null
    }

    private fun onWaitQuiet(db: Double): Detection? {
        // A permanently loud background must eventually become the new floor.
        noiseDb += (if (db < noiseDb) 0.2 else 0.02) * (db - noiseDb)
        if (db < noiseDb + config.offsetMarginDb) quietRun++ else quietRun = 0
        if (quietRun >= config.hangoverFrames) {
            state = State.IDLE
            onsetRun = 0
            ringCount = 0
        }
        return null
    }

    private fun finishSegment(): Detection? {
        val end = min(segment.size, lastActive + 1 + config.tailFrames)
        val candidate = Array(end) { segment[it] }
        segment.clear()
        state = State.IDLE
        onsetRun = 0
        ringCount = 0
        return evaluate(candidate)
    }

    private fun evaluate(candidate: Array<DoubleArray>): Detection? {
        if (candidate.size !in acceptable) {
            listener?.invoke(SegmentReport(candidate.size, null, Double.POSITIVE_INFINITY, 0.0, false))
            return null
        }
        var bestKey: SoundKey? = null
        var bestDistance = Double.POSITIVE_INFINITY
        var bestThreshold = 0.0
        var bestRatio = Double.POSITIVE_INFINITY
        for (key in keys) {
            val threshold = key.threshold * config.thresholdScale
            val d = key.distance(candidate, abandonAbove = threshold * 4.0)
            val ratio = d / threshold
            if (ratio < bestRatio) {
                bestRatio = ratio
                bestKey = key
                bestDistance = d
                bestThreshold = threshold
            }
        }
        val matched = bestRatio <= 1.0
        listener?.invoke(
            SegmentReport(candidate.size, bestKey?.name, bestDistance, bestThreshold, matched),
        )
        return if (matched && bestKey != null) {
            Detection(bestKey.name, bestDistance, bestThreshold, candidate.size)
        } else {
            null
        }
    }
}
