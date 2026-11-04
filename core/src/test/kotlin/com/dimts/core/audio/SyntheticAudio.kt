package com.dimts.core.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Deterministic test signals. A "sound" is a sequence of (frequency glide, duration) parts
 * with harmonics, so different sounds have clearly different spectral shapes over time,
 * a bit like different words.
 */
internal object SyntheticAudio {
    const val RATE = 16_000

    class Part(val fromHz: Double, val toHz: Double, val seconds: Double)

    /** Sound A: rising then falling "whoop-whoop". */
    val SOUND_A = listOf(Part(500.0, 1500.0, 0.25), Part(1500.0, 700.0, 0.20), Part(900.0, 2200.0, 0.25))

    /** Sound B: different shape and similar total length, should NOT match A. */
    val SOUND_B = listOf(Part(2500.0, 2500.0, 0.20), Part(300.0, 300.0, 0.30), Part(1800.0, 900.0, 0.20))

    /** Sound C: long steady tone. */
    val SOUND_C = listOf(Part(1000.0, 1000.0, 0.8))

    fun silence(seconds: Double, noise: Double = 0.0, rnd: Random = Random(1)): ShortArray =
        ShortArray((seconds * RATE).toInt()) { (noise * 32767 * rnd.nextGaussian()).toInt().coerceIn(-32768, 32767).toShort() }

    /**
     * Renders [parts] with variation: [tempo] stretches time, [pitch] scales frequency,
     * [gain] is amplitude (0..1), [noise] adds white noise at that amplitude.
     */
    fun render(
        parts: List<Part>,
        tempo: Double = 1.0,
        pitch: Double = 1.0,
        gain: Double = 0.5,
        noise: Double = 0.003,
        seed: Long = 7,
    ): ShortArray {
        val rnd = Random(seed)
        val out = ArrayList<Short>()
        var phase = 0.0
        for (part in parts) {
            val n = (part.seconds * tempo * RATE).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = (part.fromHz + (part.toHz - part.fromHz) * t) * pitch
                phase += 2 * PI * f / RATE
                // Smooth fade at part edges avoids clicks that would act like broadband bursts.
                val env = minOf(1.0, minOf(i, n - i) / 160.0)
                val v = env * (sin(phase) + 0.4 * sin(2 * phase) + 0.15 * sin(3 * phase)) / 1.55
                val s = gain * v + noise * rnd.nextGaussian()
                out.add((s * 32767).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        return out.toShortArray()
    }

    /** Pads [sound] with room noise before and after, as in a real recording. */
    fun padded(
        sound: ShortArray,
        lead: Double = 0.6,
        trail: Double = 0.8,
        noise: Double = 0.003,
        seed: Long = 3,
    ): ShortArray = silence(lead, noise, Random(seed)) + sound + silence(trail, noise, Random(seed + 1))

    /** Several slightly different "takes" of [parts], like a person repeating a word. */
    fun takes(parts: List<Part>, count: Int, seed: Long = 100): List<ShortArray> =
        List(count) { i ->
            val r = Random(seed + i)
            val tempo = 1.0 + 0.12 * r.nextGaussian().coerceIn(-1.5, 1.5)
            val pitch = 1.0 + 0.04 * r.nextGaussian().coerceIn(-1.5, 1.5)
            val gain = 0.3 + 0.4 * r.nextDouble()
            padded(render(parts, tempo, pitch, gain, seed = seed + i), seed = seed + 10 * i)
        }

    fun peak(x: ShortArray): Int = x.maxOf { abs(it.toInt()) }

    /** Feeds [signal] to the detector in chunks of [chunk] samples and returns all detections. */
    fun runDetector(detector: KeyDetector, signal: ShortArray, chunk: Int = 1024): List<Detection> {
        val found = ArrayList<Detection>()
        var pos = 0
        while (pos < signal.size) {
            val n = minOf(chunk, signal.size - pos)
            val piece = signal.copyOfRange(pos, pos + n)
            detector.feed(piece, n)?.let { found.add(it) }
            pos += n
        }
        return found
    }
}
