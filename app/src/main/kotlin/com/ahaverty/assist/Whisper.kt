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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.sqrt

private const val TAG = "AssistWhisper"
private const val RATE = 16_000

/** On-phone Whisper (whisper.cpp through JNI). One model stays loaded while the app process lives. */
@OptIn(ExperimentalCoroutinesApi::class)
object Whisper {
    /**
     * Measured on Alan's S23 Ultra (14 Oct, 10 s clip): small.en q8_0 1.4 s, small.en q5_1 4.0 s (q5 unpacking
     * is slow on ARM), base.en q8_0 0.6 s but less accurate, large-v3-turbo q5_0 over a minute. So: small q8_0.
     */
    enum class Model(val file: String, val label: String, val mb: Int) {
        SMALL("ggml-small.en-q8_0.bin", "Whisper", 264),
    }

    /** Names Whisper would otherwise mishear; kept short so it can't leak into silent clips. */
    private const val PROMPT = "Friday."
    private const val BASE = "https://3d.ahaverty.com/claude/assist-app/models"

    private val worker = Dispatchers.Default.limitedParallelism(1)
    private var loaded: Pair<Model, Long>? = null

    init {
        System.loadLibrary("assistwhisper")
    }

    @JvmStatic private external fun nativeInit(path: String): Long
    @JvmStatic private external fun nativeFree(ctx: Long)
    @JvmStatic private external fun nativeTranscribe(ctx: Long, samples: FloatArray, threads: Int, prompt: String?, audioCtx: Int): String

    /** Encoder frames for a clip: its own length plus a margin (0 = Whisper's full 30 s window). */
    fun audioCtxFor(samples: Int) = minOf(1500, samples / 320 * 6 / 5 + 128)

    fun file(context: Context, m: Model) = File(context.getExternalFilesDir("models"), m.file)
    fun ready(context: Context, m: Model) = file(context, m).let { it.exists() && it.length() > m.mb * 900_000L }

    /** Load ahead of time (a second or two for turbo) so the first question isn't slower. */
    suspend fun preload(context: Context, m: Model) = withContext(worker) { ensure(context, m) }

    private fun ensure(context: Context, m: Model): Long {
        loaded?.let { (lm, ctx) -> if (lm == m) return ctx else nativeFree(ctx) }
        loaded = null
        val t = SystemClock.elapsedRealtime()
        val ctx = nativeInit(file(context, m).path)
        if (ctx == 0L) throw IOException("couldn't load ${m.label}")
        Log.i(TAG, "${m.label} loaded in ${SystemClock.elapsedRealtime() - t} ms")
        loaded = m to ctx
        return ctx
    }

    suspend fun transcribe(
        context: Context,
        m: Model,
        samples: FloatArray,
        threads: Int = 4,
        audioCtx: Int = audioCtxFor(samples.size),
    ): String = withContext(worker) {
        val ctx = ensure(context, m)
        val t = SystemClock.elapsedRealtime()
        val raw = nativeTranscribe(ctx, samples, threads, PROMPT, audioCtx)
        val ms = SystemClock.elapsedRealtime() - t
        Log.i(TAG, "${m.label}: ${samples.size / RATE.toFloat()} s audio in $ms ms: \"$raw\"")
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .putString("whisper_last_${m.name}", "last: %.1f s to transcribe %.1f s of speech".format(ms / 1000f, samples.size / RATE.toFloat()))
            .apply()
        clean(raw)
    }

    /** Whisper's non-speech markers ([BLANK_AUDIO], (music)) and stray whitespace. */
    fun clean(text: String) = text.replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ").replace(Regex("\\s+"), " ").trim()

    /** Download a model from the PC (home Wi-Fi or WireGuard). [progress] gets 0..1. */
    suspend fun download(context: Context, m: Model, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dest = file(context, m)
        val part = File(dest.path + ".part")
        App.auth.http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url("$BASE/${m.file}").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("download failed (${r.code})")
                val body = r.body!!
                val total = body.contentLength().coerceAtLeast(1)
                var done = 0L
                var lastShown = 0f
                body.byteStream().use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val f = done.toFloat() / total
                            if (f - lastShown > 0.01f) {
                                lastShown = f
                                withContext(Dispatchers.Main) { progress(f) }
                            }
                        }
                    }
                }
            }
        if (!part.renameTo(dest)) throw IOException("couldn't save ${m.label}")
    }

    /** Times every .bin model in the models folder on a clip (16 kHz mono 16-bit WAV); one line each. */
    suspend fun benchmark(context: Context, wav: File): String = withContext(worker) {
        val pcm = wav.readBytes().let { it.copyOfRange(44, it.size) }
        val samples = FloatArray(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        loaded?.let { nativeFree(it.second) }
        loaded = null
        val lines = mutableListOf<String>()
        for (f in context.getExternalFilesDir("models")!!.listFiles { x -> x.name.endsWith(".bin") }!!.sortedBy { it.length() }) {
            val ctx = nativeInit(f.path)
            if (ctx == 0L) continue
            for (threads in listOf(3, 4)) {
                nativeTranscribe(ctx, samples.copyOf(RATE), threads, PROMPT, audioCtxFor(RATE)) // warm-up
                val t = SystemClock.elapsedRealtime()
                val text = clean(nativeTranscribe(ctx, samples, threads, PROMPT, audioCtxFor(samples.size)))
                lines += "${f.name} threads=$threads: ${SystemClock.elapsedRealtime() - t} ms for " +
                    "${"%.1f".format(samples.size / RATE.toFloat())} s: $text"
            }
            nativeFree(ctx)
        }
        lines.joinToString("\n").also { Log.i(TAG, "benchmark\n$it") }
    }
}

/**
 * One listening turn with Whisper. The app records the mic itself, decides when Alan has finished
 * (Google's end-of-speech when it is available, a simple loudness check otherwise), then transcribes
 * the whole clip on the phone. The same audio is fed to Google's recognizer only to show live words.
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
    private var thread: Thread? = null
    private var recognizer: SpeechRecognizer? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private var lastPartial = ""
    private var finished = true
    @Volatile private var cancelled = false
    private val samples = ShortArrayBuilder()

    val active: Boolean get() = !finished

    @SuppressLint("MissingPermission")
    fun start() {
        cancel()
        finished = false
        cancelled = false
        lastPartial = ""
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
        val p = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
        pipe = p
        if (p != null) startPreview(p[0])
        recording = true
        rec.startRecording()
        thread = Thread({ capture(rec, p?.get(1)) }, "assist-mic").also { it.start() }
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
        stopPreview()
    }

    private fun capture(rec: AudioRecord, sink: ParcelFileDescriptor?) {
        val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(200)
        val writer = sink?.let { fd ->
            Thread({
                ParcelFileDescriptor.AutoCloseOutputStream(fd).use { os ->
                    try {
                        while (true) {
                            val b = queue.take()
                            if (b.isEmpty()) break
                            os.write(b)
                        }
                    } catch (_: Exception) {
                    }
                }
            }, "assist-preview").also { it.start() }
        }
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
                if (writer != null) queue.offer(bytes.copyOf(n * 2))
                val rms = sqrt(sum / n)
                frames++
                if (frames <= 10) noise += rms / 10 // first 200 ms: the room's noise level
                val loud = frames > 10 && rms > maxOf(noise * 3, 350.0)
                if (loud) { speechMs += 20; silenceMs = 0 } else silenceMs += 20
                main.post { onLevel((rms / 3000).toFloat().coerceIn(0f, 1f)) }
                val elapsed = SystemClock.elapsedRealtime() - started
                when {
                    speechMs >= 200 && silenceMs >= 1400 -> recording = false // finished speaking
                    speechMs < 200 && elapsed > 7000 -> recording = false // never started
                    elapsed > 30_000 -> recording = false
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            if (writer != null) { queue.clear(); queue.offer(ByteArray(0)) }
            main.post { captured(speechMs >= 200) }
        }
    }

    private fun captured(heardSpeech: Boolean) {
        stopPreview()
        onLevel(0f)
        if (finished) return
        if (!heardSpeech && lastPartial.isBlank()) return finish(null, null)
        val audio = samples.toFloats()
        val fallback = lastPartial
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

    private fun startPreview(read: ParcelFileDescriptor) {
        val component = Speech.recognizerComponent(context) ?: return
        val r = SpeechRecognizer.createSpeechRecognizer(context, component)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "preview: end of speech")
                recording = false
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    lastPartial = it
                    onPartial(it)
                }
            }
            override fun onResults(results: android.os.Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial = it }
            }
            override fun onError(error: Int) { Log.d(TAG, "preview error $error") }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IE")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L),
        )
    }

    private fun stopPreview() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        pipe?.forEach { runCatching { it.close() } }
        pipe = null
    }
}

/** Growable 16-bit sample buffer, written by the mic thread and read once afterwards. */
private class ShortArrayBuilder {
    private var data = ShortArray(RATE * 10)
    private var size = 0

    @Synchronized fun clear() { size = 0 }

    @Synchronized fun append(src: ShortArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(src, 0, data, size, n)
        size += n
    }

    @Synchronized fun toFloats() = FloatArray(size) { data[it] / 32768f }
}

/**
 * `adb shell am broadcast -n com.ahaverty.assist/.BenchReceiver` times the models on files/bench.wav in
 * the background (results in logcat, tag AssistWhisper). Only the shell can send it (DUMP permission).
 */
class BenchReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { Whisper.benchmark(context, File(context.getExternalFilesDir(null), "bench.wav")) }
                .onFailure { Log.w(TAG, "benchmark failed", it) }
            pending.finish()
        }
    }
}
