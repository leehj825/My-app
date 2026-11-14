package io.github.leehj825.myapp.image

import android.util.Log
import io.github.leehj825.myapp.chat.LlmEngine

/**
 * Turns a short idea into a detailed description for an image model, using the chat's on-phone language model.
 * The chat model is loaded for the request and freed afterwards, so it does not use memory while pictures are made.
 */
object PromptWriter {
    private const val TAG = "PromptWriter"
    private const val MAX_TOKENS = 160
    // Image models read about 77 tokens, so a longer description is cut.
    private const val MAX_CHARS = 400
    private const val SYSTEM =
        "You write prompts for a text-to-image model. The user gives a short idea. Reply with one detailed " +
            "prompt in English and nothing else: no quotes, no explanation, no list. Describe the subject, the " +
            "setting, the lighting and the style, and end with a few quality words such as detailed, sharp focus."

    /**
     * Returns the improved prompt for [idea], or an empty string if the chat model could not be used.
     * Blocking, and slow while the model loads: call from a worker thread, and not while the Chat tool is open.
     */
    fun improve(modelPath: String, idea: String): String {
        LlmEngine.init()
        if (LlmEngine.load(modelPath) != 0) {
            Log.w(TAG, "The chat model could not be loaded")
            return ""
        }
        // If the context cannot start, the model is left loaded: freeing it needs the context that failed.
        if (LlmEngine.prepare() != 0) {
            Log.w(TAG, "The chat model could not be prepared")
            return ""
        }
        try {
            if (LlmEngine.processSystemPrompt(SYSTEM) != 0) return ""
            if (LlmEngine.processUserPrompt(idea, MAX_TOKENS) != 0) return ""
            val out = StringBuilder()
            while (true) {
                val piece = LlmEngine.generateNextToken() ?: break
                out.append(piece)
            }
            return clean(out.toString())
        } finally {
            LlmEngine.unload()
        }
    }

    /** Tidies the model's reply into one line: no label, no quotes, no line breaks, and not too long. */
    internal fun clean(raw: String): String {
        var text = raw.trim()
        text = text.replace(Regex("^(improved )?prompt\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\s+"), " ").trim()
        text = text.trim('"', '\'', '“', '”')
        if (text.length > MAX_CHARS) {
            // Cut at the last comma or full stop that fits, so it does not end in the middle of a phrase.
            val cut = text.take(MAX_CHARS)
            val at = maxOf(cut.lastIndexOf(','), cut.lastIndexOf('.'))
            text = if (at > MAX_CHARS / 2) cut.substring(0, at) else cut
        }
        return text
    }
}
