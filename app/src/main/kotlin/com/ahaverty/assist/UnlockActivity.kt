package com.ahaverty.assist

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle

/**
 * Invisible: brings up the phone's own unlock screen (fingerprint, face or PIN) over the lock screen and the panel,
 * and tells [Conversation.unlockFinished] whether the phone was unlocked. Started by `do:unlock` lines, which the
 * friday-voice bridge adds when a door unlock or disarm is asked while the phone is locked.
 */
class UnlockActivity : Activity() {
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return done(true)
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() = done(false)
        })
    }

    private fun done(ok: Boolean) {
        if (reported) return
        reported = true
        App.convo.unlockFinished(ok)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        done(false)
        super.onDestroy()
    }
}
