package com.ahaverty.assist

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager

/** A short chime and buzz when listening starts and a softer one when it stops, like Google's assistant. */
class Feedback(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val tones by lazy { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 70) }.getOrNull() }

    var enabled: Boolean
        get() = prefs.getBoolean("chimes", true)
        set(v) = prefs.edit().putBoolean("chimes", v).apply()

    fun listening() {
        if (!enabled) return
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
        buzz(VibrationEffect.EFFECT_CLICK)
    }

    fun done() {
        if (!enabled) return
        buzz(VibrationEffect.EFFECT_TICK)
    }

    fun tick() = buzz(VibrationEffect.EFFECT_TICK)

    private fun buzz(effect: Int) {
        runCatching { vibrator.vibrate(VibrationEffect.createPredefined(effect)) }
    }
}
