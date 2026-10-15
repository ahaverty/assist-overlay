package com.ahaverty.assist

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The panel tucked into the side of the screen: a small tab on the left or right edge while the
 * conversation carries on. A spinner while Friday works, and when the answer lands the tab buzzes and
 * slides out a line of it for a few seconds, then stays marked until tapped. Tap brings the panel back;
 * dragging moves it up and down or over to the other edge, and dropping it on the close circle (shown at
 * the bottom while dragging) ends the conversation. The chat stays in the history menu.
 *
 * Needs "Display over other apps" (Settings.canDrawOverlays).
 */
object EdgeTab {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var watch: Job? = null
    private var hideLabel: Job? = null

    fun allowed(context: Context) = Settings.canDrawOverlays(context)

    fun askPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** The edge the tab was last on (the tuck button uses it; a swipe picks its own side). */
    fun lastLeft(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("tab_left", false)

    fun show(context: Context, left: Boolean) {
        val app = context.applicationContext
        remove()
        if (!allowed(app)) return
        val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tab_left", left).apply()
        val wm = app.getSystemService(WindowManager::class.java)
        val dark = (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val surface = app.getColor(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_50)
        val onSurface = app.getColor(if (dark) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900)
        val accent = app.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        val done = app.getColor(if (dark) android.R.color.system_accent3_200 else android.R.color.system_accent3_600)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), app.resources.displayMetrics).toInt()

        val bg = GradientDrawable().apply { setColor(surface) }
        fun shape(isLeft: Boolean) {
            val r = dp(26).toFloat()
            // Rounded on the screen side only, flat against the edge.
            bg.cornerRadii = if (isLeft) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        shape(left)

        val icon = ImageView(app).apply {
            setImageResource(R.drawable.ic_launcher_fg)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setColorFilter(onSurface)
        }
        val spinner = ProgressBar(app).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0)
            setStroke(dp(3), done)
        }
        val ringView = View(app).apply { background = ring; visibility = View.GONE }
        val iconBox = FrameLayout(app).apply {
            addView(spinner, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            addView(ringView, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            addView(icon, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }
        val label = TextView(app).apply {
            setTextColor(onSurface)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220)
            visibility = View.GONE
        }
        val tab = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            elevation = dp(8).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "${Setup.assistantName}: tap to open"
        }
        fun arrange(isLeft: Boolean) {
            tab.removeAllViews()
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6); marginEnd = dp(6)
            }
            // The label always slides out towards the middle of the screen.
            if (isLeft) { tab.addView(iconBox); tab.addView(label, lp) } else { tab.addView(label, lp); tab.addView(iconBox) }
        }
        arrange(left)

        val screen = wm.currentWindowMetrics.bounds
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
            x = 0
            y = prefs.getInt("tab_y", (screen.height() * 0.45).toInt()).coerceIn(dp(48), screen.height() - dp(160))
        }

        // Tap: open. Drag: the tab follows the finger and snaps back to the nearer edge when let go, or,
        // dropped on the close circle that shows at the bottom while dragging, ends the chat.
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        var downX = 0f; var downY = 0f; var startY = 0; var grabX = 0f; var grabY = 0f; var dragging = false
        var isLeft = left
        var overClose = false
        val closeCx = screen.width() / 2
        val closeCy = screen.height() - dp(140)
        tab.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startY = p.y; dragging = false; overClose = false
                    grabX = e.x; grabY = e.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                        dragging = true
                        CloseTarget.show()
                    }
                    if (dragging) {
                        val side = e.rawX < screen.width() / 2
                        if (side != isLeft) {
                            isLeft = side
                            p.gravity = Gravity.TOP or (if (side) Gravity.START else Gravity.END)
                            shape(side); arrange(side)
                        }
                        val near = CloseTarget.hit(e.rawX, e.rawY)
                        if (near != overClose) {
                            overClose = near
                            CloseTarget.highlight(near)
                            if (near) runCatching { vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
                        }
                        // Over the circle the tab sits on it; anywhere else it hangs off the finger.
                        val leftEdge = if (near) closeCx - v.width / 2f else e.rawX - grabX
                        val top = if (near) closeCy - v.height / 2f else e.rawY - grabY
                        val fromEdge = (if (isLeft) leftEdge else screen.width() - v.width - leftEdge).toInt()
                        p.x = fromEdge.coerceIn(0, maxOf(0, screen.width() - v.width))
                        p.y = (if (near) top.toInt() else startY + (e.rawY - downY).toInt())
                            .coerceIn(dp(48), screen.height() - v.height)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && overClose && e.actionMasked == MotionEvent.ACTION_UP) {
                        runCatching { vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) }
                        CloseTarget.hide()
                        v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180)
                            .withEndAction { App.convo.closeTucked() }.start()
                    } else if (dragging) {
                        CloseTarget.hide()
                        p.y = p.y.coerceIn(dp(48), screen.height() - dp(160))
                        prefs.edit().putInt("tab_y", p.y).putBoolean("tab_left", isLeft).apply()
                        ValueAnimator.ofInt(p.x, 0).apply {
                            duration = 220
                            interpolator = OvershootInterpolator()
                            addUpdateListener {
                                p.x = maxOf(0, it.animatedValue as Int)
                                if (root === v) runCatching { wm.updateViewLayout(v, p) }
                            }
                            start()
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        remove()
                        reopenAssist(app)
                    }
                    true
                }
                else -> true
            }
        }

        CloseTarget.attach(app, closeCx, closeCy, surface, onSurface)
        runCatching { wm.addView(tab, p) }.onFailure { CloseTarget.remove(); return }
        root = tab
        params = p
        // Slide in from the edge.
        tab.translationX = if (left) -dp(80).toFloat() else dp(80).toFloat()
        tab.animate().translationX(0f).setDuration(260).setInterpolator(OvershootInterpolator()).start()

        var wasWorking = false
        var finished = false
        watch = scope.launch {
            App.convo.state.collect { s ->
                val working = s.phase == Phase.Thinking || s.phase == Phase.Speaking || s.messages.any { it.streaming }
                spinner.visibility = if (working) View.VISIBLE else View.INVISIBLE
                if (working) {
                    finished = false
                    ringView.visibility = View.GONE
                    label.visibility = View.GONE
                }
                if (wasWorking && !working) {
                    finished = true
                    ringView.visibility = View.VISIBLE
                    val reply = s.messages.lastOrNull { !it.fromUser && it.text.isNotBlank() }
                    val line = reply?.let { speakable(it.text).lines().firstOrNull { l -> l.isNotBlank() }?.trim() }
                    label.text = line ?: "Done. Tap to see it"
                    label.visibility = View.VISIBLE
                    runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 90, 40), -1)) }
                    pulse(tab)
                    hideLabel?.cancel()
                    hideLabel = scope.launch {
                        delay(6_000)
                        label.visibility = View.GONE
                    }
                }
                wasWorking = working
                tab.contentDescription = when {
                    working -> "${Setup.assistantName} is working. Tap to open"
                    finished -> "${Setup.assistantName} answered. Tap to open"
                    else -> "${Setup.assistantName}: tap to open"
                }
            }
        }
    }

    private fun pulse(v: View) {
        ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 420
            addUpdateListener { v.scaleX = it.animatedValue as Float; v.scaleY = it.animatedValue as Float }
            start()
        }
    }

    fun remove() {
        CloseTarget.remove()
        watch?.cancel(); watch = null
        hideLabel?.cancel(); hideLabel = null
        val v = root ?: return
        root = null
        params = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }
}

/** The close circle that shows near the bottom of the screen while the edge tab is being dragged. */
private object CloseTarget {
    private var view: TextView? = null
    private var cx = 0
    private var cy = 0
    private var radius = 0
    private var shown = false

    /** Added hidden before the tab, so the tab stays on top of it when it snaps on. */
    fun attach(context: Context, centreX: Int, centreY: Int, surface: Int, onSurface: Int) {
        remove()
        val wm = context.getSystemService(WindowManager::class.java)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val size = dp(64)
        cx = centreX; cy = centreY
        // Generous: the finger only has to get close, then the tab snaps onto the circle.
        radius = dp(72)
        val circle = TextView(context).apply {
            text = "✕"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(onSurface)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(surface)
                setStroke(dp(2), (onSurface and 0x00FFFFFF) or 0x40000000)
            }
            elevation = dp(6).toFloat()
            contentDescription = "Drop here to close"
        }
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = centreX - size / 2
            y = centreY - size / 2
        }
        runCatching { wm.addView(circle, p) }.onFailure { return }
        view = circle
        circle.alpha = 0f
        circle.visibility = View.INVISIBLE
        shown = false
    }

    fun show() {
        val v = view ?: return
        if (shown) return
        shown = true
        v.visibility = View.VISIBLE
        v.scaleX = 1f; v.scaleY = 1f
        v.translationY = v.height * 0.6f
        v.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    fun hide() {
        val v = view ?: return
        if (!shown) return
        shown = false
        v.animate().alpha(0f).setDuration(150).withEndAction { if (!shown) v.visibility = View.INVISIBLE }.start()
    }

    fun hit(x: Float, y: Float): Boolean {
        if (view == null || !shown) return false
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= radius.toFloat() * radius
    }

    fun highlight(on: Boolean) {
        val s = if (on) 1.3f else 1f
        view?.animate()?.scaleX(s)?.scaleY(s)?.setDuration(120)?.start()
    }

    fun remove() {
        val v = view ?: return
        view = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }
}
