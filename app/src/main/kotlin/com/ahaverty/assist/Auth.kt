package com.ahaverty.assist

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotLoggedIn : Exception("Not signed in to Home Assistant")

/** Home Assistant OAuth tokens (refresh token kept in app-private prefs) and which URL reaches HA. */
class Auth(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val lock = Mutex()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val probe = http.newBuilder().connectTimeout(1200, TimeUnit.MILLISECONDS).readTimeout(2, TimeUnit.SECONDS).build()

    private var baseUrl: String? = null
    private var baseCheckedAt = 0L

    val loggedIn: Boolean get() = prefs.getString("refresh", null) != null

    fun authorizeUrl(base: String): String = Uri.parse("$base/auth/authorize").buildUpon()
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("client_id", Config.CLIENT_ID)
        .appendQueryParameter("redirect_uri", Config.REDIRECT_URI)
        .build().toString()

    /** The LAN address when it answers quickly, otherwise Nabu Casa. Re-checked every 2 minutes or after a failure. */
    suspend fun base(): String = withContext(Dispatchers.IO) {
        val cached = baseUrl
        if (cached != null && System.currentTimeMillis() - baseCheckedAt < 120_000) return@withContext cached
        val url = try {
            probe.newCall(Request.Builder().url("${Config.INTERNAL_URL}/manifest.json").build()).execute().use {
                if (it.code < 500) Config.INTERNAL_URL else Config.EXTERNAL_URL
            }
        } catch (e: IOException) {
            Config.EXTERNAL_URL
        }
        baseUrl = url
        baseCheckedAt = System.currentTimeMillis()
        url
    }

    fun forgetBase() {
        baseUrl = null
    }

    suspend fun exchangeCode(base: String, code: String) {
        val json = token(base, FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", Config.CLIENT_ID)
            .build())
        prefs.edit()
            .putString("refresh", json.getString("refresh_token"))
            .putString("access", json.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + json.optLong("expires_in", 1800) * 1000)
            .apply()
    }

    /** A valid access token, refreshed when it has under a minute left. */
    suspend fun accessToken(base: String): String = lock.withLock {
        val refresh = prefs.getString("refresh", null) ?: throw NotLoggedIn()
        val access = prefs.getString("access", null)
        if (access != null && prefs.getLong("expires", 0) - System.currentTimeMillis() > 60_000) return access
        val json = try {
            token(base, FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", Config.CLIENT_ID)
                .build())
        } catch (e: TokenRejected) {
            signOut()
            throw NotLoggedIn()
        }
        val newAccess = json.getString("access_token")
        prefs.edit()
            .putString("access", newAccess)
            .putLong("expires", System.currentTimeMillis() + json.optLong("expires_in", 1800) * 1000)
            .apply()
        newAccess
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    private class TokenRejected(msg: String) : IOException(msg)

    private suspend fun token(base: String, body: FormBody): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/auth/token").post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code in 400..499) throw TokenRejected("HA refused the token request (${resp.code}): $text")
            if (!resp.isSuccessful) throw IOException("HA token request failed (${resp.code})")
            JSONObject(text)
        }
    }
}
