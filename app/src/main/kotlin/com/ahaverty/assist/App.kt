package com.ahaverty.assist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

object Config {
    /** Home Assistant on the LAN (tried first) and through Nabu Casa (everywhere else). */
    const val INTERNAL_URL = "http://192.168.1.10:8123"
    const val EXTERNAL_URL = "https://example.ui.nabu.casa"

    /**
     * HA's IndieAuth only fetches the client_id page when the redirect is on another host, so a
     * same-host redirect works without the domain existing. The login WebView catches the redirect.
     */
    const val CLIENT_ID = "https://assist.ahaverty.com/"
    const val REDIRECT_URI = "https://assist.ahaverty.com/auth_callback"

    /** "Claude Phone" pipeline: conversation.claude_phone via the friday-voice bridge. */
    const val PIPELINE_ID = "PIPELINE_ID"
    const val ASSISTANT_NAME = "Friday"

    /** A conversation is kept (and shown again on the next long-press) this long after the last activity. */
    const val KEEP_MS = 10 * 60 * 1000L

    const val CLAUDE_PACKAGE = "com.anthropic.claude"
    const val CHANNEL_REOPEN = "reopen"
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
    }

    companion object {
        lateinit var instance: App
        lateinit var auth: Auth
        lateinit var ha: Ha
        lateinit var convo: Conversation
    }
}
