package com.ahaverty.assist

import android.app.assist.AssistStructure
import android.content.Context
import android.text.InputType
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The overlay itself: drawn in the assistant window over whatever app is open (no activity of our own),
 * so it appears instantly and the app underneath stays put. Nothing closes it by swiping or tapping
 * outside: that tucks it into the screen edge. Only the close button, Back, or leaving for another app
 * hides it, and the conversation stays.
 */
class AssistSession(context: Context) :
    VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        window.window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
        }
        window.window?.decorView?.let { decor ->
            // On draw, not pre-draw: the system moves its pan (animated over ~250 ms) inside the window's
            // draw, after pre-draw, so pre-draw always saw last frame's pan. That, plus a Compose state
            // that only lands a frame later still, made the overlay shake as the keyboard came up.
            // Setting the view's own translation here is in time for the frame being drawn.
            val at = IntArray(2)
            decor.viewTreeObserver.addOnDrawListener {
                decor.getLocationInWindow(at)
                content?.let { if (it.translationY != -at[1].toFloat()) it.translationY = -at[1].toFloat() }
            }
        }
        // The screen stays on while Friday listens, thinks or talks, so it doesn't lock (and hide the
        // panel) mid-reply. The lock screen's own timeout is only a few seconds.
        lifecycleScope.launch {
            App.convo.state.collect { s ->
                val w = window.window ?: return@collect
                if (s.phase == Phase.Idle) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /**
     * The overlay's view. Android 16 pans the voice interaction window up to show the focused text field
     * whatever soft input mode is set, and the panel already lifts itself above the keyboard with
     * imePadding(), so this view moves back down by the pan (else it floats a keyboard's height above the
     * keys). getLocationInWindow on the decor includes the pan.
     */
    private var content: View? = null

    override fun onCreateContentView(): View = ComposeView(context).also { content = it }.apply {
        setViewTreeLifecycleOwner(this@AssistSession)
        setViewTreeSavedStateRegistryOwner(this@AssistSession)
        setViewTreeViewModelStoreOwner(this@AssistSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@AssistSession))
        setContent {
            AssistTheme {
                AssistOverlay(
                    convo = App.convo,
                    onClose = {
                        App.convo.close()
                        hide()
                    },
                    onTuck = { left ->
                        if (App.convo.tuck(left)) {
                            hide()
                        } else {
                            Toast.makeText(context, "Allow Assist to display over other apps, then tuck it again", Toast.LENGTH_LONG).show()
                            EdgeTab.askPermission(context)
                            hide()
                        }
                    },
                    onShade = {
                        // The assistant window sits above the status bar, so the pull never reached it.
                        // Open the shade once the panel has tucked away (a hidden API; if it is refused,
                        // the panel is still out of the way for a second pull).
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching {
                                val bar = context.getSystemService("statusbar")
                                bar.javaClass.getMethod("expandNotificationsPanel").invoke(bar)
                            }.onFailure { Log.w("AssistShade", "couldn't open the shade", it) }
                        }, 450)
                    },
                    onOpenClaude = {
                        App.convo.openInClaude(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        hide()
                    },
                    onOpenUrl = { url ->
                        // The conversation stays; long-press again (or the notification) to come back to it.
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            hide()
                        }.onFailure { Toast.makeText(context, "Couldn't open the link", Toast.LENGTH_SHORT).show() }
                    },
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        App.convo.onLeave = { hide() }
        App.convo.onStartActivity = { startAssistantActivity(it) }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        App.convo.onShown()
    }

    override fun onHide() {
        App.convo.onLeave = null
        App.convo.onStartActivity = null
        App.convo.onHidden()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    /** What the app underneath shows (when "use text from screen" is on); it goes along with questions about "this". */
    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure
        val pkg = structure?.activityComponent?.packageName
        Log.i("AssistScreen", "assist: pkg=$pkg windows=${structure?.windowNodeCount} focused=${state.isFocused}")
        if (structure == null || pkg == null || pkg == context.packageName) return
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val lines = LinkedHashSet<String>()
        state.assistContent?.webUri?.let { lines += "Address: $it" }
        for (i in 0 until structure.windowNodeCount) collect(structure.getWindowNodeAt(i).rootViewNode, lines)
        Log.i("AssistScreen", "screen text: $label, ${lines.size} lines")
        App.convo.setScreen(label, lines.joinToString("\n").take(8000))
    }

    /** The screenshot: the grab animation, and it goes to Friday with the screen text (or alone when the app gives none). */
    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
        Log.i("AssistScreen", "screenshot: ${screenshot?.let { "${it.width}x${it.height}" }}")
        App.convo.setScreenshot(screenshot)
    }

    private fun collect(node: AssistStructure.ViewNode, out: MutableSet<String>) {
        if (out.sumOf { it.length } > 8000) return
        val password = node.inputType and InputType.TYPE_MASK_VARIATION in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        val text = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!password && !text.isNullOrEmpty() && text.length > 1) out += text
        for (i in 0 until node.childCount) collect(node.getChildAt(i), out)
    }

    override fun onBackPressed() {
        hide()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }
}
