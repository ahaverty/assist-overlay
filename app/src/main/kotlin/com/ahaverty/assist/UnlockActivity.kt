package com.ahaverty.assist

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock

/**
 * Invisible: brings up the phone's own unlock screen (fingerprint, face or PIN) over the lock screen and the panel,
 * and tells [Conversation.unlockFinished] whether the phone was unlocked. Started by `do:unlock` lines, which the
 * friday-voice bridge adds when a door unlock or disarm is asked while the phone is locked.
 *
 * The request waits until this window is up: asked from onCreate, Samsung's lock screen cancels it a quarter of a
 * second later, when this activity covers it (seen 15 Oct). A cancel that quick is the system's, not Alan's, so it
 * is asked once more. If he really backs out, [Conversation] still takes a normal unlock within two minutes as the yes.
 */
class UnlockActivity : Activity() {
    private var reported = false
    private var asked = 0
    private var askedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) done(true)
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (asked == 0) ask()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && asked == 0) window.decorView.postDelayed({ if (asked == 0) ask() }, 300)
    }

    private fun ask() {
        if (reported) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return done(true)
        asked++
        askedAt = SystemClock.elapsedRealtime()
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = retryOr(false)
            override fun onDismissError() = retryOr(false)
        })
    }

    private fun retryOr(ok: Boolean) {
        if (asked < 2 && SystemClock.elapsedRealtime() - askedAt < 1_500) {
            window.decorView.postDelayed({ ask() }, 300)
        } else {
            done(ok)
        }
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
