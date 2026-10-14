package com.ahaverty.assist

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Soft chimes (res/raw, made by tools/make_chimes.py) and a light buzz: a rising pair when listening
 * starts, one quiet note when Alan was heard, a falling pair when nothing was caught.
 */
class Feedback(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val listenSound = pool.load(context, R.raw.chime_listen, 1)
    private val heardSound = pool.load(context, R.raw.chime_heard, 1)
    private val noneSound = pool.load(context, R.raw.chime_none, 1)

    var enabled: Boolean
        get() = prefs.getBoolean("chimes", true)
        set(v) = prefs.edit().putBoolean("chimes", v).apply()

    fun listening() {
        if (!enabled) return
        play(listenSound)
        buzz(VibrationEffect.EFFECT_CLICK)
    }

    fun heard() {
        if (!enabled) return
        play(heardSound)
        buzz(VibrationEffect.EFFECT_TICK)
    }

    fun nothing() {
        if (!enabled) return
        play(noneSound)
    }

    fun tick() = buzz(VibrationEffect.EFFECT_TICK)

    private fun play(id: Int) {
        pool.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }

    private fun buzz(effect: Int) {
        runCatching { vibrator.vibrate(VibrationEffect.createPredefined(effect)) }
    }
}
