package com.ahaverty.assist

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface PipeEvent {
    /** Streamed reply text (includes the bridge's "Checking ...…" progress paragraphs). */
    data class Delta(val text: String) : PipeEvent
    data class IntentEnd(val speech: String, val conversationId: String?, val continueConversation: Boolean) : PipeEvent
    data class Failed(val message: String) : PipeEvent
}

/** Runs the Claude Phone pipeline over HA's websocket, text in and text out: the phone does speech both ways. */
class Ha(private val auth: Auth) {
    private val ws = auth.http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun run(text: String, conversationId: String?): Flow<PipeEvent> = callbackFlow {
        val base = auth.base()
        val token = try {
            auth.accessToken(base)
        } catch (e: NotLoggedIn) {
            throw e
        } catch (e: IOException) {
            auth.forgetBase()
            throw e
        }
        val wsUrl = base.replaceFirst("http", "ws") + "/api/websocket"
        val input = text

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                    "auth_invalid" -> {
                        trySend(PipeEvent.Failed("Home Assistant rejected the sign-in. Open the Assist app to sign in again."))
                        webSocket.close(1000, null)
                        close()
                    }
                    "auth_ok" -> {
                        val run = JSONObject()
                            .put("id", 1)
                            .put("type", "assist_pipeline/run")
                            .put("start_stage", "intent")
                            .put("end_stage", "intent")
                            .put("input", JSONObject().put("text", input))
                            .put("pipeline", Config.PIPELINE_ID)
                            .put("timeout", 300)
                        if (conversationId != null) run.put("conversation_id", conversationId)
                        webSocket.send(run.toString())
                    }
                    "result" -> if (!msg.optBoolean("success", true)) {
                        val err = msg.optJSONObject("error")?.optString("message") ?: "Pipeline failed to start"
                        trySend(PipeEvent.Failed(err))
                        webSocket.close(1000, null)
                        close()
                    }
                    "event" -> {
                        val ev = msg.optJSONObject("event") ?: return
                        val data = ev.optJSONObject("data")
                        when (ev.optString("type")) {
                            "intent-progress" -> data?.optJSONObject("chat_log_delta")?.optString("content", "")
                                ?.takeIf { it.isNotEmpty() }?.let { trySend(PipeEvent.Delta(it)) }
                            "intent-end" -> {
                                val out = data?.optJSONObject("intent_output")
                                val speech = out?.optJSONObject("response")?.optJSONObject("speech")
                                    ?.optJSONObject("plain")?.optString("speech").orEmpty()
                                trySend(PipeEvent.IntentEnd(
                                    speech,
                                    out?.optString("conversation_id")?.takeIf { it.isNotBlank() },
                                    out?.optBoolean("continue_conversation", false) ?: false,
                                ))
                            }
                            "error" -> trySend(PipeEvent.Failed(data?.optString("message") ?: "Something went wrong"))
                            "run-end" -> {
                                webSocket.close(1000, null)
                                close()
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                auth.forgetBase()
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }
        val socket = ws.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        awaitClose { socket.cancel() }
    }

    /** A TTS clip for [message] in the pipeline's voice; HA renders it when the URL is fetched. */
    suspend fun ttsUrl(message: String): String = withContext(Dispatchers.IO) {
        val base = auth.base()
        val token = auth.accessToken(base)
        val body = JSONObject()
            .put("engine_id", Config.TTS_ENGINE)
            .put("message", message)
            .put("language", Config.TTS_LANGUAGE)
            .put("options", JSONObject().put("voice", Config.TTS_VOICE))
            .toString().toRequestBody("application/json".toMediaType())
        auth.http.newCall(
            Request.Builder().url("$base/api/tts_get_url").header("Authorization", "Bearer $token").post(body).build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("tts_get_url ${resp.code}")
            val json = JSONObject(resp.body!!.string())
            json.optString("path").takeIf { it.isNotBlank() }?.let { base + it } ?: json.getString("url")
        }
    }

}
