package com.ahaverty.assist

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Self-update from the PC: https://3d.ahaverty.com/claude/assist-app/ (claude-launcher, LAN + WireGuard only)
 * serves version.json and assist.apk, published by publish.sh. Checked at most every 3 hours when the
 * overlay or the app opens; a newer build posts a notification, and tapping it installs through Android's
 * own installer (one confirmation, signature must match).
 */
object Updater {
    private const val BASE = "https://3d.ahaverty.com/claude/assist-app"
    private const val TAG = "AssistUpdate"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Available(val versionCode: Long, val versionName: String, val notes: String)

    fun installed(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun checkInBackground(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("checked", 0) < 3 * 3600_000L) return
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
        scope.launch {
            val a = runCatching { check(context) }.onFailure { Log.d(TAG, "check failed: $it") }.getOrNull() ?: return@launch
            notify(context, a)
        }
    }

    suspend fun check(context: Context): Available? = withContext(Dispatchers.IO) {
        App.auth.http.newCall(Request.Builder().url("$BASE/version.json").build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val j = JSONObject(r.body!!.string())
            val a = Available(j.getLong("versionCode"), j.optString("versionName"), j.optString("notes"))
            if (a.versionCode > installed(context)) a else null
        }
    }

    private fun notify(context: Context, a: Available) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val tap = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).setAction(ACTION_INSTALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = a.notes.ifBlank { "Tap to install." }
        val n = Notification.Builder(context, Config.CHANNEL_REOPEN)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Assist ${a.versionName} is ready")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText("$body\nTap to install."))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(3, n)
    }

    /** Download and hand the APK to Android's installer. Progress and errors go to [say]. */
    fun install(activity: Activity, say: (String) -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.Main) { say("Downloading the update…") }
                val bytes = App.auth.http.newCall(Request.Builder().url("$BASE/assist.apk").build()).execute().use { r ->
                    if (!r.isSuccessful) error("download failed (${r.code})")
                    r.body!!.bytes()
                }
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(activity.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("assist.apk", 0, bytes.size.toLong()).use { it.write(bytes); s.fsync(it) }
                    val status = PendingIntent.getBroadcast(
                        activity, 4, Intent(activity, InstallStatusReceiver::class.java),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    s.commit(status.intentSender)
                }
                withContext(Dispatchers.Main) { say("Installing… confirm on the next screen.") }
            } catch (e: Exception) {
                Log.w(TAG, "install failed", e)
                withContext(Dispatchers.Main) { say("Update failed: ${e.message}") }
            }
        }
    }

    const val ACTION_INSTALL = "com.ahaverty.assist.INSTALL_UPDATE"
}

/** Android asks for the user's OK through this; anything else is just logged. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("AssistUpdate", "updated")
            else -> Log.w("AssistUpdate", "install status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
