package com.ahaverty.assist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Bound by Android while this app is the default assistant. */
class AssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    companion object {
        var instance: AssistService? = null
    }
}

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** The "recent conversation" notification: reopen the overlay where it was. */
class ReopenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = AssistService.instance
        if (service != null && VoiceInteractionService.isActiveService(context, android.content.ComponentName(context, AssistService::class.java))) {
            service.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
        } else {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Required by the assistant metadata; never used for real recognition. */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) = listener.error(SpeechRecognizer.ERROR_CLIENT)
    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
