package io.github.leehj825.myapp.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One result from a web search. */
data class WebResult(val title: String, val url: String, val content: String)

/**
 * Web search with Tavily (tavily.com). When web search is on, the question is sent to Tavily and nothing else.
 * The API key is kept in the app's private storage on this phone.
 */
object WebSearch {
    private const val PREFS = "websearch"
    private const val KEY_API = "tavily_key"
    private const val ENDPOINT = "https://api.tavily.com/search"

    fun apiKey(context: Context): String? =
        prefs(context).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun saveApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    /** Searches the web. Blocking: call from a worker thread. Throws if the search fails. */
    fun search(apiKey: String, query: String, maxResults: Int = 3): List<WebResult> {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val body = JSONObject().put("query", query).put("max_results", maxResults).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("web search failed with HTTP $code")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val results: JSONArray = JSONObject(text).optJSONArray("results") ?: JSONArray()
            return (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                WebResult(r.optString("title"), r.optString("url"), r.optString("content"))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
