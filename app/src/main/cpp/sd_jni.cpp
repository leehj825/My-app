// Native bridge between the app and stable-diffusion.cpp, for the on-phone Image tool.
// It runs a single-file GGUF image model (Stable Diffusion 1.x / SDXL style) on the phone, and an ESRGAN upscaler.
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>
#include "stable-diffusion.h"

#define TAG "MyAppSd"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static sd_ctx_t *g_sd = nullptr;
static upscaler_ctx_t *g_up = nullptr;

// The library's last warnings and errors, newest last. The app shows them when a model will not load.
static std::mutex g_log_mutex;
static std::deque<std::string> g_log_lines;

static void log_callback(enum sd_log_level_t level, const char *text, void * /*data*/) {
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case SD_LOG_VERBOSE: priority = ANDROID_LOG_VERBOSE; break;
        case SD_LOG_INFO: priority = ANDROID_LOG_INFO; break;
        case SD_LOG_WARN: priority = ANDROID_LOG_WARN; break;
        case SD_LOG_ERROR: priority = ANDROID_LOG_ERROR; break;
        default: break;
    }
    __android_log_print(priority, TAG, "%s", text ? text : "");
    if (level == SD_LOG_WARN || level == SD_LOG_ERROR) {
        std::string line(text ? text : "");
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        if (!line.empty()) {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_log_lines.push_back(line);
            while (g_log_lines.size() > 6) g_log_lines.pop_front();
        }
    }
}

// How far the picture being made has got. Set by the library, read by the app while it waits.
static std::atomic<int> g_step{0};
static std::atomic<int> g_steps{0};

static void progress_callback(int step, int steps, float /*time*/, void * /*data*/) {
    g_step = step;
    g_steps = steps;
}

// The sampler the app asks for (the order of its Sampler list).
static enum sample_method_t sampler_for(jint code) {
    switch (code) {
        case 1: return EULER_SAMPLE_METHOD;
        case 2: return LCM_SAMPLE_METHOD;
        case 3: return DPMPP2M_SAMPLE_METHOD;
        default: return EULER_A_SAMPLE_METHOD;
    }
}

// Creates the model. [backend] names the backend to use, or null for the library's default (the GPU when
// there is one). Only the Vulkan build ever passes a name.
static sd_ctx_t *create_context(const char *model_path, int threads, const char *backend) {
    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = model_path;
    params.n_threads = threads;
    if (backend) params.backend = backend;
    return new_sd_ctx(&params);
}

// Copies the first picture of [result] into a new byte array (RGB, top row first) and frees [result].
// Returns null if there is no usable picture.
static jbyteArray take_picture(JNIEnv *env, sd_image_generation_result_t *result) {
    if (!result) return nullptr;
    jbyteArray out = nullptr;
    if (result->count >= 1) {
        const sd_image_t &image = result->items[0].image;
        const size_t size = size_t(image.width) * image.height * image.channel;
        if (image.data && image.channel == 3 && size > 0) {
            out = env->NewByteArray(jsize(size));
            env->SetByteArrayRegion(out, 0, jsize(size), reinterpret_cast<const jbyte *>(image.data));
        }
    }
    free_sd_image_generation_result(result);
    return out;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_load(
        JNIEnv *env, jobject /*unused*/, jstring jmodel_path, jint threads, jboolean gpu) {
    if (g_sd) {
        free_sd_ctx(g_sd);
        g_sd = nullptr;
    }
    // The library reports through these two functions. Setting them again is harmless.
    sd_set_log_callback(log_callback, nullptr);
    sd_set_progress_callback(progress_callback, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_log_lines.clear();
    }

    const char *model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    LOGi("Loading image model: %s (gpu=%d)", model_path, int(gpu));

#ifdef MYAPP_SD_VULKAN
    // The default backend uses the GPU. "CPU" asks for the processor only. If that name is not accepted,
    // the default is used rather than failing.
    g_sd = create_context(model_path, threads, gpu ? nullptr : "CPU");
    if (!g_sd && !gpu) {
        LOGe("The CPU backend name was not accepted; loading with the default backend");
        g_sd = create_context(model_path, threads, nullptr);
    }
#else
    (void) gpu;
    g_sd = create_context(model_path, threads, nullptr);
#endif

    env->ReleaseStringUTFChars(jmodel_path, model_path);
    return g_sd ? 0 : 1;
}

// The library's last warnings and errors, one per line. Empty if there were none.
extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_lastError(JNIEnv *env, jobject /*unused*/) {
    std::string all;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        for (const auto &line : g_log_lines) {
            if (!all.empty()) all += '\n';
            all += line;
        }
    }
    return env->NewStringUTF(all.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_progressStep(JNIEnv * /*unused*/, jobject /*unused*/) {
    return g_step;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_progressSteps(JNIEnv * /*unused*/, jobject /*unused*/) {
    return g_steps;
}

// Asks the picture being made to stop. generate then returns null.
extern "C"
JNIEXPORT void JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_cancel(JNIEnv * /*unused*/, jobject /*unused*/) {
    if (g_sd) sd_cancel_generation(g_sd, SD_CANCEL_ALL);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_hasVulkan(JNIEnv * /*unused*/, jobject /*unused*/) {
#ifdef MYAPP_SD_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// The names of the devices the library can use, one per line, so the log shows whether the GPU is there.
extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_devices(JNIEnv *env, jobject /*unused*/) {
#ifdef MYAPP_SD_VULKAN
    const size_t needed = sd_list_devices(nullptr, 0);
    std::vector<char> buffer(needed + 1, 0);
    sd_list_devices(buffer.data(), buffer.size());
    return env->NewStringUTF(buffer.data());
#else
    return env->NewStringUTF("CPU");
#endif
}

/**
 * Makes one picture. Returns the pixels as RGB bytes (width * height * 3, top row first), or null if it failed
 * or was cancelled. Blocking: called from the worker thread only.
 */
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_generate(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jprompt,
        jstring jnegative,
        jint width,
        jint height,
        jint steps,
        jfloat cfg,
        jint sampler,
        jlong seed,
        jbyteArray jinit,
        jfloat strength
) {
    if (!g_sd) {
        LOGe("generate: no model loaded");
        return nullptr;
    }
    // Clear any earlier cancel, and start the progress from the beginning.
    sd_cancel_generation(g_sd, SD_CANCEL_RESET);
    g_step = 0;
    g_steps = 0;

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    const char *negative = env->GetStringUTFChars(jnegative, nullptr);

    sd_img_gen_params_t gen;
    sd_img_gen_params_init(&gen);
    gen.prompt = prompt;
    gen.negative_prompt = negative;
    gen.width = width;
    gen.height = height;
    gen.seed = seed;
    gen.batch_count = 1;
    sd_sample_params_init(&gen.sample_params);
    gen.sample_params.sample_method = sampler_for(sampler);
    gen.sample_params.sample_steps = steps;
    gen.sample_params.guidance.txt_cfg = cfg;

    // A start picture (RGB, the same size as the new picture) makes the new one a change of it, by [strength].
    jbyte *init_data = nullptr;
    if (jinit) {
        if (env->GetArrayLength(jinit) == jsize(width) * height * 3) {
            init_data = env->GetByteArrayElements(jinit, nullptr);
            gen.init_image.width = uint32_t(width);
            gen.init_image.height = uint32_t(height);
            gen.init_image.channel = 3;
            gen.init_image.data = reinterpret_cast<uint8_t *>(init_data);
            gen.strength = strength;
        } else {
            LOGe("generate: the start picture has the wrong size; ignoring it");
        }
    }

    sd_image_generation_result_t *result = nullptr;
    const bool ok = generate_image(g_sd, &gen, &result);

    if (init_data) env->ReleaseByteArrayElements(jinit, init_data, JNI_ABORT);
    env->ReleaseStringUTFChars(jprompt, prompt);
    env->ReleaseStringUTFChars(jnegative, negative);

    if (!ok) {
        LOGe("generate: no picture was made");
        if (result) free_sd_image_generation_result(result);
        return nullptr;
    }
    return take_picture(env, result);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_isLoaded(JNIEnv * /*unused*/, jobject /*unused*/) {
    return g_sd != nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    if (g_sd) {
        free_sd_ctx(g_sd);
        g_sd = nullptr;
    }
}

// How many times larger the upscaler model makes a picture (2, 4, ...), or 0 if the file is not an ESRGAN model.
extern "C"
JNIEXPORT jint JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_upscalerScale(JNIEnv *env, jobject /*unused*/, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const int scale = get_upscaler_model_scale(path);
    env->ReleaseStringUTFChars(jpath, path);
    return scale;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_upscalerLoad(
        JNIEnv *env, jobject /*unused*/, jstring jpath, jint threads) {
    if (g_up) {
        free_upscaler_ctx(g_up);
        g_up = nullptr;
    }
    sd_set_log_callback(log_callback, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGi("Loading upscaler: %s", path);
    // Tiles of 128 pixels keep the memory use small. The default backend is used.
    g_up = new_upscaler_ctx(path, false, threads, 128, nullptr, nullptr);
    env->ReleaseStringUTFChars(jpath, path);
    return g_up ? 0 : 1;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_upscalerLoaded(JNIEnv * /*unused*/, jobject /*unused*/) {
    return g_up != nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_upscalerUnload(JNIEnv * /*unused*/, jobject /*unused*/) {
    if (g_up) {
        free_upscaler_ctx(g_up);
        g_up = nullptr;
    }
}

/**
 * Makes a picture [factor] times larger. [jrgb] is RGB bytes (width * height * 3). Returns the larger picture
 * as RGB bytes, or null if it failed. Blocking: called from the worker thread only.
 */
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_upscale(
        JNIEnv *env, jobject /*unused*/, jbyteArray jrgb, jint width, jint height, jint factor) {
    if (!g_up) {
        LOGe("upscale: no upscaler loaded");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(jrgb);
    if (length != jsize(width) * height * 3) {
        LOGe("upscale: the picture has the wrong size");
        return nullptr;
    }
    std::vector<uint8_t> pixels(length);
    env->GetByteArrayRegion(jrgb, 0, length, reinterpret_cast<jbyte *>(pixels.data()));

    sd_image_t input;
    input.width = uint32_t(width);
    input.height = uint32_t(height);
    input.channel = 3;
    input.data = pixels.data();

    sd_image_generation_result_t *result = nullptr;
    const bool ok = upscale(g_up, input, uint32_t(factor), &result);
    if (!ok) {
        LOGe("upscale: no picture was made");
        if (result) free_sd_image_generation_result(result);
        return nullptr;
    }
    return take_picture(env, result);
}
