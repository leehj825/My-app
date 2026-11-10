package io.github.leehj825.myapp.ai

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizerOptions
import io.github.leehj825.myapp.voice.VoicePrefs

/**
 * On-device AI (Gemini Nano, through ML Kit GenAI). Works only on phones that support it.
 *
 * [refresh] asks the phone once and remembers the answer; screens show an AI button only when the answer is yes.
 * Nothing is sent off the phone.
 */
object AiSupport {
    private const val PREFS = "ai"
    private const val KEY_SUMMARIZE = "summarize_available"

    /** The last answer from [refresh]. False until the phone has been checked. */
    fun cachedAvailable(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SUMMARIZE, false)

    /** Asks the phone whether summarizing works here (the model is installed and ready), and remembers it. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val available = try {
            val summarizer = Summarization.getClient(options(app, null))
            val future = summarizer.checkFeatureStatus()
            future.addListener({
                val ready = try {
                    future.get() == FeatureStatus.AVAILABLE
                } catch (e: Exception) {
                    false
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SUMMARIZE, ready).apply()
                summarizer.close()
            }, ContextCompat.getMainExecutor(app))
            return
        } catch (e: Exception) {
            false
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SUMMARIZE, available).apply()
    }

    /** Summarizes [text] on the phone. [onResult] gets the summary, or null if it failed. */
    fun summarize(context: Context, text: String, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        try {
            val summarizer = Summarization.getClient(options(app, text))
            val future = summarizer.runInference(SummarizationRequest.builder(text).build())
            future.addListener({
                val summary = try {
                    future.get().summary
                } catch (e: Exception) {
                    null
                }
                summarizer.close()
                onResult(summary)
            }, ContextCompat.getMainExecutor(app))
        } catch (e: Exception) {
            onResult(null)
        }
    }

    private fun options(context: Context, text: String?): SummarizerOptions =
        SummarizerOptions.builder(context)
            .setInputType(SummarizerOptions.InputType.ARTICLE)
            .setOutputType(SummarizerOptions.OutputType.ONE_BULLET)
            .setLanguage(
                if (text != null && VoicePrefs.containsHangul(text)) {
                    SummarizerOptions.Language.KOREAN
                } else {
                    SummarizerOptions.Language.ENGLISH
                }
            )
            .build()
}
