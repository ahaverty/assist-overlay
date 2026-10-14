package com.ahaverty.assist

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import android.graphics.Bitmap
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
    /** Phone actions in this reply that have run, by their `do:` target. */
    val actions: Map<String, PhoneActions.Result> = emptyMap(),
    /** The app whose screen went along with this question, shown under the bubble. */
    val screen: String? = null,
)

enum class Phase { Idle, Listening, Thinking, Speaking }

/** Text going along with the next question: what was on screen, or something shared into the app. */
data class Attachment(val label: String, val text: String, val shared: Boolean, val on: Boolean, val shot: Bitmap? = null)

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

    /**
     * Held while a turn is under way (listening, thinking or speaking). Without it the phone sleeps a few
     * seconds after the screen goes off (quickly when Assist was opened from the lock screen), the reply
     * stream and the next TTS clip stall, and Friday stops mid-sentence. The timeout is only a safety net.
     */
    private val awake = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assist:turn").apply { setReferenceCounted(false) }

    init {
        scope.launch {
            _state.collect { s -> if (s.phase == Phase.Idle) awake.release() else if (!awake.isHeld) awake.acquire(5 * 60_000L) }
        }
    }

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
    /** What Alan had already said when he grabbed the mic button mid-sentence (see [holdStart]). */
    private var carry = ""
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

    /** Set by the open panel: hides it (a phone action opened another app, e.g. Maps). */
    var onLeave: (() -> Unit)? = null

    var bargeInEnabled: Boolean
        get() = prefs.getBoolean("barge_in", true)
        set(v) = prefs.edit().putBoolean("barge_in", v).apply()

    // ---- overlay lifecycle --------------------------------------------------------------------------------------------

    fun onShown() {
        visible = true
        main.removeCallbacks(idleCheck)
        val stale = stale()
        tucked = false
        EdgeTab.remove()
        cancelReopenNotice()
        if (stale) reset()
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
        pendingShot = null
        if (_state.value.messages.isNotEmpty() && !tucked) postReopenNotice(alert = false)
        armIdleReset()
    }

    /** Idle too long to carry on: nothing asked or answered for [Config.KEEP_MS] ([Config.TUCKED_KEEP_MS] if tucked), and no answer on its way. */
    private fun stale(): Boolean =
        !busy && System.currentTimeMillis() - lastActivity > if (tucked) Config.TUCKED_KEEP_MS else Config.KEEP_MS

    /** Clears an idle conversation while the panel is away (tab and notification too), so the next long-press starts fresh. */
    private val idleCheck = Runnable {
        if (visible) return@Runnable
        if (stale()) {
            if (tucked) EdgeTab.remove()
            tucked = false
            reset()
        } else {
            armIdleReset()
        }
    }

    private fun armIdleReset() {
        main.removeCallbacks(idleCheck)
        if (visible || _state.value.messages.isEmpty()) return
        val keep = if (tucked) Config.TUCKED_KEEP_MS else Config.KEEP_MS
        main.postDelayed(idleCheck, maxOf(30_000L, keep - (System.currentTimeMillis() - lastActivity) + 1_000L))
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
        // The screen (or shared text) belongs to this showing, not the old chat: a new chat still gets the screenshot button.
        _state.update { UiState(speakReplies = it.speakReplies, attachment = it.attachment) }
        cancelReopenNotice()
    }

    // ---- screen text and shared text ----------------------------------------------------------------------------------

    /** The screenshot Android hands over with the screen text; the two arrive in either order. */
    private var pendingShot: Bitmap? = null

    fun setScreen(label: String, text: String) {
        if (text.isBlank()) return
        _state.update { if (it.attachment?.shared == true) it else it.copy(attachment = Attachment(label, text, shared = false, on = false, shot = pendingShot)) }
    }

    fun setScreenshot(shot: Bitmap?) {
        pendingShot = shot
        _state.update { s -> s.attachment?.takeIf { !it.shared }?.let { s.copy(attachment = it.copy(shot = shot)) } ?: s }
    }

    /** The screenshot button: the screen goes with the next question, said or typed. Listens if the mic had stopped. */
    fun grabScreen() {
        _state.update { s -> s.copy(attachment = s.attachment?.copy(on = true)) }
        if (visible && _state.value.phase == Phase.Idle && App.auth.loggedIn && hasMic()) listen()
    }

    /** The screenshot button when Android passed no screen (a secure app, or screen text off): say so rather than hide it. */
    fun noScreen() = notice("Android didn't pass this screen. Check \"Use text from screen\" is on, and some apps block it.")

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
    private var sentScreen: String? = null

    private fun outgoing(question: String): String {
        sentScreen = null
        val context = mutableListOf<String>()
        val earlier = _state.value.messages.filter { it.text.isNotBlank() && !it.error }
        // No conversation id (an earlier turn was cut short before HA gave one) means HA would start afresh too.
        val stale = needsContext || conversationId == null || System.currentTimeMillis() - lastActivity > 4 * 60_000L
        needsContext = false
        if (earlier.isNotEmpty() && stale) {
            conversationId = null
            val transcript = earlier.joinToString("\n") { (if (it.fromUser) "Me: " else "You: ") + speakable(it.text).replace("\n", " ") }
            context += "this continues our earlier conversation:\n" + transcript.takeLast(4000)
        }
        _state.value.attachment?.let { a ->
            // Only when the screenshot button was tapped (or text was shared in), never guessed from the words.
            if (a.on) {
                context += Setup.userName.ifEmpty { "The user" }.let { who ->
                    if (a.shared) "$who shared this from ${a.label}" else "On $who's screen, in ${a.label}"
                } +
                    ":\n" + a.text.take(6000)
                sentScreen = a.label
            }
        }
        // Shared text goes once; the screen stays for follow-ups, its button back to unpressed.
        _state.update { it.copy(attachment = it.attachment?.takeIf { a -> !a.shared }?.copy(on = false)) }
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val unlocked = confirmNext && !locked
        confirmNext = false
        return buildString {
            if (locked) append(LOCK_MARK).append('\n')
            if (unlocked) append(UNLOCKED_MARK).append('\n')
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

    /**
     * Hold-to-talk: listen through any pause until [holdEnd]. Uses Whisper when it is on the phone.
     * Grabbed mid-sentence, it carries on from what was already heard instead of starting again; if the pause
     * has just sent it (a few seconds ago, no answer yet), that question is taken back and carried on too.
     */
    fun holdStart() {
        val s = _state.value
        // Already mid-sentence: no chime (it sounds like a restart), just a tick, and the mic picks up at once.
        val carryingOn = s.phase == Phase.Listening && !s.holding
        carry = when {
            s.phase == Phase.Listening && !s.holding -> s.partial.trim()
            s.phase == Phase.Thinking && lastTurnWasVoice && System.currentTimeMillis() - lastActivity < Config.TAKE_BACK_MS -> takeBackLast()
            else -> ""
        }
        stopListening()
        stopAudio()
        if (!hasMic()) return notice("Open the Assist app to allow the microphone.")
        _state.update { it.copy(phase = Phase.Listening, partial = carry, notice = null, holding = true) }
        if (carryingOn || carry.isNotEmpty()) feedback.tick() else feedback.listening()
        main.postDelayed({
            if (_state.value.holding && !micBusy) {
                if (Whisper.ready(app, Whisper.Model.SMALL)) whisper.start(holdToTalk = true) else speech.start()
            }
        }, if (carryingOn || carry.isNotEmpty()) 0L else 160L)
    }

    fun holdEnd() {
        _state.update { it.copy(holding = false) }
        whisper.stop()
        speech.stop()
    }

    /** Typing started: drop the listening turn so the mic isn't open behind the keyboard. */
    fun typingStarted() = stopListening()

    private fun listen() {
        carry = ""
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

    /** The just-sent question, if no answer has started: cancel it, drop it from the chat and return its text. */
    private fun takeBackLast(): String {
        val msgs = _state.value.messages
        val reply = msgs.lastOrNull() ?: return ""
        val question = msgs.getOrNull(msgs.size - 2) ?: return ""
        if (reply.fromUser || reply.text.isNotBlank() || !question.fromUser) return ""
        runJob?.cancel()
        runJob = null
        _state.update { it.copy(messages = msgs.dropLast(2), phase = Phase.Idle) }
        return question.text.trim()
    }

    private fun withCarry(p: String) = if (carry.isEmpty()) p else (carry + " " + p).trim()

    private fun onPartial(p: String) {
        if (!bargeIn) return _state.update { it.copy(partial = withCarry(p)) }
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
        val said = text?.let { withCarry(it) } ?: carry.takeIf { it.isNotEmpty() }
        carry = ""
        if (said != null) feedback.heard() else feedback.nothing()
        when {
            said != null -> send(said, spoken = true)
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
                    Msg(nextId, true, text, screen = sentScreen) + Msg(replyId, false, "", streaming = true),
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
                if (runJob === me) runActions(replyId)
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

    /** Carries out the reply's `do:` lines once it is complete (alarms, timers, opening apps, directions). */
    private fun runActions(replyId: Long) {
        val reply = _state.value.messages.firstOrNull { it.id == replyId }?.takeIf { !it.error } ?: return
        val targets = embedLines(reply.text).lines().mapNotNull { HA_EMBED.matchEntire(it.trim())?.groupValues?.get(2) }
            .filter { PhoneActions.isAction(it) }.distinct()
        if (targets.isEmpty()) return
        scope.launch {
            val results = targets.associateWith { PhoneActions.run(app, it) }
            edit(replyId) { it.copy(actions = results) }
            results.values.firstOrNull { !it.ok }?.let { notice(it.text) }
            if (visible && results.values.any { it.ok && it.leavesPanel }) onLeave?.invoke()
        }
    }

    // ---- confirming by unlocking the phone ---------------------------------------------------------------------------

    /** Starts an activity from the panel (set while it is shown): the assistant's own task, so it works on the lock screen. */
    var onStartActivity: ((Intent) -> Unit)? = null
    private var pendingConfirm: String? = null
    /** The next question goes with UNLOCKED_MARK: Alan has just unlocked the phone to confirm. */
    private var confirmNext = false

    /**
     * `do:unlock?then=..`: a door unlock or disarm asked while the phone was locked. The phone's own unlock screen
     * (fingerprint, face or PIN) comes up over the panel; once it is unlocked, [then] goes to Friday as the yes.
     * Nobody gets past it without unlocking the phone, so a pocket dial or someone else holding it can't open the door.
     */
    fun confirmByUnlock(then: String): PhoneActions.Result {
        if (!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            main.post { sendConfirmed(then) }
            return PhoneActions.Result(true, "Phone unlocked, confirming")
        }
        pendingConfirm = then
        watchUserPresent()
        val intent = Intent(app, UnlockActivity::class.java)
        runCatching {
            onStartActivity?.invoke(intent) ?: app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // The panel sits above the unlock screen (Alan had to swipe it away), so it steps aside; unlockFinished brings it back.
        if (visible) main.postDelayed({ onLeave?.invoke() }, 250)
        // Even if the unlock screen didn't come up, unlocking the phone the normal way still confirms.
        return PhoneActions.Result(true, "Unlock your phone to confirm")
    }

    /**
     * From [UnlockActivity], or any unlock of the phone (USER_PRESENT) within [CONFIRM_MS]: the phone was unlocked,
     * so send the yes and bring the panel back. A dismissed unlock screen keeps waiting for a normal unlock.
     */
    fun unlockFinished(ok: Boolean) {
        val then = pendingConfirm ?: return
        if (!ok) {
            notice("Unlock your phone within 2 minutes to confirm")
            return
        }
        pendingConfirm = null
        stopWatchingUserPresent()
        if (!visible) reopenAssist(app)
        sendConfirmed(then)
    }

    private var userPresent: BroadcastReceiver? = null
    private val confirmExpired = Runnable {
        pendingConfirm = null
        stopWatchingUserPresent()
    }

    private fun watchUserPresent() {
        stopWatchingUserPresent()
        userPresent = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = unlockFinished(true)
        }.also { app.registerReceiver(it, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED) }
        main.postDelayed(confirmExpired, CONFIRM_MS)
    }

    private fun stopWatchingUserPresent() {
        main.removeCallbacks(confirmExpired)
        userPresent?.let { runCatching { app.unregisterReceiver(it) } }
        userPresent = null
    }

    private fun sendConfirmed(then: String) {
        stopListening()
        stopAudio()
        confirmNext = true
        send(then, spoken = lastTurnWasVoice)
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
            (if (m.fromUser) "Me: " else "${Setup.assistantName}: ") + m.text.trim()
        }
        return "Carrying on from my Home Assistant voice assistant (${Setup.assistantName}). " +
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
            .setContentTitle(if (alert) "${Setup.assistantName} answered" else Setup.assistantName)
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
        /** The phone was just unlocked to confirm a door unlock or disarm asked while locked (the bridge takes it as yes). */
        const val UNLOCKED_MARK = "[assist:unlocked]"
        /** How long an unlock still counts as confirming (the proxy keeps the pending door unlock for 5 minutes). */
        const val CONFIRM_MS = 120_000L
    }
}
