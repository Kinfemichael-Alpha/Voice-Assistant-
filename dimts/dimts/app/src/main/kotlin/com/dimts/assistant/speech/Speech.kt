package com.dimts.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Step 2, the "command writer". It is created (loaded into RAM) only when the key sound is heard
 * and destroyed when the session ends. It turns ONE spoken command into text.
 *
 * Uses Android's built-in recogniser (on-device when the phone offers it). To use your own
 * fine-tuned model (Whisper, Vosk ...), implement the same load/listen/destroy surface; the rest
 * of the app only depends on this class. See docs/TRAINING.md.
 */
class Writer(private val ctx: Context) {
    private var recognizer: SpeechRecognizer? = null

    val isLoaded: Boolean get() = recognizer != null

    /** Creates the recogniser. Must run on the main thread. Returns false if the phone has none. */
    fun load(): Boolean {
        if (recognizer != null) return true
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return false
        recognizer = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        return recognizer != null
    }

    /** Hears one utterance. Exactly one of the callbacks is invoked (main thread). */
    fun listen(languageTag: String, onText: (String) -> Unit, onNothing: () -> Unit, onFail: (String) -> Unit) {
        val r = recognizer ?: return onFail("the command writer is not loaded")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onNothing() else onText(text)
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onNothing()
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onFail("microphone permission is missing - type: grant all")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        onFail("this phone cannot recognise '$languageTag' offline - try: language en")
                    else -> onFail("speech recogniser error $error")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            onFail("could not start listening: ${e.message}")
        }
    }

    fun cancel() {
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    /** Frees the recogniser (the "unload from RAM" of step 4). */
    fun destroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    companion object {
        fun tagFor(language: String) = if (language == "am") "am-ET" else "en-US"
    }
}

/** Text-to-speech for answers and confirmations. */
class Speaker(ctx: Context, private val language: () -> String, private val handler: Handler) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayList<String>()
    private var active = 0

    var onIdle: (() -> Unit)? = null

    val isSpeaking: Boolean get() = active > 0

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            handler.post {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) = finished()

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = finished()
                    })
                    val pending = queue.toList()
                    queue.clear()
                    pending.forEach { speak(it) }
                }
            }
        }
    }

    private fun finished() {
        handler.post {
            if (active > 0) active--
            if (active == 0) onIdle?.invoke()
        }
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null) return
        if (!ready) {
            queue.add(text)
            return
        }
        val locale = if (language() == "am") Locale("am", "ET") else Locale.getDefault()
        val set = engine.setLanguage(locale)
        if (set == TextToSpeech.LANG_MISSING_DATA || set == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale.US)
        active++
        val result = engine.speak(text.take(400), TextToSpeech.QUEUE_ADD, null, "dimts-${System.nanoTime()}")
        if (result != TextToSpeech.SUCCESS) {
            active--
        }
    }

    fun stop() {
        queue.clear()
        tts?.stop()
        active = 0
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
