// NNAPI probe: enumerates accelerator devices and runs a tiny ADD model to
// prove an accelerator actually executes work. libneuralnetworks.so is
// dlopen'd so the app still loads on devices/OS versions without NNAPI
// (deprecated in Android 15 but present and driver-backed on vendor SoCs).
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
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
#define FUSE_NONE 0

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
static int (*p_compFinish)(ANeuralNetworksCompilation*);
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
    LOAD("ANeuralNetworksCompilation_finish", p_compFinish);
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
