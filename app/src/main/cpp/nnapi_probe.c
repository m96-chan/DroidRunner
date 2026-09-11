// NNAPI probe: enumerates accelerator devices and runs a tiny ADD model to
// prove an accelerator actually executes work. libneuralnetworks.so is
// dlopen'd so the app still loads on devices/OS versions without NNAPI
// (deprecated in Android 15 but present and driver-backed on vendor SoCs).
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <fcntl.h>
#include <unistd.h>

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

// The symbol table above is filled in once and read by everyone afterwards,
// and two agent workers can arrive here at the same moment (issue #197).
// dlopen itself is safe to call twice and returns the same handle, but the
// pointers are not: a second thread that found `g_lib` already set would
// return success while the first was still resolving symbols, and then call
// through a pointer that is still NULL. Loading under a lock makes the table
// either untouched or complete to anybody else.
//
// A mutex rather than pthread_once, because a load that failed is not a
// verdict: nothing caches the failure today and a later call is free to try
// again. Both exits answer the same question — are the four symbols every
// caller needs actually here — so a second call cannot report success for a
// library that only half resolved.
static pthread_mutex_t g_lib_lock = PTHREAD_MUTEX_INITIALIZER;

static int resolved(void) {
    return p_getDeviceCount && p_getDevice && p_devName && p_modelCreate;
}

static int ensure_lib(void) {
    pthread_mutex_lock(&g_lib_lock);
    if (g_lib) {
        int already = resolved();
        pthread_mutex_unlock(&g_lib_lock);
        return already;
    }
    g_lib = dlopen("libneuralnetworks.so", RTLD_NOW);
    if (!g_lib) {
        pthread_mutex_unlock(&g_lib_lock);
        return 0;
    }
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
    int ok = resolved();
    pthread_mutex_unlock(&g_lib_lock);
    return ok;
}


/** ANeuralNetworksResult names, so a rejection says why rather than where. */
static const char* result_name(int code) {
    switch (code) {
        case 0: return "NO_ERROR";
        case 1: return "OUT_OF_MEMORY";
        case 2: return "INCOMPLETE";
        case 3: return "UNEXPECTED_NULL";
        case 4: return "BAD_DATA";
        case 5: return "OP_FAILED";
        case 6: return "BAD_STATE";
        case 7: return "UNMAPPABLE";
        case 8: return "OUTPUT_INSUFFICIENT_SIZE";
        case 9: return "UNAVAILABLE_DEVICE";
        case 10: return "MISSED_DEADLINE_TRANSIENT";
        case 11: return "MISSED_DEADLINE_PERSISTENT";
        case 12: return "RESOURCE_EXHAUSTED_TRANSIENT";
        case 13: return "RESOURCE_EXHAUSTED_PERSISTENT";
        case 14: return "DEAD_OBJECT";
        default: return "UNKNOWN";
    }
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

// Bounded JSON assembly (issues #197, #198).
//
// Every reply here used to be built with `off += snprintf(out + off,
// sizeof(out) - off, ...)`. snprintf returns the length it *would* have
// written, not the length it wrote, so one long driver name pushed `off` past
// the end of the buffer — and the next call then took `out + off` for its
// destination and `sizeof(out) - off` for its bound, which is a size_t and so
// an enormous positive number rather than a negative one. The loop guard
// bounded the loop; nothing bounded the write after it.
//
// So nothing below ever advances a cursor by a length a write did not
// actually produce. A json_buf carries its own capacity and its own cursor,
// every append works out the room it needs before it writes anything, and
// `at` stays inside [0, cap) for the life of the buffer, with a NUL at `at`
// after every call. That last part is what makes a truncated reply still a
// reply.
//
// An append that does not fit writes nothing at all and sets `truncated`,
// rather than writing the part that fits: half of a `\"` is not a shorter
// string, it is a broken one. Callers keep JSON_TAIL_BYTES of the buffer back
// while they fill the body, hand the reserve back to close, and say
// `"truncated":true` — a short answer the caller can parse beats a complete
// one it cannot.
//
// This is the shape `append` and `append_escaped` have had in qnn_probe.c
// next door since it was written, and it is deliberately not a third way of
// doing the same thing. The one difference is that the capacity travels with
// the buffer instead of being a #define, because these three replies are not
// all the same size.
typedef struct {
    char* out;
    size_t cap;
    size_t at;
    int truncated;
} json_buf;

// The longest close any reply here needs is `","truncated":true}` — nineteen
// bytes and the NUL. Holding that much back until the body is finished is
// what stops the closing brace from being the write that does not fit, which
// is exactly the write that ran off the end before.
#define JSON_TAIL_BYTES 24

static void json_init(json_buf* buf, char* out, size_t cap) {
    buf->out = out;
    buf->cap = cap;
    buf->at = 0;
    buf->truncated = 0;
    if (cap > 0) out[0] = '\0';
}

static void json_append(json_buf* buf, const char* text) {
    if (!text) return;
    size_t length = strlen(text);
    if (buf->at + length + 1 > buf->cap) {
        buf->truncated = 1;
        return;
    }
    memcpy(buf->out + buf->at, text, length);
    buf->at += length;
    buf->out[buf->at] = '\0';
}

// Escapes a string that came from somewhere else into the body of a JSON
// string.
//
// `name` and `version` are the vendor driver's, and the requested device name
// arrives over the wire; all three used to go in through a bare %s. A driver
// called `my "npu"` — or a caller who asks for one — produced a reply no
// parser would take, which is a worse failure than a wrong answer because it
// lands at the far end, in whatever was reading the matrix, with nothing to
// point at.
//
// A backslash goes in front of `"` and `\`, and anything below 0x20 becomes a
// space, since a raw control character is not legal inside a JSON string
// either. Bytes at 0x80 and above pass through untouched: they are the
// driver's UTF-8 and not ours to reinterpret. Each character is written whole
// or not at all, so a string the capacity cuts short is still a string.
static void json_append_escaped(json_buf* buf, const char* text) {
    if (!text) return;
    for (const char* c = text; *c; c++) {
        unsigned char ch = (unsigned char) *c;
        char piece[2];
        size_t n = 0;
        if (ch == '"' || ch == '\\') {
            piece[n++] = '\\';
            piece[n++] = (char) ch;
        } else if (ch < 0x20) {
            piece[n++] = ' ';
        } else {
            piece[n++] = (char) ch;
        }
        if (buf->at + n + 1 > buf->cap) {
            buf->truncated = 1;
            return;
        }
        memcpy(buf->out + buf->at, piece, n);
        buf->at += n;
        buf->out[buf->at] = '\0';
    }
}

// A double that %f can print and a parser can read back.
//
// JSON has no infinity and no NaN, and printf writes both as words. Neither
// is reachable from any run the iteration and shape caps allow, but a rate is
// a division and a clock is a thing that can jump, so the one place that
// could produce `inf` is closed here rather than argued about. The clamp also
// fixes the printed width — under 1e12 a "%.1f" is at most seventeen
// characters — which is what keeps the fixed scratch buffers below provably
// large enough.
static double json_number(double value) {
    if (!(value > -1e12 && value < 1e12)) return 0.0;  // false for NaN too
    return value;
}

#define DEVICES_BYTES 8192

JNIEXPORT jstring JNICALL
Java_io_github_m96chan_droidrunner_npu_NnapiProbe_devicesJson(JNIEnv* env, jobject thiz) {
    (void) thiz;
    // A local, not a `static` (issue #197). The agent runs two workers and
    // nothing serialises them, so a process-wide buffer could hand one caller
    // the other's list of devices. NewStringUTF copies before this returns,
    // which is already how the buffer was being consumed, so nothing needs to
    // outlive the frame. Eight kilobytes is a small corner of a thread's
    // stack; the twelve kilobytes of tensors in addBenchmark are not, and go
    // on the heap instead.
    char out[DEVICES_BYTES];
    if (!ensure_lib()) {
        return (*env)->NewStringUTF(env, "{\"available\":false,\"error\":\"libneuralnetworks unavailable\"}");
    }
    uint32_t count = 0;
    if (p_getDeviceCount(&count) != 0) {
        return (*env)->NewStringUTF(env, "{\"available\":false,\"error\":\"getDeviceCount failed\"}");
    }
    json_buf reply;
    json_init(&reply, out, DEVICES_BYTES - JSON_TAIL_BYTES);
    json_append(&reply, "{\"available\":true,\"devices\":[");
    // Counted rather than taken from `i`, because a device the runtime
    // refuses to hand over is skipped: with the old `i == 0 ? "" : ","` a
    // failure on device zero put a comma straight after the `[`, which is
    // another way to return something nobody can parse.
    size_t listed = 0;
    for (uint32_t i = 0; i < count; i++) {
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
        // A driver that reports a name by leaving the pointer alone is one
        // thing; one that answers with NULL is another, and assuming neither
        // is the job of whoever is calling a vendor blob.
        if (!name) name = "?";
        if (!version) version = "?";

        char level[32];
        snprintf(level, sizeof(level), "%lld", (long long) feature);

        // A device goes in whole or not at all: a list that stops in the
        // middle of `{"name":"qti-` is not JSON. The cursor is marked before
        // the entry and wound back if any part of it did not fit, and the
        // loop stops there rather than trying the next one — the buffer is
        // full, and a shorter name further down appearing while a longer one
        // above it vanished would be a strange list to publish.
        size_t mark = reply.at;
        if (listed > 0) json_append(&reply, ",");
        json_append(&reply, "{\"name\":\"");
        json_append_escaped(&reply, name);
        json_append(&reply, "\",\"type\":\"");
        json_append(&reply, device_type_name(type));
        json_append(&reply, "\",\"version\":\"");
        json_append_escaped(&reply, version);
        json_append(&reply, "\",\"featureLevel\":");
        json_append(&reply, level);
        json_append(&reply, "}");
        if (reply.truncated) {
            reply.at = mark;
            out[mark] = '\0';
            break;
        }
        listed++;
    }
    // The reserve comes back now the body is done, so the close always fits.
    // A reply that lost devices says so; it does not pretend the list ended.
    reply.cap = DEVICES_BYTES;
    json_append(&reply, reply.truncated ? "],\"truncated\":true}" : "]}");
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
Java_io_github_m96chan_droidrunner_npu_NnapiProbe_convBenchmark(
        JNIEnv* env, jobject thiz, jstring jDeviceName, jint iterations,
        jint size, jint channels, jint filters) {
    (void) thiz;
    // Local for the reason devicesJson's is (issue #197): two workers, one
    // buffer, and whichever of them wrote last used to answer for both.
    char out[1024];
    if (!ensure_lib() || !p_modelCreate || !p_compute) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"NNAPI unavailable\"}");
    }
    // Caps keep a job from cooking the phone or exhausting memory: the largest
    // accepted shape allocates a few MB and stays well inside a second per
    // iteration on the CPU fallback.
    if (iterations < 1) iterations = 1;
    if (iterations > 200) iterations = 200;
    if (size < 8) size = 8;
    if (size > 128) size = 128;
    if (channels < 1) channels = 1;
    if (channels > 32) channels = 32;
    if (filters < 1) filters = 1;
    if (filters > 32) filters = 32;

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
    int rc = 0;

    do {
        rc = p_modelCreate(&model);
        if (rc != 0) { err = "model_create"; break; }
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
            rc = p_compCreateForDevices(model, list, 1, &compilation);
            if (rc != 0) { err = "compilation_for_device"; break; }
            usedDevice = wantedName;
        } else {
            // Report support on the default path too, not only when pinned.
            if (p_modelSupportedOps && p_getDeviceCount) {
                uint32_t count = 0;
                if (p_getDeviceCount(&count) == 0 && count > 0 && count <= 32) {
                    const ANeuralNetworksDevice* all[32];
                    uint32_t found = 0;
                    for (uint32_t i = 0; i < count; i++) {
                        ANeuralNetworksDevice* device = NULL;
                        if (p_getDevice(i, &device) == 0 && device) all[found++] = device;
                    }
                    bool flags[1] = { false };
                    if (found > 0 && p_modelSupportedOps(model, all, found, flags) == 0) {
                        supported = flags[0] ? 1 : 0;
                    }
                }
            }
            rc = p_compCreate(model, &compilation);
            if (rc != 0) { err = "compilation_create"; break; }
        }
        if (p_compSetPreference) p_compSetPreference(compilation, PREFER_SUSTAINED_SPEED);
        rc = p_compFinish(compilation);
        if (rc != 0) { err = "compilation_finish"; break; }

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

    // The device name is the last field in both replies now, and it is the
    // only one that did not come from this file: it arrives over the wire
    // from the agent, so it is escaped, and it is the only field with no
    // length of its own. Last is where a field like that belongs — a name too
    // long for what is left of the buffer is then cut inside a JSON string
    // that the closing `"}` still finishes properly.
    //
    // Everything before it is measured: the literals come to about 140 bytes,
    // five `%d` to at most 11 each, `err` and result_name to at most 25 each,
    // `supported` to 5, and json_number holds the two doubles to 17 — under
    // 300 in the worst case, in a 512-byte scratch.
    json_buf reply;
    json_init(&reply, out, sizeof(out) - JSON_TAIL_BYTES);
    char detail[512];
    if (err) {
        snprintf(detail, sizeof(detail),
                 "{\"ok\":false,\"error\":\"%s\",\"resultCode\":%d,\"result\":\"%s\","
                 "\"supported\":%s,\"device\":\"",
                 err, rc, result_name(rc),
                 supported < 0 ? "null" : (supported ? "true" : "false"));
        json_append(&reply, detail);
        json_append_escaped(&reply, wantedName ? wantedName : "default");
    } else {
        // 2 * K*K * Cin * Cout * H * W flops for the convolution. A run the
        // clock could not separate would divide by zero here, and `inf` is
        // not a rate any parser will read back; no rate is the honest answer
        // to a duration of nothing anyway.
        double gflops = avgUs > 0
                ? 2.0 * 9.0 * channels * filters * size * size / (avgUs * 1e3)
                : 0.0;
        snprintf(detail, sizeof(detail),
                 "{\"ok\":true,\"op\":\"CONV_2D %dx%dx%d -> %d filters\","
                 "\"iterations\":%d,\"avgUs\":%.1f,\"gflops\":%.2f,\"supported\":%s,"
                 "\"device\":\"",
                 size, size, channels, filters, (int) iterations,
                 json_number(avgUs), json_number(gflops),
                 supported < 0 ? "null" : (supported ? "true" : "false"));
        json_append(&reply, detail);
        json_append_escaped(&reply, usedDevice);
    }
    reply.cap = sizeof(out);
    json_append(&reply, reply.truncated ? "\",\"truncated\":true}" : "\"}");

    if (compilation) p_compFree(compilation);
    if (model) p_modelFree(model);
    free(input); free(weights); free(bias); free(output);
    if (wantedName) (*env)->ReleaseStringUTFChars(env, jDeviceName, wantedName);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jstring JNICALL
Java_io_github_m96chan_droidrunner_npu_NnapiProbe_addBenchmark(
        JNIEnv* env, jobject thiz, jstring jDeviceName, jint iterations) {
    (void) thiz;
    // Local, as above (issue #197).
    char out[1024];
    if (!ensure_lib() || !p_modelCreate || !p_compute) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"NNAPI unavailable\"}");
    }
    if (iterations < 1) iterations = 1;
    if (iterations > 1000) iterations = 1000;

    const char* wantedName = NULL;
    if (jDeviceName) wantedName = (*env)->GetStringUTFChars(env, jDeviceName, NULL);

    enum { N = 1024 };
    const uint32_t dims[1] = { N };
    OperandType tensor = { OP_TENSOR_FLOAT32, 1, dims, 0.0f, 0 };
    OperandType scalar = { OP_INT32, 0, NULL, 0.0f, 0 };

    // On the heap, where `static float a[N], b[N], sum[N]` used to be (issue
    // #197). Those three arrays belonged to every caller at once: a second
    // request refilled `a` and `b` underneath the first one's running
    // compute, so a timed run was not timing the input that was set up for
    // it, and `correct` was read out of a `sum` the other execution was
    // writing — which is to say the one field that exists to prove the
    // arithmetic reached the accelerator proved nothing at all. Twelve
    // kilobytes is more than belongs on a stack the way `out` does, so it is
    // allocated and freed per call, the way convBenchmark next door already
    // handles its own tensors, with the same answer when there is no memory.
    const size_t bytes = N * sizeof(float);
    float* a = malloc(bytes);
    float* b = malloc(bytes);
    // Zeroed rather than merely allocated: `correct` reads three cells of
    // this, and reading them out of whatever the allocator last had there
    // would make a claim about the driver out of a claim about the heap.
    float* sum = calloc(N, sizeof(float));
    if (!a || !b || !sum) {
        free(a); free(b); free(sum);
        if (wantedName) (*env)->ReleaseStringUTFChars(env, jDeviceName, wantedName);
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"out of memory\"}");
    }
    for (int i = 0; i < N; i++) { a[i] = (float) i; b[i] = 2.0f; }

    ANeuralNetworksModel* model = NULL;
    ANeuralNetworksCompilation* compilation = NULL;
    const char* usedDevice = "default";
    const char* err = NULL;
    double avgUs = 0;
    int correct = 0;
    int rc = 0;

    do {
        rc = p_modelCreate(&model);
        if (rc != 0) { err = "model_create"; break; }
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
            rc = p_compCreateForDevices(model, list, 1, &compilation);
            if (rc != 0) { err = "compilation_for_device"; break; }
            usedDevice = wantedName;
        } else {
            rc = p_compCreate(model, &compilation);
            if (rc != 0) { err = "compilation_create"; break; }
        }
        rc = p_compFinish(compilation);
        if (rc != 0) { err = "compilation_finish"; break; }

        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        for (int iter = 0; iter < iterations; iter++) {
            ANeuralNetworksExecution* execution = NULL;
            if (p_execCreate(compilation, &execution) != 0) { err = "execution_create"; break; }
            // `bytes`, not `sizeof(a)`: these are pointers now, and sizeof a
            // pointer would have told the driver the tensor was eight bytes
            // long.
            if (p_setInput(execution, 0, NULL, a, bytes) != 0 ||
                p_setInput(execution, 1, NULL, b, bytes) != 0 ||
                p_setOutput(execution, 0, NULL, sum, bytes) != 0 ||
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

    // Device name last and escaped, for the reasons given in convBenchmark.
    json_buf reply;
    json_init(&reply, out, sizeof(out) - JSON_TAIL_BYTES);
    char detail[512];
    if (err) {
        snprintf(detail, sizeof(detail),
                 "{\"ok\":false,\"error\":\"%s\",\"resultCode\":%d,\"result\":\"%s\","
                 "\"device\":\"",
                 err, rc, result_name(rc));
        json_append(&reply, detail);
        json_append_escaped(&reply, wantedName ? wantedName : "default");
    } else {
        snprintf(detail, sizeof(detail),
                 "{\"ok\":true,\"op\":\"ADD float32[%d]\",\"iterations\":%d,"
                 "\"avgUs\":%.1f,\"correct\":%s,\"device\":\"",
                 N, (int) iterations, json_number(avgUs), correct ? "true" : "false");
        json_append(&reply, detail);
        json_append_escaped(&reply, usedDevice);
    }
    reply.cap = sizeof(out);
    json_append(&reply, reply.truncated ? "\",\"truncated\":true}" : "\"}");

    if (compilation) p_compFree(compilation);
    if (model) p_modelFree(model);
    free(a); free(b); free(sum);
    if (wantedName) (*env)->ReleaseStringUTFChars(env, jDeviceName, wantedName);
    return (*env)->NewStringUTF(env, out);
}


// Temporarily sends this process's stdout and stderr to a file (issue #93).
//
// TFLite states how much of a graph a delegate took, and the NNAPI delegate
// names the operators it refused, and both do it by printing. On Android an
// app's stdout and stderr go to /dev/null unless someone has set
// log.redirect-stdio, so none of it arrives anywhere. There is no API for the
// partitioning either, in Java or in C, which leaves the log as the only
// source for the number every consumer of this asks for.
//
// Unlike the isolated process, this one belongs to the app for its whole life,
// so the redirect is undone rather than left in place: the original
// descriptors are handed back as a token and restored when the run is over.
JNIEXPORT jlong JNICALL
Java_io_github_m96chan_droidrunner_npu_OutputCapture_redirect(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *file = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(file, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    (*env)->ReleaseStringUTFChars(env, path, file);
    if (fd < 0) return 0;

    int saved_out = dup(STDOUT_FILENO);
    int saved_err = dup(STDERR_FILENO);
    if (saved_out < 0 || saved_err < 0) {
        close(fd);
        if (saved_out >= 0) close(saved_out);
        if (saved_err >= 0) close(saved_err);
        return 0;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(fd, STDOUT_FILENO);
    dup2(fd, STDERR_FILENO);
    close(fd);
    // Both descriptors in one long, so the caller holds one opaque token.
    return ((jlong) saved_out << 32) | (jlong) (uint32_t) saved_err;
}

JNIEXPORT void JNICALL
Java_io_github_m96chan_droidrunner_npu_OutputCapture_restore(
        JNIEnv *env, jclass clazz, jlong token) {
    (void) env; (void) clazz;
    if (token == 0) return;
    int saved_out = (int) (token >> 32);
    int saved_err = (int) (token & 0xFFFFFFFF);
    fflush(stdout);
    fflush(stderr);
    dup2(saved_out, STDOUT_FILENO);
    dup2(saved_err, STDERR_FILENO);
    close(saved_out);
    close(saved_err);
}
