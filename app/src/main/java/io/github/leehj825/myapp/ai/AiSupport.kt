package io.github.leehj825.myapp.ai

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizerOptions
import io.github.leehj825.myapp.voice.VoicePrefs

/** Whether on-device AI (Gemini Nano) can be used on this phone. */
enum class AiStatus {
    /** Ready to use. */
    READY,

    /** Supported, but the model still has to be downloaded. */
    DOWNLOADABLE,

    /** The model is being downloaded now. */
    DOWNLOADING,

    /** Not supported on this phone (or the check failed). */
    UNAVAILABLE,
}

/**
 * On-device AI (Gemini Nano, through ML Kit GenAI). Works only on phones that support it.
 *
 * [refresh] asks the phone and remembers the answer. Screens show AI features only when the answer is READY.
 * Nothing is sent off the phone.
 */
object AiSupport {
    private const val PREFS = "ai"
    private const val KEY_SUMMARIZE = "summarize_status"

    /** The last answer from [refresh]. UNAVAILABLE until the phone has been checked. */
    fun cachedStatus(context: Context): AiStatus {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SUMMARIZE, null)
        return AiStatus.values().firstOrNull { it.name == name } ?: AiStatus.UNAVAILABLE
    }

    /** True if the AI is ready to use now. */
    fun cachedAvailable(context: Context): Boolean = cachedStatus(context) == AiStatus.READY

    /** Asks the phone for the AI status, remembers it, and gives it to [onDone]. */
    fun refresh(context: Context, onDone: (AiStatus) -> Unit = {}) {
        val app = context.applicationContext
        try {
            val summarizer = Summarization.getClient(options(app, null))
            val future = summarizer.checkFeatureStatus()
            future.addListener({
                val status = try {
                    toStatus(future.get())
                } catch (e: Exception) {
                    AiStatus.UNAVAILABLE
                }
                save(app, status)
                summarizer.close()
                onDone(status)
            }, ContextCompat.getMainExecutor(app))
        } catch (e: Exception) {
            save(app, AiStatus.UNAVAILABLE)
            onDone(AiStatus.UNAVAILABLE)
        }
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

    private fun toStatus(value: Int): AiStatus = when (value) {
        FeatureStatus.AVAILABLE -> AiStatus.READY
        FeatureStatus.DOWNLOADABLE -> AiStatus.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> AiStatus.DOWNLOADING
        else -> AiStatus.UNAVAILABLE
    }

    private fun save(context: Context, status: AiStatus) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SUMMARIZE, status.name).apply()
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
