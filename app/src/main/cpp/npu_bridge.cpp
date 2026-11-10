// Copyright 2026 Roy Watson. SPDX-License-Identifier: Apache-2.0
// Own C API handles only. Never reinterpret Kotlin's private JNI wrapper handles.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <array>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"

namespace {
constexpr int kInputFloats = 224 * 224 * 3;
constexpr int kOutputFloats = 1000;
void Check(LiteRtStatus status, const char* operation) {
    if (status != kLiteRtStatusOk) throw std::runtime_error(
        std::string(operation) + " failed (LiteRT status " + std::to_string(status) + ")");
}

// Resolve the version-pinned public C ABI from the runtime already packaged by Maven.
// The dlopen handle intentionally stays alive for the process, including Kotlin users.
struct Api {
    void* library = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
    template <typename T> T Load(const char* name) {
        if (!library) throw std::runtime_error("Bundled LiteRT C runtime could not be loaded");
        void* symbol = dlsym(library, name);
        if (!symbol) throw std::runtime_error(std::string("Required LiteRT 2.3.0 symbol missing: ") + name);
        return reinterpret_cast<T>(symbol);
    }
#define FN(name) decltype(&::name) name = Load<decltype(&::name)>(#name)
    FN(LiteRtCreateEnvironment); FN(LiteRtDestroyEnvironment);
    FN(LiteRtCreateModelFromFile); FN(LiteRtDestroyModel);
    FN(LiteRtCreateOptions); FN(LiteRtDestroyOptions); FN(LiteRtSetOptionsHardwareAccelerators);
    FN(LiteRtCreateCompiledModel); FN(LiteRtDestroyCompiledModel);
    FN(LiteRtCompiledModelIsNonCpuFullyAccelerated);
    FN(LiteRtGetModelSubgraph); FN(LiteRtGetNumSubgraphInputs); FN(LiteRtGetNumSubgraphOutputs);
    FN(LiteRtGetSubgraphInput); FN(LiteRtGetSubgraphOutput); FN(LiteRtGetRankedTensorType);
    FN(LiteRtGetCompiledModelInputBufferRequirements); FN(LiteRtGetCompiledModelOutputBufferRequirements);
    FN(LiteRtCreateManagedTensorBufferFromRequirements); FN(LiteRtDestroyTensorBuffer);
    FN(LiteRtLockTensorBuffer); FN(LiteRtUnlockTensorBuffer); FN(LiteRtRunCompiledModel);
#undef FN
};
Api& Runtime() { static Api api; return api; }

// Process lifetime environment prevents accelerator unload/reload on backend switches.
// Models and buffers are still independently owned and promptly destroyed.
LiteRtEnvironment Environment(const std::string& libraries, const std::string& cache) {
    static std::mutex mutex;
    static LiteRtEnvironment environment = nullptr;
    static std::string library_dir, cache_dir;
    std::lock_guard<std::mutex> guard(mutex);
    if (environment) {
        if (libraries != library_dir || cache != cache_dir) throw std::runtime_error("NPU environment paths changed");
        return environment;
    }
    library_dir = libraries; cache_dir = cache;
    std::array<LiteRtEnvOption, 4> options{};
    options[0].tag = kLiteRtEnvOptionTagCompilerPluginLibraryDir;
    options[0].value.type = kLiteRtAnyTypeString;
    options[0].value.str_value = library_dir.c_str();
    options[1].tag = kLiteRtEnvOptionTagDispatchLibraryDir;
    options[1].value.type = kLiteRtAnyTypeString;
    options[1].value.str_value = library_dir.c_str();
    options[2].tag = kLiteRtEnvOptionTagCompilerCacheDir;
    options[2].value.type = kLiteRtAnyTypeString;
    options[2].value.str_value = cache_dir.c_str();
    options[3].tag = kLiteRtEnvOptionTagAutoRegisterAccelerators;
    options[3].value.type = kLiteRtAnyTypeInt;
    options[3].value.int_value = kLiteRtHwAcceleratorNpu;
    Check(Runtime().LiteRtCreateEnvironment(options.size(), options.data(), &environment), "Create NPU environment");
    return environment;
}

struct Model {
    Api& api = Runtime();
    LiteRtModel source = nullptr;
    LiteRtOptions options = nullptr;
    LiteRtCompiledModel compiled = nullptr;
    LiteRtTensorBuffer input = nullptr, output = nullptr;
    ~Model() {
        if (output) api.LiteRtDestroyTensorBuffer(output);
        if (input) api.LiteRtDestroyTensorBuffer(input);
        if (compiled) api.LiteRtDestroyCompiledModel(compiled);
        if (options) api.LiteRtDestroyOptions(options);
        if (source) api.LiteRtDestroyModel(source);
    }
    void Open(const std::string& path, const std::string& libraries, const std::string& cache) {
        auto environment = Environment(libraries, cache);
        Check(api.LiteRtCreateModelFromFile(environment, path.c_str(), &source), "Load model");
        LiteRtSubgraph graph;
        Check(api.LiteRtGetModelSubgraph(source, 0, &graph), "Get main subgraph");
        LiteRtParamIndex count;
        Check(api.LiteRtGetNumSubgraphInputs(graph, &count), "Get input count");
        if (count != 1) throw std::runtime_error("Expected one model input");
        Check(api.LiteRtGetNumSubgraphOutputs(graph, &count), "Get output count");
        if (count != 1) throw std::runtime_error("Expected one model output");
        LiteRtTensor in_tensor, out_tensor;
        LiteRtRankedTensorType in_type{}, out_type{};
        Check(api.LiteRtGetSubgraphInput(graph, 0, &in_tensor), "Get input");
        Check(api.LiteRtGetSubgraphOutput(graph, 0, &out_tensor), "Get output");
        Check(api.LiteRtGetRankedTensorType(in_tensor, &in_type), "Get input type");
        Check(api.LiteRtGetRankedTensorType(out_tensor, &out_type), "Get output type");
        if (in_type.element_type != kLiteRtElementTypeFloat32 || in_type.layout.rank != 4 ||
            in_type.layout.dimensions[0] != 1 || in_type.layout.dimensions[1] != 224 ||
            in_type.layout.dimensions[2] != 224 || in_type.layout.dimensions[3] != 3 ||
            out_type.element_type != kLiteRtElementTypeFloat32 || out_type.layout.rank != 2 ||
            out_type.layout.dimensions[0] != 1 || out_type.layout.dimensions[1] != kOutputFloats) {
            throw std::runtime_error("Unexpected EfficientNet tensor contract");
        }
        Check(api.LiteRtCreateOptions(&options), "Create options");
        // Only NPU is selected. No GPU or CPU bits, and no Kotlin Options' implicit CPU bit.
        Check(api.LiteRtSetOptionsHardwareAccelerators(options, kLiteRtHwAcceleratorNpu), "Select NPU only");
        Check(api.LiteRtCreateCompiledModel(environment, source, options, &compiled), "Compile for Qualcomm NPU");
        bool fully_accelerated = false;
        Check(api.LiteRtCompiledModelIsNonCpuFullyAccelerated(compiled, &fully_accelerated), "Inspect non-CPU offload");
        if (!fully_accelerated) throw std::runtime_error(
            "Full NPU offload was not confirmed; CPU-only or partial execution rejected");
        __android_log_print(ANDROID_LOG_INFO, "VisionNpu", "selected=NPU_ONLY non_cpu_fully_accelerated=true");
        LiteRtTensorBufferRequirements in_req, out_req;
        Check(api.LiteRtGetCompiledModelInputBufferRequirements(compiled, 0, 0, &in_req), "Get input buffer requirements");
        Check(api.LiteRtGetCompiledModelOutputBufferRequirements(compiled, 0, 0, &out_req), "Get output buffer requirements");
        Check(api.LiteRtCreateManagedTensorBufferFromRequirements(environment, &in_type, in_req, &input), "Allocate NPU input");
        Check(api.LiteRtCreateManagedTensorBufferFromRequirements(environment, &out_type, out_req, &output), "Allocate NPU output");
    }
};

std::string String(JNIEnv* env, jstring value) {
    if (!value) throw std::runtime_error("Null native path");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Could not read native path");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
void Throw(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_roywatson_android_1litert_1object_1recog_vision_NativeNpuBridge_create(
    JNIEnv* env, jobject, jstring path, jstring libraries, jstring cache) {
    try {
        auto model = std::make_unique<Model>();
        model->Open(String(env, path), String(env, libraries), String(env, cache));
        return reinterpret_cast<jlong>(model.release());
    } catch (const std::exception& error) { Throw(env, error); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_roywatson_android_1litert_1object_1recog_vision_NativeNpuBridge_run(
    JNIEnv* env, jobject, jlong handle, jfloatArray values) {
    try {
        auto* model = reinterpret_cast<Model*>(handle);
        if (!model || !values || env->GetArrayLength(values) != kInputFloats) throw std::runtime_error("Invalid NPU input");
        void* data = nullptr;
        Check(model->api.LiteRtLockTensorBuffer(model->input, &data, kLiteRtTensorBufferLockModeWrite), "Lock input");
        env->GetFloatArrayRegion(values, 0, kInputFloats, static_cast<float*>(data));
        auto unlock = model->api.LiteRtUnlockTensorBuffer(model->input);
        if (env->ExceptionCheck()) return nullptr;
        Check(unlock, "Unlock input");
        Check(model->api.LiteRtRunCompiledModel(model->compiled, 0, 1, &model->input, 1, &model->output), "Run NPU model");
        jfloatArray result = env->NewFloatArray(kOutputFloats);
        if (!result) return nullptr;
        Check(model->api.LiteRtLockTensorBuffer(model->output, &data, kLiteRtTensorBufferLockModeRead), "Lock output");
        env->SetFloatArrayRegion(result, 0, kOutputFloats, static_cast<float*>(data));
        unlock = model->api.LiteRtUnlockTensorBuffer(model->output);
        if (env->ExceptionCheck()) return nullptr;
        Check(unlock, "Unlock output");
        return result;
    } catch (const std::exception& error) { Throw(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_roywatson_android_1litert_1object_1recog_vision_NativeNpuBridge_destroy(
    JNIEnv*, jobject, jlong handle) { delete reinterpret_cast<Model*>(handle); }
