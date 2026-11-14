package io.github.leehj825.myapp.image

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * An image model the app knows about. [path] is the model file. If [copied] is true the app made the copy and may
 * delete it; otherwise it is the user's own file, read in place, and is never deleted.
 */
data class ModelRef(val name: String, val path: String, val copied: Boolean)

/** The list of image models, saved on this phone in one small file. The model files themselves are not in it. */
object ModelStore {
    private const val FILE = "image_models.json"

    /** The folder for model files copied into the app. */
    fun dir(context: Context): File = File(context.filesDir, "image/models")

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun list(context: Context): MutableList<ModelRef> {
        val file = file(context)
        if (!file.exists()) return mutableListOf()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun find(context: Context, name: String): ModelRef? = list(context).firstOrNull { it.name == name }

    /** Adds a model, or replaces the one with the same name. */
    @Synchronized
    fun add(context: Context, ref: ModelRef) {
        val models = list(context)
        models.removeAll { it.name == ref.name }
        models.add(ref)
        file(context).writeText(serialize(models))
    }

    @Synchronized
    fun remove(context: Context, name: String) {
        val models = list(context)
        models.removeAll { it.name == name }
        file(context).writeText(serialize(models))
    }

    internal fun serialize(models: List<ModelRef>): String {
        val array = JSONArray()
        for (m in models) {
            array.put(JSONObject().put("name", m.name).put("path", m.path).put("copied", m.copied))
        }
        return array.toString()
    }

    internal fun parse(text: String): MutableList<ModelRef> {
        val array = JSONArray(text)
        return MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            ModelRef(o.getString("name"), o.getString("path"), o.optBoolean("copied", true))
        }
    }
}
