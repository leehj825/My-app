package io.github.leehj825.myapp.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A text note. [createdAt] is when it was made, in milliseconds since 1970. */
data class Note(val id: Long, val text: String, val createdAt: Long)

/** The date and time shown on a note, for example 2026-11-09 14:30. */
fun formatNoteTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** Keeps the notes as a JSON file in the app's private storage. Only text is kept, no audio. */
object NoteStore {
    private const val FILE = "notes.json"

    fun load(context: Context): MutableList<Note> = try {
        val array = JSONArray(context.openFileInput(FILE).bufferedReader().use { it.readText() })
        MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            Note(o.getLong("id"), o.getString("text"), o.getLong("createdAt"))
        }
    } catch (e: IOException) {
        mutableListOf()
    } catch (e: JSONException) {
        mutableListOf()
    }

    fun save(context: Context, notes: List<Note>) {
        val array = JSONArray()
        for (n in notes) {
            array.put(JSONObject().put("id", n.id).put("text", n.text).put("createdAt", n.createdAt))
        }
        context.openFileOutput(FILE, Context.MODE_PRIVATE).bufferedWriter().use { it.write(array.toString()) }
    }
}
