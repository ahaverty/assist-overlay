package com.ahaverty.assist

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

private const val TAG = "AssistWhisper"
private const val RATE = 16_000

/**
 * One listening turn with Whisper. The app records the mic itself and decides when Alan has finished:
 * a real pause ([PAUSE_MS] of quiet), or letting go of the mic button in hold-to-talk. Then the whole
 * clip is transcribed on the phone. The same audio goes to Google's recognizer only to show live words;
 * Google never ends the turn.
 */
class WhisperSpeech(
    private val context: Context,
    private val model: () -> Whisper.Model,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onDone: (text: String?, error: String?) -> Unit,
    private val onTranscribing: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var recording = false
    @Volatile private var hold = false
    private var thread: Thread? = null
    private var finished = true
    @Volatile private var cancelled = false
    private val samples = ShortArrayBuilder()

    // Live words. Google's recognizer ends its own turn at every pause, so it runs in segments: when one
    // ends, its text is kept and a fresh one starts on the same audio.
    @Volatile private var previewQueue: LinkedBlockingQueue<ByteArray>? = null
    private var recognizer: SpeechRecognizer? = null
    private var committed = ""
    private var segment = ""
    private var segments = 0
    private val shown get() = "$committed $segment".trim()

    val active: Boolean get() = !finished

    /** [holdToTalk]: keep listening through any pause until [stop] (the mic button was let go). */
    @SuppressLint("MissingPermission")
    fun start(holdToTalk: Boolean = false) {
        cancel()
        finished = false
        cancelled = false
        hold = holdToTalk
        committed = ""
        segment = ""
        segments = 0
        samples.clear()
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        } catch (e: Exception) {
            return finish(null, "Couldn't open the microphone.")
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return finish(null, "Couldn't open the microphone.")
        }
        recording = true
        startPreviewSegment()
        rec.startRecording()
        thread = Thread({ capture(rec) }, "assist-mic").also { it.start() }
    }

    /** Stop listening and transcribe what was heard. */
    fun stop() {
        recording = false
    }

    fun cancel() {
        cancelled = true
        finished = true
        recording = false
        thread?.join(500)
        thread = null
        closeSegment()
    }

    private fun capture(rec: AudioRecord) {
        val chunk = ShortArray(RATE / 50) // 20 ms
        val bytes = ByteArray(chunk.size * 2)
        var noise = 0.0
        var frames = 0
        var speechMs = 0
        var silenceMs = 0
        val started = SystemClock.elapsedRealtime()
        try {
            while (recording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                samples.append(chunk, n)
                var sum = 0.0
                for (i in 0 until n) {
                    sum += chunk[i] * chunk[i].toDouble()
                    bytes[2 * i] = (chunk[i].toInt() and 0xff).toByte()
                    bytes[2 * i + 1] = (chunk[i].toInt() shr 8).toByte()
                }
                previewQueue?.offer(bytes.copyOf(n * 2))
                val rms = sqrt(sum / n)
                frames++
                if (frames <= 10) noise += rms / 10 // first 200 ms: the room's noise level
                val loud = frames > 10 && rms > maxOf(noise * 3, 350.0)
                if (loud) {
                    speechMs += 20
                    silenceMs = 0
                } else {
                    silenceMs += 20
                }
                main.post { onLevel((rms / 3000).toFloat().coerceIn(0f, 1f)) }
                val elapsed = SystemClock.elapsedRealtime() - started
                when {
                    hold -> if (elapsed > 120_000) recording = false // held: only letting go ends it
                    speechMs >= 200 && silenceMs >= PAUSE_MS -> recording = false // a real pause: finished
                    speechMs < 200 && elapsed > 8000 -> recording = false // never started
                    elapsed > 60_000 -> recording = false
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            main.post { captured(speechMs >= 200) }
        }
    }

    private fun captured(heardSpeech: Boolean) {
        val fallback = shown
        closeSegment()
        onLevel(0f)
        if (finished) return
        if (!heardSpeech && fallback.isBlank()) return finish(null, null)
        val audio = samples.toFloats()
        onTranscribing()
        scope.launch {
            val text = runCatching { Whisper.transcribe(context, model(), audio) }
                .onFailure { Log.w(TAG, "whisper failed", it) }
                .getOrNull()
            finish(text?.takeIf { it.isNotBlank() } ?: fallback.takeIf { it.isNotBlank() }, null)
        }
    }

    private fun finish(text: String?, error: String?) {
        if (cancelled) return
        finished = true
        onDone(text, error)
    }

    // ---- live words: the same audio, fed to Google's recognizer through a pipe ----------------------------------------

    private fun startPreviewSegment() {
        if (!recording || segments >= 40) return
        val component = Speech.recognizerComponent(context) ?: return
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return
        segments++
        val queue = LinkedBlockingQueue<ByteArray>(200)
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { os ->
                try {
                    while (true) {
                        val b = queue.take()
                        if (b.isEmpty()) break
                        os.write(b)
                    }
                } catch (_: Exception) {
                }
            }
        }, "assist-preview").start()
        val r = SpeechRecognizer.createSpeechRecognizer(context, component)
        recognizer = r
        fun endSegment() {
            if (recognizer !== r) return
            committed = shown
            segment = ""
            closeSegment()
            startPreviewSegment()
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                if (recognizer !== r) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    segment = it
                    onPartial(shown)
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                if (recognizer !== r) return
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { segment = it }
                endSegment()
            }

            override fun onError(error: Int) {
                Log.d(TAG, "preview error $error")
                endSegment()
            }
        })
        previewQueue = queue
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IE")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE),
        )
    }

    private fun closeSegment() {
        previewQueue?.let {
            it.clear()
            it.offer(ByteArray(0))
        }
        previewQueue = null
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    companion object {
        /** Quiet that ends a turn when not holding the button: long enough for a breath mid-sentence. */
        const val PAUSE_MS = 2000
    }
}

/** Growable 16-bit sample buffer, written by the mic thread and read once afterwards. */
private class ShortArrayBuilder {
    private var data = ShortArray(RATE * 10)
    private var size = 0

    @Synchronized fun clear() {
        size = 0
    }

    @Synchronized fun append(src: ShortArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(src, 0, data, size, n)
        size += n
    }

    @Synchronized fun toFloats() = FloatArray(size) { data[it] / 32768f }
}
