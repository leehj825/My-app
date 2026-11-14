package io.github.leehj825.myapp.image

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One picture the Image tool made, with what it was made from. [uri] is the picture in Pictures/MyApp.
 * [kind] is "phone", "gemini" or "upscaled". For Gemini pictures the numbers are 0 and [sampler] is empty.
 */
data class HistoryEntry(
    val uri: String,
    val time: Long,
    val kind: String,
    val prompt: String,
    val negative: String,
    val model: String,
    val backend: String,
    val steps: Int,
    val size: Int,
    val seed: Long,
    val sampler: String,
    val guidance: Float,
    val seconds: Double,
)

/** Saves the list of pictures on this phone, in one file. The pictures themselves are in Pictures/MyApp. */
object ImageHistory {
    private const val FILE = "image_history.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** All entries, newest first. */
    @Synchronized
    fun load(context: Context): MutableList<HistoryEntry> {
        val file = file(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val array = JSONArray(file.readText())
            val entries = MutableList(array.length()) { i -> fromJson(array.getJSONObject(i)) }
            entries.asReversed().toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun add(context: Context, entry: HistoryEntry) {
        val entries = load(context).asReversed().toMutableList() // oldest first, as stored
        entries.add(entry)
        write(context, entries)
    }

    @Synchronized
    fun remove(context: Context, entry: HistoryEntry) {
        val entries = load(context).asReversed().toMutableList()
        entries.removeAll { it.uri == entry.uri && it.time == entry.time }
        write(context, entries)
    }

    private fun write(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        for (e in entries) array.put(toJson(e))
        file(context).writeText(array.toString())
    }

    private fun toJson(e: HistoryEntry): JSONObject = JSONObject()
        .put("uri", e.uri)
        .put("time", e.time)
        .put("kind", e.kind)
        .put("prompt", e.prompt)
        .put("negative", e.negative)
        .put("model", e.model)
        .put("backend", e.backend)
        .put("steps", e.steps)
        .put("size", e.size)
        .put("seed", e.seed)
        .put("sampler", e.sampler)
        .put("guidance", e.guidance.toDouble())
        .put("seconds", e.seconds)

    private fun fromJson(o: JSONObject): HistoryEntry = HistoryEntry(
        uri = o.getString("uri"),
        time = o.getLong("time"),
        kind = o.optString("kind", "phone"),
        prompt = o.optString("prompt"),
        negative = o.optString("negative"),
        model = o.optString("model"),
        backend = o.optString("backend"),
        steps = o.optInt("steps"),
        size = o.optInt("size"),
        seed = o.optLong("seed"),
        sampler = o.optString("sampler"),
        guidance = o.optDouble("guidance", 0.0).toFloat(),
        seconds = o.optDouble("seconds", 0.0),
    )
}
