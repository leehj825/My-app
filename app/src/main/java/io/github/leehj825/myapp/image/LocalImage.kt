package io.github.leehj825.myapp.image

import android.util.Log

/**
 * Makes pictures on the phone from a single-file GGUF image model (Stable Diffusion style), with
 * stable-diffusion.cpp (native code in app/src/main/cpp/sd_jni.cpp).
 *
 * [libraryLoaded] is false on a phone where the native library cannot load; the Image tool then offers Gemini only.
 * All calls must come from one thread at a time (ImageActivity uses a single worker thread).
 */
object LocalImage {
    private const val TAG = "LocalImage"

    val libraryLoaded: Boolean = try {
        System.loadLibrary("myapp_sd")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "The on-device image model library is not available on this phone", e)
        false
    }

    /**
     * Loads a GGUF image model file, replacing any loaded one. Returns 0 on success.
     * [gpu] asks for the GPU; it has an effect only in a build with [hasVulkan].
     */
    external fun load(modelPath: String, threads: Int, gpu: Boolean): Int

    /** True if this build of the library has the GPU (Vulkan) backend. */
    external fun hasVulkan(): Boolean

    /** The devices the library can use, one per line. For the log. */
    external fun devices(): String

    /**
     * Makes one picture. Returns RGB pixels (width * height * 3 bytes, top row first), or null if no picture came back.
     */
    external fun generate(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        sampler: Int,
        seed: Long,
    ): ByteArray?

    /** True if a model is loaded. It stays loaded when the screen closes, so it is not loaded again. */
    external fun isLoaded(): Boolean

    /** Frees the loaded model. */
    external fun unload()
}
