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
import androidx.compose.foundation.clickable
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
                    var screen by remember { mutableStateOf(if (intent?.action == ACTION_TRY) "try" else "setup") }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (intent?.action == ACTION_TRY) App.convo.onShown()
                        if (intent?.action == Updater.ACTION_INSTALL) installUpdate()
                        if (intent?.action == ACTION_BENCH) {
                            val wav = java.io.File(getExternalFilesDir(null), "bench.wav")
                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                runCatching { Whisper.benchmark(this@MainActivity, wav) }.getOrElse { "benchmark failed: $it" }
                            }
                            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                        }
                        Updater.checkInBackground(this@MainActivity, force = true)
                    }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Updater.ACTION_INSTALL) installUpdate()
    }

    private fun installUpdate() {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Assist to install updates, then tap the update again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            return
        }
        Updater.install(this) { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
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
            SpeechPicker()
            Toggle("Chime and buzz when listening", App.convo.feedback.enabled) {
                App.convo.feedback.enabled = it
                if (it) App.convo.feedback.listening()
                refresh++
            }
            Toggle("Talk over a spoken reply to interrupt it", App.convo.bargeInEnabled) { App.convo.bargeInEnabled = it; refresh++ }
            Text(
                "Questions about \"this\" or \"this page\" send the screen's text along. That needs " +
                    "Settings > Apps > Default apps > Digital assistant app > Use text from screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var update by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Version ${packageManager.getPackageInfo(packageName, 0).versionName}" + (update?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    update = "checking…"
                    scope.launch {
                        val a = runCatching { Updater.check(this@MainActivity) }.getOrElse { update = "can't reach the PC (home Wi-Fi or WireGuard only)"; return@launch }
                        if (a == null) update = "up to date" else { update = "${a.versionName} available"; installUpdate() }
                    }
                }) { Text("Check for update") }
            }
            Spacer(Modifier.size(4.dp))
            Button(onClick = onTry, enabled = loggedIn, modifier = Modifier.fillMaxWidth()) { Text("Try it here") }
        }
    }

    /** Google (live words) or on-phone Whisper; Whisper models download from the PC the first time. */
    @Composable
    private fun SpeechPicker() {
        var engine by remember { mutableStateOf(App.convo.sttEngine) }
        val progress = remember { androidx.compose.runtime.mutableStateMapOf<Whisper.Model, Float>() }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        Text("Speech recognition", style = MaterialTheme.typography.titleMedium)
        val options = listOf(Triple("google", "Google", "Live words, fastest. ${Speech.recognizerLabel(this@MainActivity)}")) +
            Whisper.Model.entries.map { m ->
                val key = if (m == Whisper.Model.SMALL) "small" else "turbo"
                val state = when {
                    Whisper.ready(this@MainActivity, m) -> "On the phone. " + (getSharedPreferences("ui", MODE_PRIVATE).getString("whisper_last_${m.name}", null)
                        ?: if (m == Whisper.Model.SMALL) "Quick." else "Most accurate, a bit slower.")
                    progress[m] != null -> "Downloading ${(progress[m]!! * 100).toInt()}%…"
                    else -> "${m.mb} MB download from the PC (home Wi-Fi or WireGuard)."
                }
                Triple(key, m.label, state)
            }
        options.forEach { (key, label, detail) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    val m = when (key) { "small" -> Whisper.Model.SMALL; "turbo" -> Whisper.Model.TURBO; else -> null }
                    if (m != null && !Whisper.ready(this@MainActivity, m)) {
                        if (progress[m] != null) return@clickable
                        progress[m] = 0f
                        error = null
                        scope.launch {
                            runCatching { Whisper.download(this@MainActivity, m) { progress[m] = it } }
                                .onSuccess { App.convo.sttEngine = key; engine = key }
                                .onFailure { error = "Download failed: ${it.message}. It needs home Wi-Fi or WireGuard." }
                            progress.remove(m)
                        }
                    } else {
                        App.convo.sttEngine = key
                        engine = key
                    }
                },
            ) {
                androidx.compose.material3.RadioButton(selected = engine == key, onClick = null)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    @Composable
    private fun Toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = on, onCheckedChange = onChange)
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

    companion object {
        const val ACTION_TRY = "com.ahaverty.assist.TRY"
        const val ACTION_BENCH = "com.ahaverty.assist.BENCH"
    }
}
