package com.ahaverty.assist

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

/** Setup (sign in, permissions, default assistant) and a place to try the panel without the power button. */
class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ })
        setContent {
            AssistTheme {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf("setup") }
                    when (screen) {
                        "login" -> LoginScreen(onDone = { ok ->
                            screen = "setup"
                            if (ok) Toast.makeText(this, "Signed in to Home Assistant", Toast.LENGTH_SHORT).show()
                        })
                        "try" -> Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                            AssistPanel(
                                App.convo,
                                onClose = { App.convo.close(); screen = "setup" },
                                onOpenClaude = {
                                    App.convo.openInClaude(this@MainActivity)?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                                },
                                onOpenUrl = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } },
                            )
                        }
                        else -> SetupScreen(
                            tick = refresh,
                            onLogin = { screen = "login" },
                            onTry = {
                                screen = "try"
                                App.convo.onShown()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        App.convo.onHidden()
        super.onPause()
    }

    @Composable
    private fun SetupScreen(tick: Int, onLogin: () -> Unit, onTry: () -> Unit) {
        check(tick >= 0)
        val loggedIn = App.auth.loggedIn
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val isAssistant = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Assist", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Long-press the power button to talk to ${Config.ASSISTANT_NAME} (your Claude Phone pipeline) over any app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Step("Sign in to Home Assistant", loggedIn) {
                if (loggedIn) TextButton(onClick = { App.auth.signOut(); refresh++ }) { Text("Sign out") }
                else Button(onClick = onLogin) { Text("Sign in") }
            }
            Step("Microphone and notifications", mic && notif) {
                if (!(mic && notif)) Button(onClick = {
                    permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                }) { Text("Allow") }
            }
            Step("Default digital assistant", isAssistant) {
                OutlinedButton(onClick = {
                    runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                        .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                }) { Text(if (isAssistant) "Change" else "Set") }
            }
            if (!isAssistant) {
                Text(
                    "In that screen pick \"Digital assistant app\", then Assist. On Samsung also check Settings > Advanced features > Side button: Press and hold = Wake Digital assistant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Speech recognition: ${Speech.recognizerLabel(this@MainActivity)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Button(onClick = onTry, enabled = loggedIn, modifier = Modifier.fillMaxWidth()) { Text("Try it here") }
        }
    }

    @Composable
    private fun Step(title: String, done: Boolean, action: @Composable () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.size(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            action()
        }
    }

    /** HA's own login page; the redirect to [Config.REDIRECT_URI] is caught here and never loaded. */
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun LoginScreen(onDone: (Boolean) -> Unit) {
        val scope = rememberCoroutineScope()
        var base by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) { base = App.auth.base() }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                TextButton(onClick = { onDone(false) }) { Text("Cancel") }
                Text(error ?: (base ?: "Finding Home Assistant…"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            val url = base ?: return@Column
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                if (!u.toString().startsWith(Config.REDIRECT_URI)) return false
                                val code = u.getQueryParameter("code")
                                if (code == null) {
                                    error = "Sign-in was cancelled"
                                    return true
                                }
                                scope.launch {
                                    runCatching { App.auth.exchangeCode(url, code) }
                                        .onSuccess { onDone(true) }
                                        .onFailure { error = "Sign-in failed: ${it.message}" }
                                }
                                return true
                            }
                        }
                        loadUrl(App.auth.authorizeUrl(url))
                    }
                },
            )
        }
    }
}
