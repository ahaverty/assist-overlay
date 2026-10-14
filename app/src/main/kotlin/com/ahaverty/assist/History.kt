package com.ahaverty.assist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedChat(
    val id: String,
    val updated: Long,
    val conversationId: String?,
    val messages: List<Msg>,
) {
    val title: String get() = messages.firstOrNull { it.fromUser }?.text?.trim().orEmpty().ifEmpty { "Conversation" }
    val snippet: String get() = messages.lastOrNull { !it.fromUser && it.text.isNotBlank() }?.text
        ?.let { speakable(it) }?.lines()?.lastOrNull { it.isNotBlank() }.orEmpty()
}

/** Recent chats, newest first, in app-private storage: the last [MAX] chats from the last [DAYS] days. */
class History(context: Context) {
    private val file = File(context.filesDir, "history.json")

    fun load(): List<SavedChat> = runCatching {
        if (!file.exists()) return emptyList()
        val cutoff = System.currentTimeMillis() - DAYS * 86_400_000L
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val msgs = o.getJSONArray("messages")
            SavedChat(
                id = o.getString("id"),
                updated = o.getLong("updated"),
                conversationId = o.optString("conversationId").takeIf { it.isNotBlank() },
                messages = (0 until msgs.length()).map { j ->
                    val m = msgs.getJSONObject(j)
                    Msg(m.getLong("id"), m.getBoolean("user"), m.getString("text"), error = m.optBoolean("error"))
                },
            )
        }.filter { it.updated >= cutoff }
    }.getOrDefault(emptyList())

    fun save(chats: List<SavedChat>) {
        val arr = JSONArray()
        chats.take(MAX).forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("updated", c.updated)
                .put("conversationId", c.conversationId ?: "")
                .put("messages", JSONArray().apply {
                    c.messages.forEach { m -> put(JSONObject().put("id", m.id).put("user", m.fromUser).put("text", m.text).put("error", m.error)) }
                }))
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    companion object {
        const val MAX = 40
        const val DAYS = 30
    }
}
