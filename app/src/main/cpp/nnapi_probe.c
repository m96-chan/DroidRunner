// NNAPI probe: enumerates accelerator devices and runs a tiny ADD model to
// prove an accelerator actually executes work. libneuralnetworks.so is
// dlopen'd so the app still loads on devices/OS versions without NNAPI
// (deprecated in Android 15 but present and driver-backed on vendor SoCs).
#include <jni.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

typedef struct ANeuralNetworksDevice ANeuralNetworksDevice;
typedef struct ANeuralNetworksModel ANeuralNetworksModel;
typedef struct ANeuralNetworksCompilation ANeuralNetworksCompilation;
typedef struct ANeuralNetworksExecution ANeuralNetworksExecution;

typedef struct {
    int32_t type;
    uint32_t dimensionCount;
    const uint32_t* dimensions;
    float scale;
    int32_t zeroPoint;
} OperandType;

// OperandCode / OperationCode / FuseCode constants from NeuralNetworks.h.
#define OP_INT32 1
#define OP_TENSOR_FLOAT32 3
#define OP_ADD 0
#define OP_CONV_2D 3
#define FUSE_NONE 0
#define FUSE_RELU 1
#define PREFER_SUSTAINED_SPEED 2

static void* g_lib;
static int (*p_getDeviceCount)(uint32_t*);
static int (*p_getDevice)(uint32_t, ANeuralNetworksDevice**);
static int (*p_devName)(const ANeuralNetworksDevice*, const char**);
static int (*p_devType)(const ANeuralNetworksDevice*, int32_t*);
static int (*p_devVersion)(const ANeuralNetworksDevice*, const char**);
static int (*p_devFeatureLevel)(const ANeuralNetworksDevice*, int64_t*);
static int (*p_modelCreate)(ANeuralNetworksModel**);
static void (*p_modelFree)(ANeuralNetworksModel*);
static int (*p_addOperand)(ANeuralNetworksModel*, const OperandType*);
static int (*p_setOperandValue)(ANeuralNetworksModel*, int32_t, const void*, size_t);
static int (*p_addOperation)(ANeuralNetworksModel*, int32_t, uint32_t, const uint32_t*, uint32_t, const uint32_t*);
static int (*p_identifyIO)(ANeuralNetworksModel*, uint32_t, const uint32_t*, uint32_t, const uint32_t*);
static int (*p_modelFinish)(ANeuralNetworksModel*);
static int (*p_compCreate)(ANeuralNetworksModel*, ANeuralNetworksCompilation**);
static int (*p_compCreateForDevices)(ANeuralNetworksModel*, const ANeuralNetworksDevice* const*, uint32_t, ANeuralNetworksCompilation**);
static int (*p_compSetPreference)(ANeuralNetworksCompilation*, int32_t);
static int (*p_compFinish)(ANeuralNetworksCompilation*);
static int (*p_modelSupportedOps)(ANeuralNetworksModel*, const ANeuralNetworksDevice* const*, uint32_t, bool*);
static void (*p_compFree)(ANeuralNetworksCompilation*);
static int (*p_execCreate)(ANeuralNetworksCompilation*, ANeuralNetworksExecution**);
static void (*p_execFree)(ANeuralNetworksExecution*);
static int (*p_setInput)(ANeuralNetworksExecution*, int32_t, const OperandType*, const void*, size_t);
static int (*p_setOutput)(ANeuralNetworksExecution*, int32_t, const OperandType*, void*, size_t);
static int (*p_compute)(ANeuralNetworksExecution*);

#define LOAD(sym, var) do { var = dlsym(g_lib, sym); } while (0)

static int ensure_lib(void) {
    if (g_lib) return 1;
    g_lib = dlopen("libneuralnetworks.so", RTLD_NOW);
    if (!g_lib) return 0;
    LOAD("ANeuralNetworks_getDeviceCount", p_getDeviceCount);
    LOAD("ANeuralNetworks_getDevice", p_getDevice);
    LOAD("ANeuralNetworksDevice_getName", p_devName);
    LOAD("ANeuralNetworksDevice_getType", p_devType);
    LOAD("ANeuralNetworksDevice_getVersion", p_devVersion);
    LOAD("ANeuralNetworksDevice_getFeatureLevel", p_devFeatureLevel);
    LOAD("ANeuralNetworksModel_create", p_modelCreate);
    LOAD("ANeuralNetworksModel_free", p_modelFree);
    LOAD("ANeuralNetworksModel_addOperand", p_addOperand);
    LOAD("ANeuralNetworksModel_setOperandValue", p_setOperandValue);
    LOAD("ANeuralNetworksModel_addOperation", p_addOperation);
    LOAD("ANeuralNetworksModel_identifyInputsAndOutputs", p_identifyIO);
    LOAD("ANeuralNetworksModel_finish", p_modelFinish);
    LOAD("ANeuralNetworksCompilation_create", p_compCreate);
    LOAD("ANeuralNetworksCompilation_createForDevices", p_compCreateForDevices);
    LOAD("ANeuralNetworksCompilation_setPreference", p_compSetPreference);
    LOAD("ANeuralNetworksCompilation_finish", p_compFinish);
    LOAD("ANeuralNetworksModel_getSupportedOperationsForDevices", p_modelSupportedOps);
    LOAD("ANeuralNetworksCompilation_free", p_compFree);
    LOAD("ANeuralNetworksExecution_create", p_execCreate);
    LOAD("ANeuralNetworksExecution_free", p_execFree);
    LOAD("ANeuralNetworksExecution_setInput", p_setInput);
    LOAD("ANeuralNetworksExecution_setOutput", p_setOutput);
    LOAD("ANeuralNetworksExecution_compute", p_compute);
    return p_getDeviceCount && p_getDevice && p_devName && p_modelCreate;
}

static const char* device_type_name(int32_t type) {
    switch (type) {
        case 1: return "other";
        case 2: return "cpu";
        case 3: return "gpu";
        case 4: return "accelerator";
        default: return "unknown";
    }
}

JNIEXPORT jstring JNICALL
Java_dev_devenus_droidrunner_npu_NnapiProbe_devicesJson(JNIEnv* env, jobject thiz) {
    (void) thiz;
    static char out[8192];
    if (!ensure_lib()) {
        return (*env)->NewStringUTF(env, "{\"available\":false,\"error\":\"libneuralnetworks unavailable\"}");
    }
    uint32_t count = 0;
    if (p_getDeviceCount(&count) != 0) {
        return (*env)->NewStringUTF(env, "{\"available\":false,\"error\":\"getDeviceCount failed\"}");
    }
    size_t off = 0;
    off += snprintf(out + off, sizeof(out) - off, "{\"available\":true,\"devices\":[");
    for (uint32_t i = 0; i < count && off < sizeof(out) - 256; i++) {
        ANeuralNetworksDevice* device = NULL;
        if (p_getDevice(i, &device) != 0 || !device) continue;
        const char* name = "?";
        const char* version = "?";
        int32_t type = 0;
        int64_t feature = -1;
        p_devName(device, &name);
        if (p_devVersion) p_devVersion(device, &version);
        if (p_devType) p_devType(device, &type);
        if (p_devFeatureLevel) p_devFeatureLevel(device, &feature);
        off += snprintf(out + off, sizeof(out) - off,
            "%s{\"name\":\"%s\",\"type\":\"%s\",\"version\":\"%s\",\"featureLevel\":%lld}",
            i == 0 ? "" : ",", name, device_type_name(type), version, (long long) feature);
    }
    snprintf(out + off, sizeof(out) - off, "]}");
    return (*env)->NewStringUTF(env, out);
}

// Finds an NNAPI device by name; NULL when absent.
static ANeuralNetworksDevice* find_device(const char* wanted) {
    uint32_t count = 0;
    if (!wanted || p_getDeviceCount(&count) != 0) return NULL;
    for (uint32_t i = 0; i < count; i++) {
        ANeuralNetworksDevice* device = NULL;
        const char* name = "";
        if (p_getDevice(i, &device) == 0 && p_devName(device, &name) == 0 &&
            strcmp(name, wanted) == 0) {
            return device;
        }
    }
    return NULL;
}

/**
 * CONV_2D benchmark: a real convolution is the workload vendor NPUs are built
 * for (unlike a trivial ADD, which they may refuse or lose to the CPU on).
 * Shape: input 1x H x W x C_in, weights C_out x 3 x 3 x C_in, SAME padding,
 * stride 1, RELU.
 */
JNIEXPORT jstring JNICALL
Java_dev_devenus_droidrunner_npu_NnapiProbe_convBenchmark(
        JNIEnv* env, jobject thiz, jstring jDeviceName, jint iterations,
        jint size, jint channels, jint filters) {
    (void) thiz;
    static char out[1024];
    if (!ensure_lib() || !p_modelCreate || !p_compute) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"NNAPI unavailable\"}");
    }
    if (iterations < 1) iterations = 1;
    if (iterations > 500) iterations = 500;
    if (size < 8) size = 8;
    if (size > 256) size = 256;
    if (channels < 1) channels = 1;
    if (channels > 64) channels = 64;
    if (filters < 1) filters = 1;
    if (filters > 64) filters = 64;

    const char* wantedName = NULL;
    if (jDeviceName) wantedName = (*env)->GetStringUTFChars(env, jDeviceName, NULL);

    const uint32_t inDims[4] = { 1, (uint32_t) size, (uint32_t) size, (uint32_t) channels };
    const uint32_t wDims[4] = { (uint32_t) filters, 3, 3, (uint32_t) channels };
    const uint32_t bDims[1] = { (uint32_t) filters };
    const uint32_t outDims[4] = { 1, (uint32_t) size, (uint32_t) size, (uint32_t) filters };

    OperandType inType = { OP_TENSOR_FLOAT32, 4, inDims, 0.0f, 0 };
    OperandType wType = { OP_TENSOR_FLOAT32, 4, wDims, 0.0f, 0 };
    OperandType bType = { OP_TENSOR_FLOAT32, 1, bDims, 0.0f, 0 };
    OperandType outType = { OP_TENSOR_FLOAT32, 4, outDims, 0.0f, 0 };
    OperandType scalar = { OP_INT32, 0, NULL, 0.0f, 0 };

    size_t inCount = (size_t) size * size * channels;
    size_t wCount = (size_t) filters * 3 * 3 * channels;
    size_t outCount = (size_t) size * size * filters;
    float* input = malloc(inCount * sizeof(float));
    float* weights = malloc(wCount * sizeof(float));
    float* bias = malloc((size_t) filters * sizeof(float));
    float* output = malloc(outCount * sizeof(float));
    if (!input || !weights || !bias || !output) {
        free(input); free(weights); free(bias); free(output);
        if (wantedName) (*env)->ReleaseStringUTFChars(env, jDeviceName, wantedName);
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"out of memory\"}");
    }
    for (size_t i = 0; i < inCount; i++) input[i] = (float) ((i % 17) - 8) * 0.1f;
    for (size_t i = 0; i < wCount; i++) weights[i] = (float) ((i % 7) - 3) * 0.05f;
    for (int i = 0; i < filters; i++) bias[i] = 0.01f * i;

    ANeuralNetworksModel* model = NULL;
    ANeuralNetworksCompilation* compilation = NULL;
    const char* err = NULL;
    const char* usedDevice = "default";
    double avgUs = 0;
    int supported = -1;

    do {
        if (p_modelCreate(&model) != 0) { err = "model_create"; break; }
        // Operands: 0 input, 1 weights, 2 bias, 3-6 padding, 7-8 stride,
        // 9 fused activation, 10 output.
        if (p_addOperand(model, &inType) != 0 || p_addOperand(model, &wType) != 0 ||
            p_addOperand(model, &bType) != 0) { err = "add_operand"; break; }
        // 7 scalars: padding left/right/top/bottom, stride w/h, fused activation.
        for (int i = 0; i < 7; i++) {
            if (p_addOperand(model, &scalar) != 0) { err = "add_scalar"; break; }
        }
        if (err) break;
        if (p_addOperand(model, &outType) != 0) { err = "add_output"; break; }

        if (p_setOperandValue(model, 1, weights, wCount * sizeof(float)) != 0 ||
            p_setOperandValue(model, 2, bias, (size_t) filters * sizeof(float)) != 0) {
            err = "set_weights"; break;
        }
        int32_t pad = 1, stride = 1, fuse = FUSE_RELU;
        int32_t padValues[4] = { pad, pad, pad, pad };  // left, right, top, bottom
        int ok = 1;
        for (int i = 0; i < 4; i++) {
            ok &= p_setOperandValue(model, 3 + i, &padValues[i], sizeof(int32_t)) == 0;
        }
        ok &= p_setOperandValue(model, 7, &stride, sizeof(stride)) == 0;
        ok &= p_setOperandValue(model, 8, &stride, sizeof(stride)) == 0;
        ok &= p_setOperandValue(model, 9, &fuse, sizeof(fuse)) == 0;
        if (!ok) { err = "set_scalars"; break; }

        uint32_t convIns[10] = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        uint32_t convOuts[1] = { 10 };  // operand 10 is the output tensor
        if (p_addOperation(model, OP_CONV_2D, 10, convIns, 1, convOuts) != 0) {
            err = "add_operation"; break;
        }
        uint32_t modelIns[1] = { 0 };
        if (p_identifyIO(model, 1, modelIns, 1, convOuts) != 0) { err = "identify_io"; break; }
        if (p_modelFinish(model) != 0) { err = "model_finish"; break; }

        if (wantedName && p_compCreateForDevices) {
            ANeuralNetworksDevice* chosen = find_device(wantedName);
            if (!chosen) { err = "device_not_found"; break; }
            const ANeuralNetworksDevice* list[1] = { chosen };
            if (p_modelSupportedOps) {
                bool flags[1] = { false };
                if (p_modelSupportedOps(model, list, 1, flags) == 0) supported = flags[0] ? 1 : 0;
            }
            if (p_compCreateForDevices(model, list, 1, &compilation) != 0) {
                err = "compilation_for_device"; break;
            }
            usedDevice = wantedName;
        } else {
            if (p_compCreate(model, &compilation) != 0) { err = "compilation_create"; break; }
        }
        if (p_compSetPreference) p_compSetPreference(compilation, PREFER_SUSTAINED_SPEED);
        if (p_compFinish(compilation) != 0) { err = "compilation_finish"; break; }

        // Warm-up execution keeps driver init out of the timed loop.
        for (int warm = 0; warm < 2; warm++) {
            ANeuralNetworksExecution* execution = NULL;
            if (p_execCreate(compilation, &execution) != 0) { err = "execution_create"; break; }
            if (p_setInput(execution, 0, NULL, input, inCount * sizeof(float)) != 0 ||
                p_setOutput(execution, 0, NULL, output, outCount * sizeof(float)) != 0 ||
                p_compute(execution) != 0) {
                err = "compute_warmup";
                p_execFree(execution);
                break;
            }
            p_execFree(execution);
        }
        if (err) break;

        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        for (int iter = 0; iter < iterations; iter++) {
            ANeuralNetworksExecution* execution = NULL;
            if (p_execCreate(compilation, &execution) != 0) { err = "execution_create"; break; }
            if (p_setInput(execution, 0, NULL, input, inCount * sizeof(float)) != 0 ||
                p_setOutput(execution, 0, NULL, output, outCount * sizeof(float)) != 0 ||
                p_compute(execution) != 0) {
                err = "compute";
                p_execFree(execution);
                break;
            }
            p_execFree(execution);
        }
        if (err) break;
        clock_gettime(CLOCK_MONOTONIC, &t1);
        avgUs = ((t1.tv_sec - t0.tv_sec) * 1e6 + (t1.tv_nsec - t0.tv_nsec) / 1e3) / iterations;
    } while (0);

    if (err) {
        snprintf(out, sizeof(out), "{\"ok\":false,\"error\":\"%s\",\"device\":\"%s\"}",
                 err, wantedName ? wantedName : "default");
    } else {
        // 2 * K*K * Cin * Cout * H * W flops for the convolution.
        double gflops = 2.0 * 9.0 * channels * filters * size * size / (avgUs * 1e3);
        snprintf(out, sizeof(out),
                 "{\"ok\":true,\"device\":\"%s\",\"op\":\"CONV_2D %dx%dx%d -> %d filters\","
                 "\"iterations\":%d,\"avgUs\":%.1f,\"gflops\":%.2f,\"supported\":%s}",
                 usedDevice, size, size, channels, filters, (int) iterations, avgUs, gflops,
                 supported < 0 ? "null" : (supported ? "true" : "false"));
    }

    if (compilation) p_compFree(compilation);
    if (model) p_modelFree(model);
    free(input); free(weights); free(bias); free(output);
    if (wantedName) (*env)->ReleaseStringUTFChars(env, jDeviceName, wantedName);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jstring JNICALL
Java_dev_devenus_droidrunner_npu_NnapiProbe_addBenchmark(
        JNIEnv* env, jobject thiz, jstring jDeviceName, jint iterations) {
    (void) thiz;
    static char out[1024];
    if (!ensure_lib() || !p_modelCreate || !p_compute) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"NNAPI unavailable\"}");
    }
    if (iterations < 1) iterations = 1;
    if (iterations > 1000) iterations = 1000;

    const char* wantedName = NULL;
    if (jDeviceName) wantedName = (*env)->GetStringUTFChars(env, jDeviceName, NULL);

    enum { N = 1024 };
    static const uint32_t dims[1] = { N };
    OperandType tensor = { OP_TENSOR_FLOAT32, 1, dims, 0.0f, 0 };
    OperandType scalar = { OP_INT32, 0, NULL, 0.0f, 0 };
    static float a[N], b[N], sum[N];
    for (int i = 0; i < N; i++) { a[i] = (float) i; b[i] = 2.0f; }

    ANeuralNetworksModel* model = NULL;
    ANeuralNetworksCompilation* compilation = NULL;
    const char* usedDevice = "default";
    const char* err = NULL;
    double avgUs = 0;
    int correct = 0;

    do {
        if (p_modelCreate(&model) != 0) { err = "model_create"; break; }
        if (p_addOperand(model, &tensor) != 0 ||   // 0: a
            p_addOperand(model, &tensor) != 0 ||   // 1: b
            p_addOperand(model, &scalar) != 0 ||   // 2: fuse activation
            p_addOperand(model, &tensor) != 0) {   // 3: out
            err = "add_operand"; break;
        }
        int32_t fuse = FUSE_NONE;
        if (p_setOperandValue(model, 2, &fuse, sizeof(fuse)) != 0) { err = "set_operand"; break; }
        uint32_t opIns[3] = { 0, 1, 2 };
        uint32_t opOuts[1] = { 3 };
        if (p_addOperation(model, OP_ADD, 3, opIns, 1, opOuts) != 0) { err = "add_operation"; break; }
        uint32_t modelIns[2] = { 0, 1 };
        if (p_identifyIO(model, 2, modelIns, 1, opOuts) != 0) { err = "identify_io"; break; }
        if (p_modelFinish(model) != 0) { err = "model_finish"; break; }

        // Pick the requested device, if any.
        if (wantedName && p_compCreateForDevices) {
            uint32_t count = 0;
            p_getDeviceCount(&count);
            ANeuralNetworksDevice* chosen = NULL;
            for (uint32_t i = 0; i < count; i++) {
                ANeuralNetworksDevice* device = NULL;
                const char* name = "";
                if (p_getDevice(i, &device) == 0 && p_devName(device, &name) == 0 &&
                    strcmp(name, wantedName) == 0) {
                    chosen = device;
                    break;
                }
            }
            if (!chosen) { err = "device_not_found"; break; }
            const ANeuralNetworksDevice* list[1] = { chosen };
            if (p_compCreateForDevices(model, list, 1, &compilation) != 0) { err = "compilation_for_device"; break; }
            usedDevice = wantedName;
        } else {
            if (p_compCreate(model, &compilation) != 0) { err = "compilation_create"; break; }
        }
        if (p_compFinish(compilation) != 0) { err = "compilation_finish"; break; }

        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        for (int iter = 0; iter < iterations; iter++) {
            ANeuralNetworksExecution* execution = NULL;
            if (p_execCreate(compilation, &execution) != 0) { err = "execution_create"; break; }
            if (p_setInput(execution, 0, NULL, a, sizeof(a)) != 0 ||
                p_setInput(execution, 1, NULL, b, sizeof(b)) != 0 ||
                p_setOutput(execution, 0, NULL, sum, sizeof(sum)) != 0 ||
                p_compute(execution) != 0) {
                err = "compute";
                p_execFree(execution);
                break;
            }
            p_execFree(execution);
        }
        if (err) break;
        clock_gettime(CLOCK_MONOTONIC, &t1);
        avgUs = ((t1.tv_sec - t0.tv_sec) * 1e6 + (t1.tv_nsec - t0.tv_nsec) / 1e3) / iterations;
        correct = (sum[0] == 2.0f && sum[100] == 102.0f && sum[N - 1] == (float) (N - 1) + 2.0f);
    } while (0);

    if (err) {
        snprintf(out, sizeof(out), "{\"ok\":false,\"error\":\"%s\",\"device\":\"%s\"}",
                 err, wantedName ? wantedName : "default");
    } else {
        snprintf(out, sizeof(out),
                 "{\"ok\":true,\"device\":\"%s\",\"op\":\"ADD float32[%d]\",\"iterations\":%d,"
                 "\"avgUs\":%.1f,\"correct\":%s}",
                 usedDevice, N, (int) iterations, avgUs, correct ? "true" : "false");
    }

    if (compilation) p_compFree(compilation);
    if (model) p_modelFree(model);
    if (wantedName) (*env)->ReleaseStringUTFChars(env, jDeviceName, wantedName);
    return (*env)->NewStringUTF(env, out);
}
