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
