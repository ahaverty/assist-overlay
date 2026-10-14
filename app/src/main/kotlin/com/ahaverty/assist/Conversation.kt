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
import android.util.Log
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
    val showHistory: Boolean = false,
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
    private var voice: ReplyVoice? = null
    private var visible = false
    private var lastTurnWasVoice = false
    private var continueConversation = false
    private var nextId = 1L
    private var chatId = newChatId()
    private var needsContext = false
    private val store = History(app)
    private val _history = MutableStateFlow(store.load())
    val history: StateFlow<List<SavedChat>> = _history

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
        saveCurrent()
        chatId = newChatId()
        speech.cancel()
        stopAudio()
        runJob?.cancel()
        runJob = null
        conversationId = null
        continueConversation = false
        _state.update { UiState(speakReplies = it.speakReplies) }
        cancelReopenNotice()
    }

    // ---- history ------------------------------------------------------------------------------------------------------

    fun showHistory(show: Boolean) {
        if (show) {
            speech.cancel()
            saveCurrent()
        }
        _state.update { it.copy(showHistory = show, phase = if (show && it.phase == Phase.Listening) Phase.Idle else it.phase, partial = "", level = 0f) }
    }

    /** Reopen a saved chat. The next question carries the earlier exchange if HA has likely forgotten it. */
    fun resume(chat: SavedChat) {
        if (chat.id == chatId) return showHistory(false)
        reset()
        chatId = chat.id
        conversationId = chat.conversationId
        lastActivity = System.currentTimeMillis()
        needsContext = lastActivity - chat.updated > 4 * 60_000L
        nextId = (chat.messages.maxOfOrNull { it.id } ?: 0L) + 1
        _state.update { it.copy(messages = chat.messages, showHistory = false) }
    }

    fun deleteChat(chat: SavedChat) {
        _history.value = _history.value.filter { it.id != chat.id }
        store.save(_history.value)
        if (chat.id == chatId) reset()
    }

    private fun saveCurrent() {
        val msgs = _state.value.messages.filter { it.text.isNotBlank() }.map { it.copy(streaming = false) }
        if (msgs.none { it.fromUser }) return
        val chat = SavedChat(chatId, maxOf(lastActivity, 1L), conversationId, msgs)
        _history.value = listOf(chat) + _history.value.filter { it.id != chatId }
        store.save(_history.value)
    }

    private fun newChatId() = System.currentTimeMillis().toString(36) + (0..999).random()

    /**
     * HA keeps a conversation's memory only a few minutes. After a gap, the earlier exchange is sent
     * along with the question (the bridge treats a leading "(Context: ...)" as background).
     */
    private fun withEarlierContext(text: String): String {
        val earlier = _state.value.messages.filter { it.text.isNotBlank() && !it.error }
        val stale = needsContext || System.currentTimeMillis() - lastActivity > 4 * 60_000L
        needsContext = false
        if (earlier.isEmpty() || !stale) return text
        conversationId = null
        val transcript = earlier.joinToString("\n") { (if (it.fromUser) "Me: " else "You: ") + speakable(it.text).replace("\n", " ") }
            .takeLast(4000)
        return "(Context: this continues our earlier conversation:\n$transcript)\n\n$text"
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
        val outgoing = withEarlierContext(text)
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
        val speaker = if (voice && _state.value.speakReplies) startVoice() else null
        runJob = scope.launch {
            val me = coroutineContext.job
            try {
                ha.run(outgoing, conversationId).collect { ev ->
                    when (ev) {
                        is PipeEvent.Delta -> {
                            edit(replyId) { it.copy(text = it.text + ev.text) }
                            speaker?.add(ev.text)
                        }
                        is PipeEvent.IntentEnd -> {
                            conversationId = ev.conversationId ?: conversationId
                            continueConversation = ev.continueConversation
                            if (_state.value.messages.firstOrNull { it.id == replyId }?.text.isNullOrBlank() && ev.speech.isNotBlank()) {
                                edit(replyId) { it.copy(text = ev.speech) }
                                speaker?.add(ev.speech)
                            }
                        }
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
                speaker?.end()
                edit(replyId) { it.copy(streaming = false) }
                lastActivity = System.currentTimeMillis()
                if (runJob === me) saveCurrent()
                if (runJob === me) {
                    runJob = null
                    _state.update { if (it.phase == Phase.Thinking) it.copy(phase = if (voice != null) Phase.Speaking else Phase.Idle) else it }
                    if (!visible && _state.value.messages.isNotEmpty()) postReopenNotice()
                    if (voice == null) maybeContinue()
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

    private fun startVoice(): ReplyVoice {
        stopAudio()
        lateinit var v: ReplyVoice
        v = ReplyVoice(
            app, scope, ha,
            onStarted = { if (voice === v) _state.update { if (it.phase == Phase.Thinking || it.phase == Phase.Idle) it.copy(phase = Phase.Speaking) else it } },
            onFinished = {
                if (voice === v) {
                    voice = null
                    if (!busy) {
                        _state.update { if (it.phase == Phase.Speaking) it.copy(phase = Phase.Idle) else it }
                        maybeContinue()
                    }
                }
            },
        )
        voice = v
        return v
    }

    private fun stopAudio() {
        voice?.let {
            voice = null
            it.stop()
        }
        _state.update { if (it.phase == Phase.Speaking) it.copy(phase = if (busy) Phase.Thinking else Phase.Idle) else it }
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
