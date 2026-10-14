package com.ahaverty.assist

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
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

/** Text going along with the next question: what was on screen, or something shared into the app. */
data class Attachment(val label: String, val text: String, val shared: Boolean, val on: Boolean)

data class UiState(
    val messages: List<Msg> = emptyList(),
    val phase: Phase = Phase.Idle,
    val partial: String = "",
    val level: Float = 0f,
    val speakReplies: Boolean = true,
    val notice: String? = null,
    val showHistory: Boolean = false,
    val attachment: Attachment? = null,
    val transcribing: Boolean = false,
    val holding: Boolean = false,
)

/** Words that point at the screen: the screen text goes along automatically when the question uses one. */
private val POINTS_AT_SCREEN = Regex("""\b(this|these|screen|page|article|email|message|here|on my phone)\b""", RegexOption.IGNORE_CASE)

/**
 * The one conversation, kept in the app process so it survives the overlay being hidden
 * (home, recents, opening another app) for [Config.KEEP_MS], and saved to [History] after every reply.
 */
class Conversation(private val app: Context, private val ha: Ha) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(speakReplies = prefs.getBoolean("speak", true)))
    val state: StateFlow<UiState> = _state
    val feedback = Feedback(app)

    private var conversationId: String? = null
    private var lastActivity = 0L
    private var runJob: Job? = null
    private var voice: ReplyVoice? = null
    private var visible = false
    /** The panel is tucked into the screen edge (EdgeTab): the conversation is still very much on. */
    private var tucked = false
    private var lastTurnWasVoice = false
    private var continueConversation = false
    private var nextId = 1L
    private var chatId = newChatId()
    private var needsContext = false
    private val store = History(app)
    private val _history = MutableStateFlow(store.load())
    val history: StateFlow<List<SavedChat>> = _history

    /** True while the mic listens during a spoken reply, only to catch Alan talking over it. */
    private var bargeIn = false
    private var bargeRestarts = 0

    private val speech = Speech(
        app,
        onPartial = { p -> onPartial(p) },
        onLevel = { l -> if (!bargeIn) _state.update { it.copy(level = l) } },
        onDone = { text, error -> onSpeechDone(text, error) },
    )

    /** On-phone Whisper for the question itself (Google stays for live words and talk-over). */
    private val whisper = WhisperSpeech(
        app,
        model = { whisperModel ?: Whisper.Model.SMALL },
        onPartial = { p -> onPartial(p) },
        onLevel = { l -> _state.update { it.copy(level = l) } },
        onDone = { text, error -> onSpeechDone(text, error) },
        onTranscribing = { _state.update { it.copy(transcribing = true, level = 0f) } },
    )

    /** "google" or "small" (on-phone Whisper; an old "turbo" choice also means Whisper). */
    var sttEngine: String
        get() = if (prefs.getString("stt", "google") == "google") "google" else "small"
        set(v) = prefs.edit().putString("stt", v).apply()

    private val whisperModel: Whisper.Model?
        get() = (if (sttEngine == "google") null else Whisper.Model.SMALL)?.takeIf { Whisper.ready(app, it) }

    private val micBusy get() = speech.active || whisper.active

    private val busy get() = runJob?.isActive == true

    var bargeInEnabled: Boolean
        get() = prefs.getBoolean("barge_in", true)
        set(v) = prefs.edit().putBoolean("barge_in", v).apply()

    // ---- overlay lifecycle --------------------------------------------------------------------------------------------

    fun onShown() {
        visible = true
        val wasTucked = tucked
        tucked = false
        EdgeTab.remove()
        cancelReopenNotice()
        // A tucked conversation waits for Alan however long he leaves it.
        if (!wasTucked && System.currentTimeMillis() - lastActivity > Config.KEEP_MS) reset()
        when {
            !App.auth.loggedIn -> notice("Open the Assist app and sign in to Home Assistant first.")
            !hasMic() -> notice("Open the Assist app to allow the microphone.")
            _state.value.attachment?.shared == true -> Unit // something was shared in: let Alan ask about it
            _state.value.phase == Phase.Idle -> listen()
        }
        Updater.checkInBackground(app)
        whisperModel?.let { m -> scope.launch { runCatching { Whisper.preload(app, m) } } }
    }

    fun onHidden() {
        visible = false
        stopListening()
        _state.update { if (it.attachment?.shared == false) it.copy(attachment = null) else it }
        if (_state.value.messages.isNotEmpty() && !tucked) postReopenNotice(alert = false)
    }

    /** Tuck the panel into the left or right edge; the caller hides the panel. False if not allowed to draw over apps yet. */
    fun tuck(left: Boolean): Boolean {
        if (!EdgeTab.allowed(app)) return false
        tucked = true
        EdgeTab.show(app, left)
        return true
    }

    /** The close button: stop talking and listening, keep the conversation for later. */
    fun close() {
        stopListening()
        stopAudio()
    }

    fun newChat() {
        reset()
        if (visible && App.auth.loggedIn && hasMic()) listen()
    }

    private fun reset() {
        saveCurrent()
        chatId = newChatId()
        stopListening()
        stopAudio()
        runJob?.cancel()
        runJob = null
        conversationId = null
        continueConversation = false
        _state.update { UiState(speakReplies = it.speakReplies, attachment = it.attachment?.takeIf { a -> a.shared }) }
        cancelReopenNotice()
    }

    // ---- screen text and shared text ----------------------------------------------------------------------------------

    fun setScreen(label: String, text: String) {
        if (text.isBlank()) return
        _state.update { if (it.attachment?.shared == true) it else it.copy(attachment = Attachment(label, text, shared = false, on = false)) }
    }

    fun share(label: String, text: String) {
        _state.update { it.copy(attachment = Attachment(label, text, shared = true, on = true)) }
    }

    fun toggleAttachment() = _state.update { s -> s.copy(attachment = s.attachment?.let { it.copy(on = !it.on) }) }

    fun dropAttachment() = _state.update { it.copy(attachment = null) }

    // ---- history ------------------------------------------------------------------------------------------------------

    fun showHistory(show: Boolean) {
        if (show) {
            stopListening()
            saveCurrent()
        }
        _state.update { it.copy(showHistory = show) }
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

    // ---- what is sent -------------------------------------------------------------------------------------------------

    /**
     * The text sent to HA for one question: a locked-phone mark for the bridge, then background context
     * (the earlier exchange after a gap, screen or shared text), then the question itself.
     */
    private fun outgoing(question: String): String {
        val context = mutableListOf<String>()
        val earlier = _state.value.messages.filter { it.text.isNotBlank() && !it.error }
        val stale = needsContext || System.currentTimeMillis() - lastActivity > 4 * 60_000L
        needsContext = false
        if (earlier.isNotEmpty() && stale) {
            conversationId = null
            val transcript = earlier.joinToString("\n") { (if (it.fromUser) "Me: " else "You: ") + speakable(it.text).replace("\n", " ") }
            context += "this continues our earlier conversation:\n" + transcript.takeLast(4000)
        }
        _state.value.attachment?.let { a ->
            if (a.on || (!a.shared && POINTS_AT_SCREEN.containsMatchIn(question))) {
                context += (if (a.shared) "Alan shared this from ${a.label}" else "On Alan's screen, in ${a.label}") +
                    ":\n" + a.text.take(6000)
            }
        }
        _state.update { it.copy(attachment = null) }
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return buildString {
            if (locked) append(LOCK_MARK).append('\n')
            if (context.isNotEmpty()) append("(Context: ").append(context.joinToString("\n\n")).append(")\n\n")
            append(question)
        }
    }

    // ---- input --------------------------------------------------------------------------------------------------------

    fun micPressed() {
        when (_state.value.phase) {
            Phase.Listening -> {
                speech.stop()
                whisper.stop()
            }
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
        stopListening()
        stopAudio()
        send(text.trim(), spoken = false)
    }

    /** Hold-to-talk: listen through any pause until [holdEnd]. Uses Whisper when it is on the phone. */
    fun holdStart() {
        stopListening()
        stopAudio()
        if (!hasMic()) return notice("Open the Assist app to allow the microphone.")
        _state.update { it.copy(phase = Phase.Listening, partial = "", notice = null, holding = true) }
        feedback.listening()
        main.postDelayed({
            if (_state.value.holding && !micBusy) {
                if (Whisper.ready(app, Whisper.Model.SMALL)) whisper.start(holdToTalk = true) else speech.start()
            }
        }, 160)
    }

    fun holdEnd() {
        _state.update { it.copy(holding = false) }
        whisper.stop()
        speech.stop()
    }

    /** Typing started: drop the listening turn so the mic isn't open behind the keyboard. */
    fun typingStarted() = stopListening()

    private fun listen() {
        if (!hasMic()) return notice("Open the Assist app to allow the microphone.")
        stopAudio()
        bargeIn = false
        _state.update { it.copy(phase = Phase.Listening, partial = "", notice = null) }
        feedback.listening()
        // A beat after the chime, so the chime isn't heard as speech.
        main.postDelayed({
            if (_state.value.phase == Phase.Listening && !micBusy) {
                if (whisperModel != null) whisper.start() else speech.start()
            }
        }, 160)
    }

    private fun stopListening() {
        bargeIn = false
        if (micBusy || _state.value.phase == Phase.Listening) {
            speech.cancel()
            whisper.cancel()
            _state.update { it.copy(phase = if (it.phase == Phase.Listening) (if (busy) Phase.Thinking else Phase.Idle) else it.phase, partial = "", level = 0f, transcribing = false, holding = false) }
        }
    }

    private fun onPartial(p: String) {
        if (!bargeIn) return _state.update { it.copy(partial = p) }
        if (!isInterruption(p)) return
        // Alan is talking over the reply: stop it and carry on with this as his next question.
        bargeIn = false
        stopAudio()
        feedback.tick()
        _state.update { it.copy(phase = Phase.Listening, partial = p, notice = null) }
    }

    private fun onSpeechDone(text: String?, error: String?) {
        if (bargeIn) {
            bargeIn = false
            if (voice != null && visible && bargeRestarts < 15) {
                bargeRestarts++
                startBargeIn()
            }
            return
        }
        _state.update { it.copy(partial = "", level = 0f, transcribing = false, holding = false, phase = if (busy) Phase.Thinking else if (it.phase == Phase.Listening) Phase.Idle else it.phase) }
        if (text != null) feedback.heard() else feedback.nothing()
        when {
            text != null -> send(text, spoken = true)
            error != null -> notice(error)
        }
    }

    /** The reply's own voice comes back through the mic, so only words that aren't in the reply count. */
    private fun isInterruption(heard: String): Boolean {
        val words = heard.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length > 1 }
        if (words.isEmpty()) return false
        if (words.first() in setOf("stop", "wait", "friday", "cancel", "hang", "hold")) return true
        val reply = _state.value.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
        val spoken = speakable(reply).lowercase().split(Regex("[^a-z0-9']+")).toSet()
        val unknown = words.count { it !in spoken }
        return unknown >= 2 && unknown * 2 >= words.size
    }

    private fun startBargeIn() {
        if (!bargeInEnabled || !visible || !hasMic() || micBusy) return
        bargeIn = true
        speech.start()
    }

    // ---- a turn -------------------------------------------------------------------------------------------------------

    private fun send(text: String, spoken: Boolean) {
        runJob?.cancel()
        val sent = outgoing(text)
        val started = System.currentTimeMillis()
        lastActivity = started
        lastTurnWasVoice = spoken
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
        val speaker = if (spoken && _state.value.speakReplies) startVoice() else null
        runJob = scope.launch {
            val me = coroutineContext.job
            try {
                ha.run(sent, conversationId).collect { ev ->
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
                if (runJob === me) {
                    runJob = null
                    saveCurrent()
                    _state.update { if (it.phase == Phase.Thinking) it.copy(phase = if (voice != null) Phase.Speaking else Phase.Idle) else it }
                    // A slow answer that lands while the panel is away gets a proper (sounding) notification.
                    // Tucked, the edge tab shows it; the sounding one still comes for a slow answer (phone in a pocket).
                    val slow = lastActivity - started > 15_000
                    if (!visible && (!tucked || slow)) postReopenNotice(alert = slow)
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
            onStarted = {
                if (voice === v) {
                    _state.update { if (it.phase == Phase.Thinking || it.phase == Phase.Idle) it.copy(phase = Phase.Speaking) else it }
                    bargeRestarts = 0
                    startBargeIn()
                }
            },
            onFinished = {
                if (voice === v) {
                    voice = null
                    if (bargeIn) stopListening()
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
        if (bargeIn) {
            bargeIn = false
            speech.cancel()
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

    private fun postReopenNotice(alert: Boolean) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val last = _state.value.messages.lastOrNull { !it.fromUser && it.text.isNotBlank() }
        val body = when {
            busy -> "Still working on it…"
            last != null -> speakable(last.text).lines().lastOrNull { it.isNotBlank() } ?: "Tap to see the reply"
            else -> "Tap to go back to the conversation"
        }
        val tap = PendingIntent.getBroadcast(
            app, 0, Intent(app, ReopenReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(app, if (alert) Config.CHANNEL_ANSWERS else Config.CHANNEL_REOPEN)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(if (alert) "${Config.ASSISTANT_NAME} answered" else Config.ASSISTANT_NAME)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setTimeoutAfter(maxOf(60_000L, Config.KEEP_MS - (System.currentTimeMillis() - lastActivity)))
            .build()
        app.getSystemService(NotificationManager::class.java).notify(1, n)
    }

    private fun cancelReopenNotice() = app.getSystemService(NotificationManager::class.java).cancel(1)

    private fun hasMic() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Tells the friday-voice bridge the phone was locked (it refuses door unlock and alarm disarm). */
        const val LOCK_MARK = "[assist:locked]"
    }
}
