package io.github.leehj825.myapp.chat

import io.github.leehj825.myapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini in the cloud (Google's Gemini API). Used only when the user turns it on in the chat.
 * The API key is built in from the GEMINI_API_KEY secret; the message is sent to Google.
 */
object GeminiClient {
    // Flash-Lite model names to try, newest first. A name Google no longer knows gets HTTP 404 and the next one is tried.
    private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")

    /** True if this build has a Gemini key. The option is hidden otherwise. */
    val available: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    /** Sends [prompt] to Gemini and returns the reply. Blocking: call from a worker thread. */
    fun generate(prompt: String): String {
        var lastError: IllegalStateException? = null
        for (model in MODELS) {
            try {
                return generateWith(model, prompt)
            } catch (e: ModelNotFound) {
                lastError = IllegalStateException("model $model not found (HTTP 404)")
            }
        }
        throw lastError ?: IllegalStateException("no Gemini model available")
    }

    private class ModelNotFound : RuntimeException()

    private fun generateWith(model: String, prompt: String): String {
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            val body = JSONObject().put(
                "contents",
                JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
            )
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw ModelNotFound()
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Gemini failed with HTTP $code")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
        } finally {
            connection.disconnect()
        }
    }
}
