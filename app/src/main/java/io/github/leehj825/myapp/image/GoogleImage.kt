package io.github.leehj825.myapp.image

import android.util.Base64
import io.github.leehj825.myapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A picture made by Google's image model, with any text the model sent with it. */
class GeneratedImage(val bytes: ByteArray?, val text: String, val model: String)

/**
 * Picture generation with Google's Gemini image model ("Nano Banana"). The prompt is sent to Google.
 * Uses the GEMINI_API_KEY built into the app.
 */
object GoogleImage {
    // Image model names to try, newest first. A name Google no longer knows gets HTTP 404 and the next is tried.
    private val MODELS = listOf("gemini-3.1-flash-image", "gemini-2.5-flash-image")

    private class ModelNotFound : RuntimeException()

    /** Google has no picture quota left for this key (HTTP 429). The app can make the picture on the phone instead. */
    class QuotaExceeded : RuntimeException("Google's picture quota is used up")

    /** Creates a picture from [prompt]. Blocking: call from a worker thread. */
    fun generate(prompt: String): GeneratedImage {
        var lastError: IllegalStateException? = null
        for (model in MODELS) {
            try {
                return generateWith(model, prompt)
            } catch (e: ModelNotFound) {
                lastError = IllegalStateException("model $model not found (HTTP 404)")
            }
        }
        throw lastError ?: IllegalStateException("no image model available")
    }

    private fun generateWith(model: String, prompt: String): GeneratedImage {
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            val body = JSONObject()
                .put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw ModelNotFound()
            if (code == 429) throw QuotaExceeded()
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("Google failed with HTTP $code")

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return GeneratedImage(null, "", model)
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            var bytes: ByteArray? = null
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                part.optJSONObject("inlineData")?.let { inline ->
                    if (bytes == null) bytes = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                }
                text.append(part.optString("text"))
            }
            return GeneratedImage(bytes, text.toString(), model)
        } finally {
            connection.disconnect()
        }
    }
}
