package com.ahaverty.assist

import android.app.assist.AssistStructure
import android.content.Context
import android.text.InputType
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The overlay itself: drawn in the assistant window over whatever app is open (no activity of our own),
 * so it appears instantly and the app underneath stays put. Nothing dismisses it by swiping or tapping
 * outside; only the close button, Back, or leaving for another app hides it, and the conversation stays.
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
            // The panel lifts itself with imePadding(). Resizing the window too would count the
            // keyboard twice and float the panel a keyboard's height above it.
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
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
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        App.convo.onShown()
    }

    override fun onHide() {
        App.convo.onHidden()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    /** What the app underneath shows (when "use text from screen" is on); it goes along with questions about "this". */
    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure ?: return
        val pkg = structure.activityComponent?.packageName ?: return
        if (pkg == context.packageName) return
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val lines = LinkedHashSet<String>()
        state.assistContent?.webUri?.let { lines += "Address: $it" }
        for (i in 0 until structure.windowNodeCount) collect(structure.getWindowNodeAt(i).rootViewNode, lines)
        App.convo.setScreen(label, lines.joinToString("\n").take(8000))
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
