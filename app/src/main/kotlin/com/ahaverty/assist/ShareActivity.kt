package com.ahaverty.assist

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.widget.Toast

/**
 * "Share to Assist" from any app: the shared text or link rides along with the next question, and the
 * overlay opens so Alan can ask about it. Images can't go through Home Assistant's text pipeline.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "Only text and links can be shared to Assist for now.", Toast.LENGTH_LONG).show()
            return finish()
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val from = referrer?.host?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        } ?: "another app"
        App.convo.share(if (subject.isNotEmpty()) "$from ($subject)" else from, if (subject.isNotEmpty()) "$subject\n$text" else text)
        val service = AssistService.instance
        if (service != null && VoiceInteractionService.isActiveService(this, ComponentName(this, AssistService::class.java))) {
            service.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
        } else {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_TRY))
        }
        finish()
    }
}
