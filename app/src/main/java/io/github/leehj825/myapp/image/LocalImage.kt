package io.github.leehj825.myapp.image

import android.util.Log

/**
 * Makes pictures on the phone from a single-file GGUF image model (Stable Diffusion style), and makes pictures
 * larger with an ESRGAN upscaler, with stable-diffusion.cpp (native code in app/src/main/cpp/sd_jni.cpp).
 *
 * [libraryLoaded] is false on a phone where the native library cannot load; the Image tool then offers Gemini only.
 * Calls that load or run a model must come from one thread at a time (ImageActivity uses a single worker thread).
 * [progressStep], [progressSteps] and [cancel] may be called from any thread while a picture is being made.
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

    /** The library's last warnings and errors, one per line. Empty if there were none. Shown when a load fails. */
    external fun lastError(): String

    /** True if this build of the library has the GPU (Vulkan) backend. */
    external fun hasVulkan(): Boolean

    /** The devices the library can use, one per line. For the log. */
    external fun devices(): String

    /**
     * Makes one picture. Returns RGB pixels (width * height * 3 bytes, top row first), or null if no picture came
     * back (it failed, or [cancel] was called). With [init], RGB pixels of the same size, the new picture is made
     * from that picture, changed by [strength] (0 to 1; 1 changes it completely).
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
        init: ByteArray?,
        strength: Float,
    ): ByteArray?

    /** The step the picture being made has reached, 0 before the first step. */
    external fun progressStep(): Int

    /** The number of steps of the picture being made, 0 before it is known. */
    external fun progressSteps(): Int

    /** Asks the picture being made to stop. */
    external fun cancel()

    /** True if a model is loaded. It stays loaded when the screen closes, so it is not loaded again. */
    external fun isLoaded(): Boolean

    /** Frees the loaded model. */
    external fun unload()

    /** How many times larger the upscaler model in this file makes a picture, or 0 if it is not an ESRGAN model. */
    external fun upscalerScale(modelPath: String): Int

    /** Loads an ESRGAN upscaler model file, replacing any loaded one. Returns 0 on success. */
    external fun upscalerLoad(modelPath: String, threads: Int): Int

    /** True if an upscaler is loaded. */
    external fun upscalerLoaded(): Boolean

    /** Frees the loaded upscaler. */
    external fun upscalerUnload()

    /**
     * Makes a picture [factor] times larger. [rgb] is width * height * 3 bytes. Returns RGB pixels of the larger
     * picture, or null if it failed.
     */
    external fun upscale(rgb: ByteArray, width: Int, height: Int, factor: Int): ByteArray?
}
