package io.github.leehj825.myapp.chat

import android.util.Log

/**
 * Runs a GGUF language model on the phone with llama.cpp (native code in app/src/main/cpp).
 *
 * [libraryLoaded] is false on a phone where the native library cannot load; the Chat tool is hidden then.
 * All calls must come from one thread at a time (ChatActivity uses a single worker thread).
 */
object LlmEngine {
    private const val TAG = "LlmEngine"

    val libraryLoaded: Boolean = try {
        System.loadLibrary("myapp_llm")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "The on-device language model library is not available on this phone", e)
        false
    }

    /** Starts the llama.cpp backend. Call once before [load]. */
    external fun init()

    /** Loads a GGUF model file. Returns 0 on success. */
    external fun load(modelPath: String): Int

    /** Creates the context for the loaded model. Returns 0 on success. */
    external fun prepare(): Int

    /** Sets the instructions for the assistant and resets the conversation. Returns 0 on success. */
    external fun processSystemPrompt(prompt: String): Int

    /** Adds the user's message and gets ready to reply. Returns 0 on success. */
    external fun processUserPrompt(prompt: String, nPredict: Int): Int

    /** The next piece of the reply, or null when the reply is finished. */
    external fun generateNextToken(): String?

    /** The model's name and size, from the model file. Empty if no model is loaded. */
    external fun modelInfo(): String

    /** Frees the model and the conversation. */
    external fun unload()

    /** Shuts the backend down. */
    external fun shutdown()
}
