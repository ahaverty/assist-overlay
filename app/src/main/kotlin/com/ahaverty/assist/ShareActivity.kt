package com.ahaverty.assist

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "Share to Assist" from any app: the shared text or link rides along with the next question, and the
 * overlay opens so Alan can ask about it. Shared photos go in the text bar like ones from the photo button.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.type?.startsWith("image/") == true) return sharePhotos()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "Only text, links and photos can be shared to Assist.", Toast.LENGTH_LONG).show()
            return finish()
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val from = referrer?.host?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        } ?: "another app"
        App.convo.share(if (subject.isNotEmpty()) "$from ($subject)" else from, if (subject.isNotEmpty()) "$subject\n$text" else text)
        open()
    }

    private fun sharePhotos() {
        @Suppress("DEPRECATION")
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
        // Read them before finishing: the sharing app's read grant ends with this activity.
        lifecycleScope.launch {
            App.convo.addPhotos(uris)
            open()
        }
    }

    private fun open() {
        val service = AssistService.instance
        if (service != null && VoiceInteractionService.isActiveService(this, ComponentName(this, AssistService::class.java))) {
            service.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
        } else {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_TRY))
        }
        finish()
    }
}
