package io.github.leehj825.myapp.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A text note. [createdAt] is when it was made, in milliseconds since 1970.
 * [day] (yyyy-MM-dd) is set only for journal entries, which have one per day.
 */
data class Note(val id: Long, val text: String, val createdAt: Long, val day: String? = null)

/** The date and time shown on a note, for example 2026-11-09 14:30. */
fun formatNoteTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** The day key of a time, for example 2026-11-09. */
fun dayKey(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

/** Keeps plain notes as a JSON file in the app's private storage. Only text is kept, no audio. */
object NoteStore {
    private const val FILE = "notes.json"

    fun load(context: Context): MutableList<Note> = try {
        parse(context.openFileInput(FILE).bufferedReader().use { it.readText() })
    } catch (e: IOException) {
        mutableListOf()
    }

    fun save(context: Context, notes: List<Note>) {
        context.openFileOutput(FILE, Context.MODE_PRIVATE).bufferedWriter().use { it.write(encode(notes)) }
    }

    /** JSON text to notes. Also used for the encrypted secure notes. */
    fun parse(json: String): MutableList<Note> = try {
        val array = JSONArray(json)
        MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            Note(
                o.getLong("id"),
                o.getString("text"),
                o.getLong("createdAt"),
                if (o.has("day")) o.getString("day") else null,
            )
        }
    } catch (e: JSONException) {
        mutableListOf()
    }

    fun encode(notes: List<Note>): String {
        val array = JSONArray()
        for (n in notes) {
            val o = JSONObject().put("id", n.id).put("text", n.text).put("createdAt", n.createdAt)
            if (n.day != null) o.put("day", n.day)
            array.put(o)
        }
        return array.toString()
    }
}
