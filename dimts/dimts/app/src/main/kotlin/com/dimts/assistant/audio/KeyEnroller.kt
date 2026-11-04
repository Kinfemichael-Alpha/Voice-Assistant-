package com.dimts.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.ToneGenerator
import com.dimts.core.audio.FeatureConfig
import com.dimts.core.audio.KeyTrainer
import com.dimts.core.audio.SoundKey
import com.dimts.core.audio.TrainFailure
import com.dimts.core.audio.TrainResult
import com.dimts.core.script.Level
import com.dimts.core.session.BeepKind

/** Short tones used for feedback ("I heard you", "yes?", "error"). */
class Beeper {
    private var tone: ToneGenerator? = null

    fun play(kind: BeepKind) {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tone = it }
            when (kind) {
                BeepKind.WAKE -> t.startTone(ToneGenerator.TONE_PROP_ACK, 160)
                BeepKind.OK -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                BeepKind.ERROR -> t.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                BeepKind.ASK -> t.startTone(ToneGenerator.TONE_PROP_PROMPT, 200)
            }
        } catch (_: RuntimeException) {
            // no audio output available: feedback is optional
        }
    }

    fun release() {
        tone?.release()
        tone = null
    }
}

/**
 * Records [CLIPS] samples of the user's key sound and trains a [SoundKey] from them.
 * Runs on its own thread; every callback is delivered through [post] (the main thread).
 */
class KeyEnroller(
    private val beeper: Beeper,
    private val post: (() -> Unit) -> Unit,
    private val log: (Level, String) -> Unit,
) {
    @Volatile private var cancelled = false
    @Volatile var running = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun start(name: String, onDone: (SoundKey?) -> Unit) {
        if (running) return
        running = true
        cancelled = false
        Thread({
            var key: SoundKey? = null
            try {
                key = record(name)
            } finally {
                running = false
                post { onDone(key) }
            }
        }, "dimts-enroll").start()
    }

    private fun say(level: Level, text: String) = post { log(level, text) }

    @SuppressLint("MissingPermission")
    private fun record(name: String): SoundKey? {
        val features = FeatureConfig()
        val rate = features.sampleRate
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16384))
        } catch (e: SecurityException) {
            say(Level.ERROR, "microphone permission is missing - type: grant all")
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            say(Level.ERROR, "the microphone could not be opened")
            return null
        }
        val clips = ArrayList<ShortArray>()
        try {
            say(Level.INFO, "Recording key '$name': $CLIPS samples, ${CLIP_SECONDS} s each.")
            say(Level.INFO, "After each beep make your sound ONCE (a word, a whistle, a clap pattern).")
            rec.startRecording()
            for (i in 1..CLIPS) {
                if (cancelled) return abort()
                say(Level.INFO, "($i/$CLIPS) get ready...")
                Thread.sleep(1200)
                beeper.play(BeepKind.WAKE)
                Thread.sleep(400) // let the beep fade so it is not recorded
                say(Level.OK, "($i/$CLIPS) NOW")
                val clip = ShortArray(rate * CLIP_SECONDS)
                var got = 0
                while (got < clip.size && !cancelled) {
                    val n = rec.read(clip, got, clip.size - got)
                    if (n <= 0) {
                        say(Level.ERROR, "microphone read failed")
                        return null
                    }
                    got += n
                }
                if (cancelled) return abort()
                clips.add(clip)
                Thread.sleep(400)
            }
        } catch (e: InterruptedException) {
            return abort()
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }

        return when (val result = KeyTrainer.train(name, clips, features)) {
            is TrainResult.Success -> {
                val r = result.report
                say(
                    Level.OK,
                    "Key '$name' saved. Sound length ${r.frames.minOrNull()}-${r.frames.maxOrNull()} frames, " +
                        "match threshold %.2f. Try: start listening $name   |   test key".format(r.threshold),
                )
                result.key
            }
            is TrainResult.Failure -> {
                val which = result.clipIndex?.let { "sample ${it + 1}" } ?: "the samples"
                val why = when (result.reason) {
                    TrainFailure.TOO_FEW_CLIPS -> "not enough samples"
                    TrainFailure.NO_SOUND_IN_CLIP -> "$which was silent - speak louder or closer to the microphone"
                    TrainFailure.CLIP_TOO_SHORT -> "$which was too short"
                    TrainFailure.CLIP_TOO_LONG -> "$which was too long (keep it under 4 s)"
                    TrainFailure.INCONSISTENT -> "$which did not sound like the others - say it the same way each time"
                }
                say(Level.ERROR, "Could not make a key: $why. Run the command again.")
                null
            }
        }
    }

    private fun abort(): SoundKey? {
        say(Level.WARN, "recording cancelled")
        return null
    }

    companion object {
        const val CLIPS = 4
        const val CLIP_SECONDS = 3
    }
}
