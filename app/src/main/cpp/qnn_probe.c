// Loads Qualcomm's QNN libraries and asks them what this device can do
// (issue #82, stage 4).
//
// Runs in the ":qnn" process and nowhere else. That isolation is a licensing
// boundary as much as a safety one: PRoot is GPL-2.0 and lives in the main
// process, and the FSF's line for "one program" is the shared address space.
// Nothing here may be loaded beside it.
//
// ---------------------------------------------------------------------------
// Additional permission under GNU GPL version 2, as a special exception:
//
// The copyright holders of this file give you permission to combine it with
// Qualcomm's QNN runtime and LiteRT delegate libraries, and to convey the
// resulting work. This permission covers this file only; it does not extend to
// any other part of DroidRunner, which remains GPL-2.0-only.
// ---------------------------------------------------------------------------
//
// The libraries are dlopen'd by absolute path from the app's data directory
// rather than linked, because they are fetched at run time and are not in the
// APK. Preloading matters: the QNN runtime opens its own pieces later by bare
// soname, and the linker will not search app data for them — but a library
// already loaded is found by soname whatever path it came from.
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

// From QnnTFLiteDelegate.h. Only the scalar ABI is used: an int in, an int
// out. Nothing here reproduces Qualcomm's structures, so there is no header of
// theirs to carry around and no layout to get wrong.
#define CAP_HTP_QUANT 0
#define CAP_HTP_FP16 1
#define CAP_GPU 2
#define CAP_DSP 3

typedef int (*has_capability_fn)(int cap);

// TFLite's external-delegate ABI, which every delegate exports. Using it
// instead of TfLiteQnnDelegateCreate keeps Qualcomm's options structure out of
// this file entirely: options are passed as strings, so there is no layout to
// reproduce and nothing of their headers to carry around.
typedef void *(*plugin_create_fn)(char **keys, char **values, size_t count,
                                  void (*report_error)(const char *));
typedef void (*plugin_destroy_fn)(void *delegate);

// { const uint8_t* buffer; uint32_t buffer_length; } from QnnTFLiteDelegate.h.
// Two fields and no ambiguity about either.
typedef struct {
    const unsigned char *buffer;
    unsigned int length;
} profiling_result;

typedef profiling_result (*profiling_fn)(void *delegate);

// The delegate library, kept from loadLibraries so a later call can reach it.
static void *g_delegate;

// Whatever the delegate last complained about. Its create function reports
// errors through a callback with no user pointer, so there is nowhere else to
// put it.
#define ERROR_BYTES 1024
static char g_last_error[ERROR_BYTES];

static void record_error(const char *message) {
    if (message == NULL) return;
    snprintf(g_last_error, ERROR_BYTES, "%s", message);
}

#define JSON_BYTES 4096

static size_t append(char *out, size_t at, const char *text) {
    size_t length = strlen(text);
    if (at + length + 1 >= JSON_BYTES) return at;
    memcpy(out + at, text, length);
    out[at + length] = '\0';
    return at + length;
}

static size_t append_escaped(char *out, size_t at, const char *text) {
    for (const char *c = text; *c && at + 8 < JSON_BYTES; c++) {
        if (*c == '"' || *c == '\\') out[at++] = '\\';
        if (*c == '\n' || *c == '\r') { out[at++] = ' '; continue; }
        out[at++] = *c;
    }
    out[at] = '\0';
    return at;
}

JNIEXPORT jstring JNICALL
Java_io_github_m96chan_droidrunner_qnn_QnnNative_loadLibraries(
        JNIEnv *env, jclass clazz, jstring directory, jobjectArray libraries) {
    (void) clazz;
    char json[JSON_BYTES];
    size_t at = 0;

    const char *dir = (*env)->GetStringUTFChars(env, directory, NULL);

    // The skel runs on the DSP itself and is loaded by the DSP loader, not by
    // us, so it is found through this variable rather than through dlopen.
    setenv("ADSP_LIBRARY_PATH", dir, 1);

    char pid[64];
    snprintf(pid, sizeof(pid), "{\"pid\":%d,\"libraries\":[", (int) getpid());
    at = append(json, at, pid);

    void *delegate = NULL;
    int all_loaded = 1;
    jsize count = (*env)->GetArrayLength(env, libraries);
    for (jsize i = 0; i < count; i++) {
        jstring entry = (jstring) (*env)->GetObjectArrayElement(env, libraries, i);
        const char *name = (*env)->GetStringUTFChars(env, entry, NULL);

        char path[1024];
        snprintf(path, sizeof(path), "%s/%s", dir, name);
        // RTLD_GLOBAL so the runtime's later dlopen of these by bare soname
        // resolves to what we already put in the namespace.
        void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);

        if (i > 0) at = append(json, at, ",");
        at = append(json, at, "{\"name\":\"");
        at = append_escaped(json, at, name);
        at = append(json, at, handle ? "\",\"loaded\":true" : "\",\"loaded\":false");
        if (!handle) {
            all_loaded = 0;
            const char *why = dlerror();
            at = append(json, at, ",\"error\":\"");
            at = append_escaped(json, at, why ? why : "dlopen failed");
            at = append(json, at, "\"");
        } else if (strstr(name, "libQnnTFLiteDelegate.so") != NULL) {
            delegate = handle;
            g_delegate = handle;
        }
        at = append(json, at, "}");

        (*env)->ReleaseStringUTFChars(env, entry, name);
        (*env)->DeleteLocalRef(env, entry);
    }
    at = append(json, at, "]");

    // Asking the delegate itself, rather than inferring from the SoC name.
    // This is the difference between a label that means something and the hint
    // that is on these phones today.
    has_capability_fn has_capability =
            delegate ? (has_capability_fn) dlsym(delegate, "TfLiteQnnDelegateHasCapability") : NULL;
    if (has_capability) {
        char caps[256];
        snprintf(caps, sizeof(caps),
                 ",\"capabilities\":{\"htpQuant\":%d,\"htpFp16\":%d,\"gpu\":%d,\"dsp\":%d}",
                 has_capability(CAP_HTP_QUANT), has_capability(CAP_HTP_FP16),
                 has_capability(CAP_GPU), has_capability(CAP_DSP));
        at = append(json, at, caps);
    } else if (delegate) {
        at = append(json, at, ",\"error\":\"delegate has no TfLiteQnnDelegateHasCapability\"");
    }

    at = append(json, at, all_loaded && has_capability ? ",\"ok\":true}" : ",\"ok\":false}");

    (*env)->ReleaseStringUTFChars(env, directory, dir);
    return (*env)->NewStringUTF(env, json);
}


JNIEXPORT jlong JNICALL
Java_io_github_m96chan_droidrunner_qnn_QnnNative_createDelegate(
        JNIEnv *env, jclass clazz, jobjectArray keys, jobjectArray values) {
    (void) clazz;
    g_last_error[0] = '\0';
    if (g_delegate == NULL) {
        record_error("the delegate library is not loaded");
        return 0;
    }
    plugin_create_fn create =
            (plugin_create_fn) dlsym(g_delegate, "tflite_plugin_create_delegate");
    if (create == NULL) {
        record_error("libQnnTFLiteDelegate.so has no tflite_plugin_create_delegate");
        return 0;
    }

    jsize count = (*env)->GetArrayLength(env, keys);
    char **key_strings = calloc((size_t) count, sizeof(char *));
    char **value_strings = calloc((size_t) count, sizeof(char *));
    jstring *key_refs = calloc((size_t) count, sizeof(jstring));
    jstring *value_refs = calloc((size_t) count, sizeof(jstring));
    if (!key_strings || !value_strings || !key_refs || !value_refs) {
        free(key_strings); free(value_strings); free(key_refs); free(value_refs);
        record_error("out of memory building delegate options");
        return 0;
    }
    for (jsize i = 0; i < count; i++) {
        key_refs[i] = (jstring) (*env)->GetObjectArrayElement(env, keys, i);
        value_refs[i] = (jstring) (*env)->GetObjectArrayElement(env, values, i);
        key_strings[i] = (char *) (*env)->GetStringUTFChars(env, key_refs[i], NULL);
        value_strings[i] = (char *) (*env)->GetStringUTFChars(env, value_refs[i], NULL);
    }

    void *handle = create(key_strings, value_strings, (size_t) count, record_error);

    for (jsize i = 0; i < count; i++) {
        (*env)->ReleaseStringUTFChars(env, key_refs[i], key_strings[i]);
        (*env)->ReleaseStringUTFChars(env, value_refs[i], value_strings[i]);
        (*env)->DeleteLocalRef(env, key_refs[i]);
        (*env)->DeleteLocalRef(env, value_refs[i]);
    }
    free(key_strings); free(value_strings); free(key_refs); free(value_refs);

    if (handle == NULL && g_last_error[0] == '\0') {
        record_error("the delegate refused these options without saying why");
    }
    return (jlong) (uintptr_t) handle;
}

JNIEXPORT void JNICALL
Java_io_github_m96chan_droidrunner_qnn_QnnNative_destroyDelegate(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (g_delegate == NULL || handle == 0) return;
    plugin_destroy_fn destroy =
            (plugin_destroy_fn) dlsym(g_delegate, "tflite_plugin_destroy_delegate");
    if (destroy != NULL) destroy((void *) (uintptr_t) handle);
}

// How many bytes of profiling the backend recorded. Nothing is recorded unless
// a QNN graph actually executed, which makes this a second opinion on whether
// the work reached the accelerator at all.
JNIEXPORT jint JNICALL
Java_io_github_m96chan_droidrunner_qnn_QnnNative_profilingBytes(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (g_delegate == NULL || handle == 0) return -1;
    profiling_fn profiling =
            (profiling_fn) dlsym(g_delegate, "TfLiteQnnDelegateGetProfilingResult");
    if (profiling == NULL) return -1;
    profiling_result result = profiling((void *) (uintptr_t) handle);
    return (jint) result.length;
}

JNIEXPORT jstring JNICALL
Java_io_github_m96chan_droidrunner_qnn_QnnNative_lastError(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, g_last_error);
}
