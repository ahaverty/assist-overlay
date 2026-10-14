package com.ahaverty.assist

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

data class Msg(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val error: Boolean = false,
)

enum class Phase { Idle, Listening, Thinking, Speaking }

data class UiState(
    val messages: List<Msg> = emptyList(),
    val phase: Phase = Phase.Idle,
    val partial: String = "",
    val level: Float = 0f,
    val speakReplies: Boolean = true,
    val notice: String? = null,
)

/**
 * The one conversation, kept in the app process so it survives the overlay being hidden
 * (home, recents, opening another app) for [Config.KEEP_MS]. Only "New chat" or expiry clears it.
 */
private const val TAG = "AssistConvo"

class Conversation(private val app: Context, private val ha: Ha) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(speakReplies = prefs.getBoolean("speak", true)))
    val state: StateFlow<UiState> = _state

    private var conversationId: String? = null
    private var lastActivity = 0L
    private var runJob: Job? = null
    private var player: ExoPlayer? = null
    private var visible = false
    private var lastTurnWasVoice = false
    private var continueConversation = false
    private var nextId = 1L

    private val speech = Speech(
        app,
        onPartial = { p -> _state.update { it.copy(partial = p) } },
        onLevel = { l -> _state.update { it.copy(level = l) } },
        onDone = { text, error -> onHeard(text, error) },
    )

    private val busy get() = runJob?.isActive == true

    // ---- overlay lifecycle --------------------------------------------------------------------------------------------

    fun onShown() {
        visible = true
        cancelReopenNotice()
        if (System.currentTimeMillis() - lastActivity > Config.KEEP_MS) reset()
        when {
            !App.auth.loggedIn -> notice("Open the Assist app and sign in to Home Assistant first.")
            !hasMic() -> notice("Open the Assist app to allow the microphone.")
            _state.value.phase == Phase.Idle -> listen()
        }
    }

    fun onHidden() {
        visible = false
        if (speech.active) {
            speech.cancel()
            _state.update { it.copy(phase = if (busy) Phase.Thinking else Phase.Idle, partial = "", level = 0f) }
        }
        if (_state.value.messages.isNotEmpty()) postReopenNotice()
    }

    /** The close button: stop talking and listening, keep the conversation for later. */
    fun close() {
        speech.cancel()
        stopAudio()
        _state.update { it.copy(phase = if (busy) Phase.Thinking else Phase.Idle, partial = "", level = 0f) }
    }

    fun newChat() {
        reset()
        if (visible && App.auth.loggedIn && hasMic()) listen()
    }

    private fun reset() {
        speech.cancel()
        stopAudio()
        runJob?.cancel()
        runJob = null
        conversationId = null
        continueConversation = false
        _state.update { UiState(speakReplies = it.speakReplies) }
        cancelReopenNotice()
    }

    // ---- input --------------------------------------------------------------------------------------------------------

    fun micPressed() {
        when (_state.value.phase) {
            Phase.Listening -> speech.stop()
            Phase.Speaking -> {
                stopAudio()
                listen()
            }
            else -> listen()
        }
    }

    fun toggleSpeak() {
        val on = !_state.value.speakReplies
        prefs.edit().putBoolean("speak", on).apply()
        if (!on) stopAudio()
        _state.update { it.copy(speakReplies = on) }
    }

    fun sendTyped(text: String) {
        if (text.isBlank()) return
        speech.cancel()
        stopAudio()
        send(text.trim(), voice = false)
    }

    /** Typing started: drop the listening turn so the mic isn't open behind the keyboard. */
    fun typingStarted() {
        if (speech.active) {
            speech.cancel()
            _state.update { it.copy(phase = if (busy) Phase.Thinking else Phase.Idle, partial = "", level = 0f) }
        }
    }

    private fun listen() {
        if (!hasMic()) return notice("Open the Assist app to allow the microphone.")
        stopAudio()
        _state.update { it.copy(phase = Phase.Listening, partial = "", notice = null) }
        speech.start()
    }

    private fun onHeard(text: String?, error: String?) {
        _state.update { it.copy(partial = "", level = 0f, phase = if (busy) Phase.Thinking else Phase.Idle) }
        when {
            text != null -> send(text, voice = true)
            error != null -> notice(error)
        }
    }

    // ---- a turn -------------------------------------------------------------------------------------------------------

    private fun send(text: String, voice: Boolean) {
        runJob?.cancel()
        lastActivity = System.currentTimeMillis()
        lastTurnWasVoice = voice
        continueConversation = false
        val replyId = nextId + 1
        _state.update {
            it.copy(
                messages = it.messages.map { m -> if (m.streaming) m.copy(streaming = false) else m } +
                    Msg(nextId, true, text) + Msg(replyId, false, "", streaming = true),
                phase = Phase.Thinking,
                notice = null,
            )
        }
        nextId += 2
        val withTts = voice && _state.value.speakReplies
        runJob = scope.launch {
            val me = coroutineContext.job
            try {
                ha.run(text, conversationId, withTts).collect { ev ->
                    when (ev) {
                        is PipeEvent.Delta -> edit(replyId) { it.copy(text = it.text + ev.text) }
                        is PipeEvent.IntentEnd -> {
                            conversationId = ev.conversationId ?: conversationId
                            continueConversation = ev.continueConversation
                            edit(replyId) { if (it.text.isBlank()) it.copy(text = ev.speech) else it }
                        }
                        is PipeEvent.Tts -> if (_state.value.speakReplies) play(ev.url)
                        is PipeEvent.Failed -> edit(replyId) {
                            it.copy(text = (it.text.trimEnd() + "\n\n" + ev.message).trim(), error = true)
                        }
                    }
                }
            } catch (e: NotLoggedIn) {
                edit(replyId) { it.copy(text = "Not signed in. Open the Assist app to sign in to Home Assistant.", error = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                edit(replyId) { it.copy(text = (it.text.trimEnd() + "\n\nCouldn't reach Home Assistant ($why).").trim(), error = true) }
            } finally {
                edit(replyId) { it.copy(streaming = false) }
                lastActivity = System.currentTimeMillis()
                if (runJob === me) {
                    runJob = null
                    _state.update { if (it.phase == Phase.Thinking) it.copy(phase = if (player != null) Phase.Speaking else Phase.Idle) else it }
                    if (!visible && _state.value.messages.isNotEmpty()) postReopenNotice()
                    if (player == null) maybeContinue()
                }
            }
        }
    }

    private fun edit(id: Long, f: (Msg) -> Msg) = _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) f(it) else it }) }

    private fun maybeContinue() {
        if (continueConversation && lastTurnWasVoice && visible && _state.value.phase == Phase.Idle) {
            continueConversation = false
            listen()
        }
    }

    private fun notice(text: String) = _state.update { it.copy(notice = text, phase = if (it.phase == Phase.Listening) Phase.Idle else it.phase) }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ---- reply audio --------------------------------------------------------------------------------------------------

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    /** Reply audio must never break the text reply, so any player problem is logged and skipped. */
    private fun play(url: String) = runCatching { startPlayer(url) }.onFailure {
        Log.w(TAG, "Reply audio failed", it)
        stopAudio()
    }

    private fun startPlayer(url: String) {
        stopAudio()
        val p = ExoPlayer.Builder(app).build()
        // ExoPlayer only manages focus itself for media/game usage; ducking other audio is done by hand.
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_ASSISTANT).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            false,
        )
        audioManager.requestAudioFocus(focusRequest)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) audioFinished(p)
            }

            override fun onPlayerError(error: PlaybackException) = audioFinished(p)
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
        player = p
        _state.update { if (it.phase == Phase.Idle) it.copy(phase = Phase.Speaking) else it }
    }

    private fun audioFinished(p: ExoPlayer) {
        if (player !== p) return
        p.release()
        player = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (!busy) {
            _state.update { if (it.phase == Phase.Speaking) it.copy(phase = Phase.Idle) else it }
            maybeContinue()
        }
    }

    private fun stopAudio() {
        player?.let {
            player = null
            it.stop()
            it.release()
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        _state.update { if (it.phase == Phase.Speaking) it.copy(phase = Phase.Idle) else it }
    }

    // ---- hand over to Claude ------------------------------------------------------------------------------------------

    fun transcript(): String {
        val lines = _state.value.messages.filter { it.text.isNotBlank() }.joinToString("\n\n") { m ->
            (if (m.fromUser) "Me: " else "${Config.ASSISTANT_NAME}: ") + m.text.trim()
        }
        return "Carrying on from my Home Assistant voice assistant (${Config.ASSISTANT_NAME}). " +
            "Here is the conversation so far:\n\n$lines\n\n"
    }

    /** Opens Claude with the conversation prefilled (share intent), else copies it and opens Claude. Returns a message to show, if any. */
    fun openInClaude(context: Context): String? {
        val text = transcript()
        close()
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .setPackage(Config.CLAUDE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (share.resolveActivity(context.packageManager) != null) {
            return runCatching { context.startActivity(share); null }.getOrElse { "Couldn't open Claude: ${it.message}" }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Conversation", text))
        val launch = context.packageManager.getLaunchIntentForPackage(Config.CLAUDE_PACKAGE)
            ?: return "Claude isn't installed. The conversation is on the clipboard."
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    // ---- "go back to it" notification ---------------------------------------------------------------------------------

    private fun postReopenNotice() {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val last = _state.value.messages.lastOrNull { !it.fromUser && it.text.isNotBlank() }
        val body = when {
            busy -> "Still working on it…"
            last != null -> last.text.trim().lines().last { it.isNotBlank() }
            else -> "Tap to go back to the conversation"
        }
        val tap = PendingIntent.getBroadcast(
            app, 0, Intent(app, ReopenReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(app, Config.CHANNEL_REOPEN)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(Config.ASSISTANT_NAME)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(maxOf(1_000L, Config.KEEP_MS - (System.currentTimeMillis() - lastActivity)))
            .build()
        app.getSystemService(NotificationManager::class.java).notify(1, n)
    }

    private fun cancelReopenNotice() = app.getSystemService(NotificationManager::class.java).cancel(1)

    private fun hasMic() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
