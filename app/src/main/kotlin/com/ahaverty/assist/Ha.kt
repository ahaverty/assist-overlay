package com.ahaverty.assist

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface PipeEvent {
    /** Streamed reply text (includes the bridge's "Checking ...…" progress paragraphs). */
    data class Delta(val text: String) : PipeEvent
    data class IntentEnd(val speech: String, val conversationId: String?, val continueConversation: Boolean) : PipeEvent
    data class Failed(val message: String) : PipeEvent
}

/** Runs the chosen Assist pipeline over HA's websocket, text in and text out: the phone does speech both ways. */
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
                            .put("timeout", 300)
                        Setup.pipelineId.takeIf { it.isNotEmpty() }?.let { run.put("pipeline", it) }
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

    /**
     * Speech to text by the pipeline's own STT engine (e.g. OpenAI's gpt-4o-transcribe, with the accent and
     * household words in its prompt): the whole clip (16 kHz mono 16-bit) goes up in one go after the turn ends.
     * no_vad, so HA's own end-of-speech check (0.7 s of quiet, 15 s cap) can't cut a long ramble short.
     */
    suspend fun transcribe(pcm: ShortArray): String {
        val base = auth.base()
        val token = auth.accessToken(base)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[2 * i] = (pcm[i].toInt() and 0xff).toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        // Upload plus the engine's own time (about 3 s for half a minute through OpenAI).
        return withTimeout(20_000L + pcm.size / 16) {
            suspendCancellableCoroutine { cont ->
                val socket = ws.newWebSocket(
                    Request.Builder().url(base.replaceFirst("http", "ws") + "/api/websocket").build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                            when (msg.optString("type")) {
                                "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                                "auth_invalid" -> fail(webSocket, "Home Assistant rejected the sign-in")
                                "auth_ok" -> {
                                    val run = JSONObject()
                                        .put("id", 1)
                                        .put("type", "assist_pipeline/run")
                                        .put("start_stage", "stt")
                                        .put("end_stage", "stt")
                                        .put("input", JSONObject().put("sample_rate", 16_000).put("no_vad", true))
                                        .put("timeout", 120)
                                    Setup.pipelineId.takeIf { it.isNotEmpty() }?.let { run.put("pipeline", it) }
                                    webSocket.send(run.toString())
                                }
                                "result" -> if (!msg.optBoolean("success", true)) {
                                    fail(webSocket, msg.optJSONObject("error")?.optString("message") ?: "speech to text failed to start")
                                }
                                "event" -> {
                                    val ev = msg.optJSONObject("event") ?: return
                                    val data = ev.optJSONObject("data")
                                    when (ev.optString("type")) {
                                        "run-start" -> {
                                            // Binary frames: the handler id byte, then audio; the id alone ends the stream.
                                            val h = data?.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1
                                            if (h < 0) return fail(webSocket, "no audio handler")
                                            val chunk = 32_000
                                            var i = 0
                                            while (i < bytes.size) {
                                                val n = minOf(chunk, bytes.size - i)
                                                webSocket.send(ByteString.of(h.toByte(), *bytes.copyOfRange(i, i + n)))
                                                i += n
                                            }
                                            webSocket.send(ByteString.of(h.toByte()))
                                        }
                                        "stt-end" -> {
                                            val said = data?.optJSONObject("stt_output")?.optString("text").orEmpty()
                                            if (cont.isActive) cont.resumeWith(Result.success(said))
                                            webSocket.close(1000, null)
                                        }
                                        "error" -> fail(webSocket, data?.optString("message") ?: "speech to text failed")
                                        "run-end" -> fail(webSocket, "no transcript")
                                    }
                                }
                            }
                        }

                        private fun fail(webSocket: WebSocket, why: String) {
                            if (cont.isActive) cont.resumeWith(Result.failure(IOException(why)))
                            webSocket.close(1000, null)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            auth.forgetBase()
                            if (cont.isActive) cont.resumeWith(Result.failure(t))
                        }
                    },
                )
                cont.invokeOnCancellation { socket.cancel() }
            }
        }
    }

    /** GET an HA API path with the user's token (states, history, camera snapshots). */
    suspend fun get(path: String): ByteArray = withContext(Dispatchers.IO) {
        val base = auth.base()
        val token = auth.accessToken(base)
        auth.http.newCall(Request.Builder().url(base + path).header("Authorization", "Bearer $token").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("$path ${r.code}")
            r.body!!.bytes()
        }
    }

    /**
     * Put a photo in HA's image store (the one person pictures use) and return its id. HA serves it to the LAN at
     * /api/image/serve/<id>/original, which is where the friday-voice bridge picks it up; [deleteImage] once answered.
     */
    suspend fun uploadImage(file: java.io.File): String = withContext(Dispatchers.IO) {
        val base = auth.base()
        val token = auth.accessToken(base)
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        auth.http.newCall(Request.Builder().url("$base/api/image/upload").header("Authorization", "Bearer $token").post(body).build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("image upload ${r.code}")
                JSONObject(r.body!!.string()).getString("id")
            }
    }

    suspend fun deleteImage(id: String) {
        command(JSONObject().put("type", "image/delete").put("image_id", id))
    }

    data class Pipeline(val id: String, val name: String, val preferred: Boolean, val tts: Triple<String, String, String>)

    /** The Assist pipelines set up in HA (Settings > Voice assistants). */
    suspend fun pipelines(): List<Pipeline> {
        val r = command(JSONObject().put("type", "assist_pipeline/pipeline/list"))
        val preferred = r.optString("preferred_pipeline")
        val list = r.getJSONArray("pipelines")
        return (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val id = p.getString("id")
            Pipeline(id, p.optString("name", id), id == preferred,
                Triple(p.optString("tts_engine").nullless(), p.optString("tts_language").nullless(), p.optString("tts_voice").nullless()))
        }
    }

    private fun String.nullless() = if (this == "null") "" else this

    /** A line in Home Assistant's own log (Settings > System > Logs), for tracing on the phone without adb. */
    suspend fun logLine(message: String) {
        command(
            JSONObject().put("type", "call_service").put("domain", "system_log").put("service", "write")
                .put("service_data", JSONObject().put("message", message).put("logger", "assist_overlay.trace").put("level", "warning")),
        )
    }

    /** One websocket command; returns its result. */
    private suspend fun command(cmd: JSONObject): JSONObject {
        val base = auth.base()
        val token = auth.accessToken(base)
        return withTimeout(15_000) {
            suspendCancellableCoroutine { cont ->
                val socket = ws.newWebSocket(
                    Request.Builder().url(base.replaceFirst("http", "ws") + "/api/websocket").build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                            when (msg.optString("type")) {
                                "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                                "auth_invalid" -> fail(webSocket, "Home Assistant rejected the sign-in")
                                "auth_ok" -> webSocket.send(cmd.put("id", 1).toString())
                                "result" -> {
                                    if (msg.optBoolean("success")) {
                                        if (cont.isActive) cont.resumeWith(Result.success(msg.optJSONObject("result") ?: JSONObject()))
                                        webSocket.close(1000, null)
                                    } else fail(webSocket, msg.optJSONObject("error")?.optString("message") ?: "failed")
                                }
                            }
                        }

                        private fun fail(webSocket: WebSocket, why: String) {
                            if (cont.isActive) cont.resumeWith(Result.failure(IOException(why)))
                            webSocket.close(1000, null)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            auth.forgetBase()
                            if (cont.isActive) cont.resumeWith(Result.failure(t))
                        }
                    },
                )
                cont.invokeOnCancellation { socket.cancel() }
            }
        }
    }

    /** A TTS clip for [message] in the pipeline's voice; HA renders it when the URL is fetched. */
    suspend fun ttsUrl(message: String): String = withContext(Dispatchers.IO) {
        if (Setup.ttsEngine.isEmpty()) throw IOException("no reply voice set up")
        val base = auth.base()
        val token = auth.accessToken(base)
        val body = JSONObject()
            .put("engine_id", Setup.ttsEngine)
            .put("message", message)
            .apply { Setup.ttsLanguage.takeIf { it.isNotEmpty() }?.let { put("language", it) } }
            .apply { Setup.ttsVoice.takeIf { it.isNotEmpty() }?.let { put("options", JSONObject().put("voice", it)) } }
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
