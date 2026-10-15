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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

private const val TAG = "AssistWhisper"
private const val RATE = 16_000
private const val LIVE = "AssistLive"

/**
 * One listening turn with Whisper. The app records the mic itself and decides when Alan has finished:
 * a real pause ([PAUSE_MS] of quiet), or letting go of the mic button in hold-to-talk. Then the whole
 * clip is transcribed on the phone. The same audio goes to Google's recognizer only to show live words;
 * Google never ends the turn. At each short breath (or every few seconds of unbroken talk, cut at the last
 * gap between words) the clip so far is also transcribed by Whisper ("drafts"), so the shown words settle to
 * what will be sent while Alan is still talking, with only the newest words Google's. Once a draft ending in
 * quiet covers [SETTLE_S] seconds, its text is settled: later drafts and the final transcription only redo the
 * audio after it (with the settled text as Whisper's prompt), so they stay quick however long Alan talks.
 * When the turn ends with no speech after the last draft, that draft is sent as it is.
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
    private var segBase = "" // [committed] when this recognizer session started
    private var segHeld = "" // words this session said before it restarted mid-session (see onPartialResults)
    private val shown get() = LiveWords.merge(committed, segment)

    // Whisper drafts (see the class comment). [turn] keeps a late draft from an old turn quiet.
    private var turn = 0
    private var draftJob: Job? = null
    private var draftSnap = 0 // samples the running (or last) draft was given
    private var draft = ""
    private var draftDone = 0 // samples [draft] covers
    private var draftGoogle = "" // Google's words when the draft's audio was taken
    private var settled = "" // Whisper's text for samples [0, settledAt): never transcribed again
    private var settledAt = 0
    private val heard = ArrayList<Pair<Int, String>>() // (samples, shown) at each Google update
    @Volatile private var lastSpeechSample = 0
    @Volatile private var lastGapSample = 0 // middle of the newest short gap between words
    private var t0 = 0L
    private var replaying = false

    /** Everything the live words do, for replaying a turn over adb (logcat tag AssistLive). */
    private fun live(msg: String) = Log.d(LIVE, "+${SystemClock.elapsedRealtime() - t0} $msg")

    private fun show() {
        heard += samples.count to shown
        val d = display()
        live("show: $d")
        onPartial(d)
    }

    /** Whisper's draft, then whatever Google has heard since it was taken (minus any words both have). */
    private fun display(): String {
        if (draft.isEmpty()) return shown
        LiveWords.tailAfter(shown, draft, draftGoogle)?.let { return LiveWords.merge(draft, it) }
        return LiveWords.merge(draft, LiveWords.after(shown, draftGoogle))
    }

    val active: Boolean get() = !finished

    /**
     * [holdToTalk]: keep listening through any pause until [stop] (the mic button was let go).
     * [replay]: play these samples in real time instead of the mic (adb testing, see [ReplayReceiver]).
     */
    @SuppressLint("MissingPermission")
    fun start(holdToTalk: Boolean = false, replay: ShortArray? = null) {
        cancel()
        finished = false
        cancelled = false
        hold = holdToTalk
        committed = ""
        segment = ""
        segments = 0
        samples.clear()
        draft = ""
        draftDone = 0
        draftSnap = 0
        draftGoogle = ""
        settled = ""
        settledAt = 0
        heard.clear()
        lastSpeechSample = 0
        lastGapSample = 0
        t0 = SystemClock.elapsedRealtime()
        replaying = replay != null
        if (replay != null) {
            recording = true
            startPreviewSegment()
            var pos = 0
            val read = { buf: ShortArray ->
                SystemClock.sleep(20)
                // Then quiet, as a mic would hear, until the pause ends the turn.
                val n = minOf(buf.size, replay.size - pos).coerceAtLeast(0)
                System.arraycopy(replay, pos, buf, 0, n)
                buf.fill(0, n, buf.size)
                pos += n
                buf.size
            }
            thread = Thread({ capture(read) {} }, "assist-replay").also { it.start() }
            return
        }
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
        thread = Thread({ capture({ rec.read(it, 0, it.size) }) { runCatching { rec.stop() }; rec.release() } }, "assist-mic").also { it.start() }
    }

    /** Stop listening and transcribe what was heard. */
    fun stop() {
        recording = false
    }

    fun cancel() {
        cancelled = true
        finished = true
        turn++
        draftJob?.cancel()
        draftJob = null
        recording = false
        thread?.join(500)
        thread = null
        closeSegment()
    }

    private fun capture(read: (ShortArray) -> Int, close: () -> Unit) {
        val chunk = ShortArray(RATE / 50) // 20 ms
        val bytes = ByteArray(chunk.size * 2)
        var noise = 300.0
        var speechMs = 0
        var silenceMs = 0
        val started = SystemClock.elapsedRealtime()
        var lastDraftAt = started
        try {
            while (recording) {
                val n = read(chunk)
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
                // The room's quiet level, learnt as it goes: it drops quickly to any quieter moment and
                // creeps up only slowly, so talking from the very first moment can't be mistaken for it.
                noise = if (rms < noise) noise * 0.8 + rms * 0.2 else minOf(noise * 1.003, 2500.0)
                val loud = rms > maxOf(noise * 2.2, 260.0)
                if (loud) {
                    speechMs += 20
                    silenceMs = 0
                    lastSpeechSample = samples.count
                } else {
                    silenceMs += 20
                    if (silenceMs == GAP_MS) lastGapSample = samples.count - RATE * GAP_MS / 2000
                }
                main.post { onLevel((rms / 3000).toFloat().coerceIn(0f, 1f)) }
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - started
                // A breath: draft the words so far. A long run of talk with no breath in it: draft up to the
                // last gap between words, so no word is cut in half.
                if (speechMs >= 200 && silenceMs == DRAFT_PAUSE_MS) {
                    lastDraftAt = now
                    val end = samples.count
                    main.post { draftNow(end, quiet = true) }
                } else if (speechMs >= 200 && loud && now - lastDraftAt > DRAFT_EVERY_MS) {
                    lastDraftAt = now
                    val gap = lastGapSample
                    val end = samples.count
                    main.post { if (gap > end - RATE * 3 && gap > draftSnap) draftNow(gap, quiet = true) else draftNow(end, quiet = false) }
                }
                when {
                    hold -> if (elapsed > 120_000) recording = false // held: only letting go ends it
                    speechMs >= 200 && silenceMs >= PAUSE_MS -> recording = false // a real pause: finished
                    speechMs < 200 && elapsed > 8000 -> recording = false // never started
                    elapsed > 60_000 -> recording = false
                }
            }
        } finally {
            close()
            main.post { captured(speechMs >= 200) }
        }
    }

    /**
     * Transcribe the audio up to [end] in the background and show it as settled words (one draft at a time).
     * [quiet]: the audio ends between words, so the draft may become [settled].
     */
    private fun draftNow(end: Int, quiet: Boolean) {
        if (finished || !recording || draftJob?.isActive == true) return
        if (lastSpeechSample <= draftSnap || end <= draftSnap) return // nothing said since the last draft
        val from = settledAt
        if (end - from < RATE) return
        val audio = samples.toFloats(from, end)
        val t = turn
        draftSnap = end
        // Google's words when it had heard about this much (it can be a word behind: display() drops repeats).
        val googleThen = googleAt(end)
        val before = settled
        live("draft start ${from / RATE.toFloat()}-${end / RATE.toFloat()} s")
        draftJob = scope.launch {
            val text = runCatching { transcribeChecked(audio, from, end, before) }
                .onFailure { Log.w(TAG, "draft failed", it) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
            if (t != turn || finished) return@launch
            draft = join(before, text)
            draftDone = end
            draftGoogle = googleThen
            if ((quiet && end - from >= SETTLE_S * RATE) || end - from >= MAX_CHUNK_S * RATE) {
                settled = draft
                settledAt = end
            }
            show()
        }
    }

    /**
     * Whisper on samples [from, end), checked: it sometimes stops early (a few words, then nothing), so text
     * well short of what the last draft or Google already have for this audio is tried again without the
     * earlier words as its prompt, and dropped (null) if it is still short.
     */
    private suspend fun transcribeChecked(audio: FloatArray, from: Int, end: Int, before: String): String? {
        val drafted = words(draft) - words(before)
        val google = words(googleAt(end)) - words(googleAt(from))
        fun short(text: String) = words(text).let { it < drafted - 3 || it < google * 0.6 - 2 }
        val text = Whisper.transcribe(context, model(), audio, previous = before)
        live("draft: $text")
        if (!short(text)) return text
        val again = Whisper.transcribe(context, model(), audio)
        live("too short (drafted $drafted, Google $google), again: $again")
        return again.takeUnless { short(it) }
    }

    private fun words(s: String) = s.split(' ').count { it.isNotBlank() }

    /** What Google showed when it had been given [end] samples. */
    private fun googleAt(end: Int) = heard.lastOrNull { it.first <= end }?.second ?: ""

    private fun captured(heardSpeech: Boolean) {
        val fallback = display()
        closeSegment()
        onLevel(0f)
        if (finished) return
        if (!heardSpeech && fallback.isBlank()) return finish(null, null)
        if (!replaying) keepTurn(samples.toShorts())
        val t = turn
        onTranscribing()
        scope.launch {
            // Drafts are short now: let the running one finish, as it may settle more of the clip.
            draftJob?.join()
            if (t != turn) return@launch
            // The last draft already heard everything said (only the closing quiet came after it): use it.
            if (draft.isNotBlank() && draftDone >= lastSpeechSample) return@launch finish(draft, null)
            // The closing quiet is left out: Whisper makes up words ("Thanks for watching.") in silence.
            val end = minOf(samples.count, lastSpeechSample + RATE / 2)
            val before = settled
            val text = if (end - settledAt < RATE / 4) "" else runCatching {
                transcribeChecked(samples.toFloats(settledAt, end), settledAt, end, before)
            }.onFailure { Log.w(TAG, "whisper failed", it) }.getOrNull()
            if (t != turn) return@launch
            val said = if (text == null) null else join(before, text)
            finish(said?.takeIf { it.isNotBlank() } ?: fallback.takeIf { it.isNotBlank() }, null)
        }
    }

    private fun finish(text: String?, error: String?) {
        if (cancelled) return
        live("done: $text")
        finished = true
        onDone(text, error)
    }

    /** The last few turns' audio stay on the phone (files/turns), so a bad one can be replayed over adb. */
    private fun keepTurn(pcm: ShortArray) = scope.launch(Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(context.getExternalFilesDir(null), "turns").apply { mkdirs() }
            val name = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            writeWav(java.io.File(dir, "$name.wav"), pcm)
            dir.listFiles()!!.sortedByDescending { it.name }.drop(KEEP_TURNS).forEach { it.delete() }
        }.onFailure { Log.w(TAG, "couldn't keep the turn", it) }
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
        segBase = committed
        segHeld = ""
        val began = SystemClock.elapsedRealtime()
        val t = turn
        fun endSegment(final: String?) {
            if (recognizer !== r) return
            val last = final ?: segment
            // A final that repeats the words held at a mid-session restart replaces them, rather than following them.
            committed = if (segHeld.isNotEmpty() && LiveWords.startsLike(last, segHeld)) LiveWords.merge(segBase, last)
            else LiveWords.merge(committed, last)
            val failedAtOnce = final == null && segment.isEmpty() && SystemClock.elapsedRealtime() - began < 1000
            segment = ""
            closeSegment()
            // A recognizer that fails straight away (busy, not ready) is tried again after a beat, not 40 times at once.
            if (failedAtOnce) main.postDelayed({ if (t == turn && recognizer == null) startPreviewSegment() }, 300)
            else startPreviewSegment()
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
                    live("g$segments partial: $it")
                    // After a pause Google sometimes starts a new utterance in the same session without
                    // ending the old one: keep the old words instead of letting the new ones replace them.
                    // If it then goes back to the whole utterance, that was only a revision: un-keep them.
                    if (segHeld.isNotEmpty() && LiveWords.startsLike(it, segHeld) &&
                        it.split(' ').count { w -> w.isNotBlank() } >= segHeld.split(' ').size
                    ) {
                        committed = segBase
                        segHeld = ""
                    } else if (restarted(segment, it)) {
                        segHeld = LiveWords.merge(segHeld, segment)
                        committed = LiveWords.merge(committed, segment)
                    }
                    segment = it
                    show()
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                if (recognizer !== r) return
                live("g$segments final: ${results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()}")
                endSegment(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() })
            }

            override fun onError(error: Int) {
                live("g$segments error $error")
                endSegment(null)
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
        const val KEEP_TURNS = 8

        fun writeWav(file: java.io.File, pcm: ShortArray) {
            val b = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(pcm.size * 2)
            pcm.forEach { b.putShort(it) }
            file.writeBytes(b.array())
        }

        /** 16 kHz mono 16-bit WAV (a plain 44-byte header). */
        fun readWav(file: java.io.File): ShortArray {
            val bytes = file.readBytes()
            val b = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return ShortArray((bytes.size - 44) / 2) { b.short }
        }

        /** Quiet that ends a turn when not holding the button: long enough for a breath mid-sentence. */
        const val PAUSE_MS = 2200

        /** Quiet that starts a Whisper draft (multiples of the 20 ms mic chunk), and a gap between words. */
        const val DRAFT_PAUSE_MS = 500
        const val GAP_MS = 140

        /** Unbroken talk is drafted this often. */
        const val DRAFT_EVERY_MS = 4000

        /** A draft ending in quiet that covers this many seconds is settled; any draft at [MAX_CHUNK_S]. */
        const val SETTLE_S = 8
        const val MAX_CHUNK_S = 20

        /** [before] then [text], Whisper's settled words and the ones after them. */
        fun join(before: String, text: String) = if (before.isEmpty()) text.trim() else "$before ${text.trim()}".trim()

        /**
         * True when [next] is a fresh utterance, not a revision of [old]: Google started again after a pause in
         * the same session. It begins with other words and is no longer than [old]. (Revisions keep the start;
         * a fresh utterance can be long at once when Google was quiet for a while, so length alone won't do.)
         */
        fun restarted(old: String, next: String): Boolean {
            val a = old.split(' ').filter { it.isNotBlank() }
            val b = next.split(' ').filter { it.isNotBlank() }
            return a.size >= 3 && b.isNotEmpty() && b.size <= a.size && !LiveWords.startsLike(next, old)
        }
    }
}

/** Growable 16-bit sample buffer, written by the mic thread and read once afterwards. */
private class ShortArrayBuilder {
    private var data = ShortArray(RATE * 10)
    private var size = 0

    val count: Int @Synchronized get() = size

    @Synchronized fun clear() {
        size = 0
    }

    @Synchronized fun append(src: ShortArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(src, 0, data, size, n)
        size += n
    }

    @Synchronized fun toFloats(from: Int = 0, to: Int = size): FloatArray {
        val end = minOf(to, size)
        return FloatArray(maxOf(0, end - from)) { data[from + it] / 32768f }
    }

    @Synchronized fun toShorts() = data.copyOf(size)
}

/**
 * `adb shell am broadcast -n com.ahaverty.assist/.ReplayReceiver --es file turns/<name>.wav` plays a recorded
 * turn (or any 16 kHz mono WAV in the app's files) through the live words in real time, with no mic and no
 * question sent. Every Google partial, Whisper draft and shown text goes to logcat (tag AssistLive).
 */
class ReplayReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val file = java.io.File(context.getExternalFilesDir(null), intent.getStringExtra("file") ?: "replay.wav")
        val pcm = runCatching { WhisperSpeech.readWav(file) }.getOrNull()
            ?: return run { Log.w(LIVE, "can't read $file") }
        Log.d(LIVE, "replay ${file.name}: ${pcm.size / RATE.toFloat()} s")
        running?.cancel()
        running = WhisperSpeech(
            context.applicationContext,
            model = { Whisper.Model.SMALL },
            onPartial = {},
            onLevel = {},
            onDone = { text, error -> Log.d(LIVE, "replay done: $text ${error.orEmpty()}") },
        ).also { it.start(holdToTalk = intent.getBooleanExtra("hold", false), replay = pcm) }
    }

    companion object {
        private var running: WhisperSpeech? = null
    }
}
