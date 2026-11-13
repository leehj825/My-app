// Native bridge between the app and stable-diffusion.cpp, for the on-phone Image tool.
// It runs a single-file GGUF image model (Stable Diffusion 1.x / SDXL style) on the phone.
#include <android/log.h>
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <vector>
#include "stable-diffusion.h"

#define TAG "MyAppSd"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static sd_ctx_t *g_sd = nullptr;

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

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_leehj825_myapp_image_LocalImage_load(
        JNIEnv *env, jobject /*unused*/, jstring jmodel_path, jint threads, jboolean gpu) {
    if (g_sd) {
        free_sd_ctx(g_sd);
        g_sd = nullptr;
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
 * Makes one picture. Returns the pixels as RGB bytes (width * height * 3, top row first), or null if it failed.
 * Blocking: called from the worker thread only.
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
        jlong seed
) {
    if (!g_sd) {
        LOGe("generate: no model loaded");
        return nullptr;
    }
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

    sd_image_generation_result_t *result = nullptr;
    const bool ok = generate_image(g_sd, &gen, &result);

    env->ReleaseStringUTFChars(jprompt, prompt);
    env->ReleaseStringUTFChars(jnegative, negative);

    if (!ok || !result || result->count < 1) {
        LOGe("generate: no picture was made");
        if (result) free_sd_image_generation_result(result);
        return nullptr;
    }

    const sd_image_t &image = result->items[0].image;
    const size_t size = size_t(image.width) * image.height * image.channel;
    jbyteArray out = nullptr;
    if (image.data && image.channel == 3 && size > 0) {
        out = env->NewByteArray(jsize(size));
        env->SetByteArrayRegion(out, 0, jsize(size), reinterpret_cast<const jbyte *>(image.data));
    }
    free_sd_image_generation_result(result);
    return out;
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
