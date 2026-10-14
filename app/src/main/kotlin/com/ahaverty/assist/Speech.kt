package com.ahaverty.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One listening turn on the phone's own speech recognizer (live words while Alan talks).
 *
 * The mic is owned only between start() and the first of: final result, error, stop(), cancel() or the
 * hard cap. Every path ends in release(), so a turn can never leave the mic open for the next one.
 * Being the default assistant points Android's default recognizer at our stub, so we name Google's
 * recognizer explicitly instead of using createSpeechRecognizer(context).
 */
class Speech(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onDone: (text: String?, error: String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var lastPartial = ""
    private var finished = false
    private val cap = Runnable { stop() }

    val active: Boolean get() = recognizer != null

    fun start() {
        release()
        finished = false
        lastPartial = ""
        val component = recognizerComponent(context)
        val r = if (component != null) SpeechRecognizer.createSpeechRecognizer(context, component)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    lastPartial = text
                    onPartial(text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish(text?.takeIf { it.isNotBlank() } ?: lastPartial.takeIf { it.isNotBlank() }, null)
            }

            override fun onError(error: Int) {
                // A stop() can surface as an error after words were heard: keep what we have.
                if (lastPartial.isNotBlank()) return finish(lastPartial, null)
                finish(null, when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off. Open the Assist app to allow it."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Try again."
                    else -> "Couldn't listen (error $error). Try again."
                })
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IE")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        main.postDelayed(cap, 30_000)
        r.startListening(intent)
    }

    /** Stop listening and use what was heard so far. */
    fun stop() {
        val r = recognizer ?: return
        r.stopListening()
        // Some recognizers never answer a stop; don't wait on them.
        main.postDelayed({ if (recognizer === r) finish(lastPartial.takeIf { it.isNotBlank() }, null) }, 1500)
    }

    /** Drop the turn without sending anything. */
    fun cancel() {
        finished = true
        release()
    }

    private fun finish(text: String?, error: String?) {
        if (finished) return
        finished = true
        release()
        onLevel(0f)
        onDone(text, error)
    }

    private fun release() {
        main.removeCallbacks(cap)
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    companion object {
        private val PREFERRED = listOf(
            "com.google.android.tts",
            "com.google.android.googlequicksearchbox",
            "com.google.android.as",
        )

        fun recognizerComponent(context: Context): ComponentName? {
            val found = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
                .filter { it.packageName != context.packageName }
            return PREFERRED.firstNotNullOfOrNull { pkg -> found.firstOrNull { it.packageName == pkg } } ?: found.firstOrNull()
        }

        fun recognizerLabel(context: Context): String {
            val c = recognizerComponent(context) ?: return "none found"
            return runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString()
            }.getOrDefault(c.packageName)
        }
    }
}
