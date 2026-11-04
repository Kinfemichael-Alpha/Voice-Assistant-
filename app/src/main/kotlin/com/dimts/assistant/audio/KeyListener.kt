package com.dimts.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.dimts.core.audio.DetectorConfig
import com.dimts.core.audio.Detection
import com.dimts.core.audio.FeatureConfig
import com.dimts.core.audio.KeyDetector
import com.dimts.core.audio.SegmentReport
import com.dimts.core.audio.SoundKey

/**
 * Step 1 of the plan: the ONE thing that stays on. Reads 16 kHz mono audio and hands it to the
 * pure-Kotlin [KeyDetector]. No model, no network; quiet audio costs one loudness number per frame.
 *
 * Callbacks arrive on the audio thread - the caller must hop to the main thread.
 */
class KeyListener(
    private val onDetect: (Detection) -> Unit,
    private val onReport: (SegmentReport) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // checked by the caller before start()
    fun start(keys: List<SoundKey>, sensitivity: Double) {
        stop()
        if (keys.isEmpty()) {
            onError("no key to listen for")
            return
        }
        val rate = FeatureConfig().sampleRate
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, 8192)
        val detector = KeyDetector(keys, DetectorConfig(thresholdScale = sensitivity))
        detector.listener = onReport

        running = true
        thread = Thread({
            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    onError("the microphone could not be opened (is another app using it?)")
                    running = false
                    return@Thread
                }
                record.startRecording()
                val buf = ShortArray(1024)
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) {
                        onError("microphone read failed ($n)")
                        break
                    }
                    if (n > 0) detector.feed(buf, n)?.let { onDetect(it) }
                }
            } catch (e: SecurityException) {
                onError("microphone permission is missing - type: grant all")
            } catch (e: Exception) {
                onError("microphone error: ${e.message}")
            } finally {
                running = false
                try { record?.stop() } catch (_: Exception) {}
                record?.release()
            }
        }, "dimts-key-listener").also { it.start() }
    }

    fun stop() {
        running = false
        val t = thread ?: return
        thread = null
        if (t !== Thread.currentThread()) {
            try { t.join(800) } catch (_: InterruptedException) {}
        }
    }
}
