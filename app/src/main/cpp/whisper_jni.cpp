#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <exception>
#include "ggml-backend.h"
#include "whisper.h"

namespace {
struct Engine {
    whisper_context* context = nullptr;
    bool usesGpu = false;
    std::mutex mutex;
    std::atomic_bool cancelled{false};
};

Engine* fromHandle(jlong handle) { return reinterpret_cast<Engine*>(handle); }
bool shouldAbort(void* userData) {
    return static_cast<Engine*>(userData)->cancelled.load();
}
bool gpuBackendAvailable() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        const auto type = ggml_backend_dev_type(ggml_backend_dev_get(i));
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) return true;
    }
    return false;
}
bool containsIgnoreCase(const char* value, const char* needle) {
    if (!value) return false;
    std::string haystack(value);
    std::string target(needle);
    std::transform(haystack.begin(), haystack.end(), haystack.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    std::transform(target.begin(), target.end(), target.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return haystack.find(target) != std::string::npos;
}
bool hasUnsafeAdrenoDriver() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        const auto device = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(device);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        if (containsIgnoreCase(ggml_backend_dev_name(device), "adreno") ||
            containsIgnoreCase(ggml_backend_dev_description(device), "adreno")) {
            return true;
        }
    }
    return false;
}
std::string safeText(const char* raw) {
    std::string text = raw ? raw : "";
    for (char& value : text) {
        if (value == '\x1e' || value == '\x1f' || value == '\n' || value == '\r' || value == '\t') value = ' ';
    }
    return text;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_listener_app_speech_JniWhisperEngine_nativeCreate(JNIEnv* env, jobject, jstring path, jboolean preferGpu) {
    const char* modelPath = env->GetStringUTFChars(path, nullptr);
    auto* engine = new Engine();
    try {
        ggml_backend_load_all();
        if (preferGpu == JNI_TRUE && hasUnsafeAdrenoDriver()) {
            __android_log_print(
                ANDROID_LOG_WARN,
                "ListenerWhisper",
                "Skipping Vulkan: the current whisper.cpp Vulkan backend can crash in the Adreno shader compiler"
            );
            env->ReleaseStringUTFChars(path, modelPath);
            delete engine;
            return 0;
        }
        whisper_context_params contextParams = whisper_context_default_params();
        contextParams.use_gpu = preferGpu == JNI_TRUE;
        contextParams.flash_attn = false;
        engine->context = whisper_init_from_file_with_params(modelPath, contextParams);
        engine->usesGpu = engine->context && preferGpu == JNI_TRUE && gpuBackendAvailable();
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_WARN, "ListenerWhisper", "Backend initialization failed: %s", error.what());
        engine->context = nullptr;
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, "ListenerWhisper", "Backend initialization failed");
        engine->context = nullptr;
    }
    env->ReleaseStringUTFChars(path, modelPath);
    if (!engine->context) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_listener_app_speech_JniWhisperEngine_nativeUsesGpu(JNIEnv*, jobject, jlong handle) {
    Engine* engine = fromHandle(handle);
    return engine && engine->usesGpu ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_listener_app_speech_JniWhisperEngine_nativeTranscribe(JNIEnv* env, jobject, jlong handle, jshortArray pcm, jstring language, jstring prompt) {
    Engine* engine = fromHandle(handle);
    if (!engine || !engine->context) return nullptr;
    const jsize count = env->GetArrayLength(pcm);
    std::vector<jshort> shorts(static_cast<size_t>(count));
    env->GetShortArrayRegion(pcm, 0, count, shorts.data());
    std::vector<float> samples(static_cast<size_t>(count));
    std::transform(shorts.begin(), shorts.end(), samples.begin(), [](jshort value) { return static_cast<float>(value) / 32768.0f; });
    const char* languageCode = env->GetStringUTFChars(language, nullptr);
    const char* promptText = env->GetStringUTFChars(prompt, nullptr);

    std::lock_guard<std::mutex> guard(engine->mutex);
    engine->cancelled.store(false);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = languageCode;
    params.translate = false;
    params.no_context = true;
    params.initial_prompt = promptText;
    params.carry_initial_prompt = true;
    params.audio_ctx = std::max(150, std::min(1500, static_cast<int>((count + 319) / 320)));
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    params.abort_callback = shouldAbort;
    params.abort_callback_user_data = engine;
    const int result = whisper_full(engine->context, params, samples.data(), static_cast<int>(samples.size()));
    env->ReleaseStringUTFChars(language, languageCode);
    env->ReleaseStringUTFChars(prompt, promptText);
    if (result != 0) return nullptr;

    std::string payload;
    const int segmentCount = whisper_full_n_segments(engine->context);
    for (int i = 0; i < segmentCount; ++i) {
        if (!payload.empty()) payload.push_back('\x1e');
        payload += std::to_string(whisper_full_get_segment_t0(engine->context, i) * 10);
        payload.push_back('\x1f');
        payload += std::to_string(whisper_full_get_segment_t1(engine->context, i) * 10);
        payload.push_back('\x1f');
        payload += safeText(whisper_full_get_segment_text(engine->context, i));
    }
    return env->NewStringUTF(payload.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_listener_app_speech_JniWhisperEngine_nativeCancel(JNIEnv*, jobject, jlong handle) {
    Engine* engine = fromHandle(handle);
    if (engine) engine->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_listener_app_speech_JniWhisperEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    Engine* engine = fromHandle(handle);
    if (!engine) return;
    {
        std::lock_guard<std::mutex> guard(engine->mutex);
        if (engine->context) whisper_free(engine->context);
        engine->context = nullptr;
    }
    delete engine;
}
