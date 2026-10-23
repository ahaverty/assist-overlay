package com.ahaverty.assist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

object Config {
    /**
     * HA's IndieAuth only fetches the client_id page when the redirect is on another host, so a
     * same-host redirect works without the domain existing. The login WebView catches the redirect.
     * Changing these signs everyone out (refresh tokens are tied to the client_id).
     */
    const val CLIENT_ID = "https://assist.ahaverty.com/"
    const val REDIRECT_URI = "https://assist.ahaverty.com/auth_callback"

    /** A conversation is kept (and shown again on the next long-press) this long after the last question or reply. */
    const val KEEP_MS = 5 * 60 * 1000L
    /** The same for a conversation tucked into the screen edge (a slow answer being waited on). */
    const val TUCKED_KEEP_MS = 30 * 60 * 1000L
    /** Grabbing the mic button this soon after a pause sent the question takes it back to carry on. */
    const val TAKE_BACK_MS = 6_000L
    /** Tapping the text box keeps (finishes and sends) a turn this long with this many words; a shorter one is the TV or a false start and is dropped. */
    const val NOTE_MS = 12_000L
    const val NOTE_WORDS = 8

    const val CLAUDE_PACKAGE = "com.anthropic.claude"
    const val CHANNEL_REOPEN = "reopen"
    const val CHANNEL_ANSWERS = "answers"
}

/**
 * Everything specific to one household, set on the setup screen (or over adb with [ConfigReceiver]):
 * where Home Assistant is, which Assist pipeline to talk to, and what to call the assistant and the user.
 */
object Setup {
    /** The keys, also the extra names [ConfigReceiver] takes. */
    val KEYS = listOf(
        "home_url", "remote_url", "pipeline_id", "pipeline_name", "assistant_name", "user_name",
        "whisper_words", "tts_engine", "tts_language", "tts_voice",
    )
    private val prefs get() = App.instance.getSharedPreferences("setup", Context.MODE_PRIVATE)

    fun get(key: String): String = prefs.getString(key, null).orEmpty()
    fun set(key: String, value: String) {
        val v = value.trim().let { if (key.endsWith("_url")) it.trimEnd('/') else it }
        prefs.edit().putString(key, v).apply()
        if (key.endsWith("_url")) App.auth.forgetBase()
    }

    /** Tried first when it answers quickly, e.g. http://192.168.1.10:8123. Optional. */
    val homeUrl get() = get("home_url")
    /** Reachable from anywhere, e.g. a Nabu Casa or own-domain https URL. */
    val remoteUrl get() = get("remote_url")
    val configured get() = homeUrl.isNotEmpty() || remoteUrl.isNotEmpty()
    /** Blank means HA's preferred pipeline. */
    val pipelineId get() = get("pipeline_id")
    val assistantName get() = get("assistant_name").ifEmpty { "Assistant" }
    val userName get() = get("user_name")
    /** Names Whisper would otherwise mishear; kept short so it can't leak into silent clips. */
    val whisperWords get() = get("whisper_words")
    /** The pipeline's voice. The app speaks replies itself so links can be read as their names. */
    val ttsEngine get() = get("tts_engine")
    val ttsLanguage get() = get("tts_language")
    val ttsVoice get() = get("tts_voice")
}

/**
 * `adb shell am broadcast -n com.ahaverty.assist/.ConfigReceiver --es remote_url https://... --es pipeline_id ...`
 * sets any of [Setup.KEYS]. Only the shell can send it (DUMP permission).
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Setup.KEYS.forEach { k -> intent.getStringExtra(k)?.let { Setup.set(k, it) } }
        Log.i("AssistSetup", Setup.KEYS.joinToString { "$it=${Setup.get(it)}" })
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        auth = Auth(this)
        ha = Ha(auth)
        convo = Conversation(this, ha)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Config.CHANNEL_REOPEN, "Recent conversation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tap to go back to the last conversation"
                setShowBadge(false)
            },
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Config.CHANNEL_ANSWERS, "Answers that took a while", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a slow answer arrives after you left the panel"
            },
        )
    }

    companion object {
        lateinit var instance: App
        lateinit var auth: Auth
        lateinit var ha: Ha
        lateinit var convo: Conversation
    }
}
