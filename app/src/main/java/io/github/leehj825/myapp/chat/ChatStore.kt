package io.github.leehj825.myapp.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One message in a chat. [role] is "user" or "assistant". */
data class ChatMessage(val role: String, val text: String)

/** A saved chat, for the list of chats. */
data class ChatSummary(val id: String, val title: String, val modified: Long)

/**
 * Saves chats on this phone, one file per chat, and remembers which chat is open.
 * It also keeps the memory settings: how many earlier messages go with each new message, for the on-phone
 * model and for Gemini separately. -1 means the whole chat.
 */
object ChatStore {
    private const val PREFS = "chat"
    private const val KEY_CURRENT = "current"
    private const val KEY_MEMORY = "memory"
    private const val KEY_GEMINI_MEMORY = "gemini_memory"
    private const val DEFAULT_MEMORY = 4

    /** The open chat's id. Starts a new chat if there is none yet. */
    fun currentId(context: Context): String =
        prefs(context).getString(KEY_CURRENT, null) ?: newChat(context)

    /** Starts a new, empty chat and makes it the open one. */
    fun newChat(context: Context): String {
        val id = System.currentTimeMillis().toString()
        setCurrent(context, id)
        return id
    }

    fun setCurrent(context: Context, id: String) {
        prefs(context).edit().putString(KEY_CURRENT, id).apply()
    }

    /** How many earlier messages go to the on-phone model with each new message. */
    fun memoryTurns(context: Context): Int = prefs(context).getInt(KEY_MEMORY, DEFAULT_MEMORY)

    fun setMemoryTurns(context: Context, turns: Int) {
        prefs(context).edit().putInt(KEY_MEMORY, turns).apply()
    }

    /** How many earlier messages go to Gemini with each new message. */
    fun geminiMemoryTurns(context: Context): Int = prefs(context).getInt(KEY_GEMINI_MEMORY, DEFAULT_MEMORY)

    fun setGeminiMemoryTurns(context: Context, turns: Int) {
        prefs(context).edit().putInt(KEY_GEMINI_MEMORY, turns).apply()
    }

    fun load(context: Context, id: String): MutableList<ChatMessage> {
        val file = file(context, id)
        if (!file.exists()) return mutableListOf()
        return try {
            val array = JSONArray(file.readText())
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                ChatMessage(o.getString("role"), o.getString("text"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, id: String, messages: List<ChatMessage>) {
        val array = JSONArray()
        for (m in messages) array.put(JSONObject().put("role", m.role).put("text", m.text))
        file(context, id).writeText(array.toString())
    }

    /** Deletes a saved chat. If it was the open one, the open chat is cleared so a new one is started. */
    fun delete(context: Context, id: String) {
        file(context, id).delete()
        if (prefs(context).getString(KEY_CURRENT, null) == id) {
            prefs(context).edit().remove(KEY_CURRENT).apply()
        }
    }

    /** All saved chats, newest first. */
    fun list(context: Context): List<ChatSummary> =
        dir(context).listFiles { f -> f.extension == "json" }.orEmpty()
            .map { f ->
                val id = f.nameWithoutExtension
                val first = load(context, id).firstOrNull { it.role == "user" }?.text
                ChatSummary(id, (first ?: "New chat").take(40), f.lastModified())
            }
            .sortedByDescending { it.modified }

    private fun dir(context: Context): File = File(context.filesDir, "chats").apply { mkdirs() }

    private fun file(context: Context, id: String): File = File(dir(context), "$id.json")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
