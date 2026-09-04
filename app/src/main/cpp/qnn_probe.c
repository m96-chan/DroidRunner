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
#include <fcntl.h>

// From QnnTFLiteDelegate.h. Only the scalar ABI is used: an int in, an int
// out. Nothing here reproduces Qualcomm's structures, so there is no header of
// theirs to carry around and no layout to get wrong.
#define CAP_HTP_QUANT 0
#define CAP_HTP_FP16 1
#define CAP_GPU 2
#define CAP_DSP 3

typedef int (*has_capability_fn)(int cap);

// The delegate is created the way Qualcomm's own Java wrapper creates it:
// take the library's own default options, change what must change, hand them
// back. The string-keyed plugin entry point was tried first and is a dead end
// -- it builds a delegate that TFLite then refuses to apply, and passing
// log_level through it loses the backend outright.
//
// The structure stays opaque here. Nothing of its shape is declared beyond
// three offsets that the published header fixes as its first three members:
// the backend at 0, and two paths after it. Everything else is whatever the
// library's own defaults put there.
#define OPTIONS_BYTES 16384
#define OFFSET_BACKEND_TYPE 0
#define OFFSET_LIBRARY_PATH 8
#define OFFSET_SKEL_DIR 16

// Big enough that the compiler returns it indirectly, which is the convention
// the real function was compiled with; the callee writes its own smaller size
// into the space we give it.
typedef struct { unsigned char bytes[OPTIONS_BYTES]; } qnn_options;

typedef qnn_options (*options_default_fn)(void);
typedef void *(*delegate_create_fn)(const void *options);
typedef void (*delegate_delete_fn)(void *delegate);

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

// Sends this process's stdout and stderr to a file.
//
// QNN writes its diagnostics with printf, and the phones this runs on include
// one whose ROM has no working logcat at all — logd runs, logcat returns
// nothing. Without this there is no way to learn why a backend refused a
// graph, which turns every failure into guesswork.
JNIEXPORT jboolean JNICALL
Java_io_github_m96chan_droidrunner_qnn_QnnNative_redirectOutput(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *file = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(file, O_WRONLY | O_CREAT | O_APPEND, 0600);
    (*env)->ReleaseStringUTFChars(env, path, file);
    if (fd < 0) return JNI_FALSE;
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    int ok = dup2(fd, STDOUT_FILENO) >= 0 && dup2(fd, STDERR_FILENO) >= 0;
    close(fd);
    return ok ? JNI_TRUE : JNI_FALSE;
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
    //
    // The platform's own directories stay on the path behind ours. Overriding
    // them outright was the first attempt, and it hid the vendor's
    // libQnnHtpV75Skel.so — which the DSP can read, while nothing outside this
    // app can read app-private storage. Ours first, theirs as the fallback.
    char adsp[2048];
    snprintf(adsp, sizeof(adsp),
             "%s;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/vendor/lib/rfsa/adsp", dir);
    setenv("ADSP_LIBRARY_PATH", adsp, 1);

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
Java_io_github_m96chan_droidrunner_qnn_QnnNative_createDelegate2(
        JNIEnv *env, jclass clazz, jint backend, jstring skelDir) {
    (void) clazz;
    const char *skel_dir = skelDir ? (*env)->GetStringUTFChars(env, skelDir, NULL) : NULL;
    g_last_error[0] = '\0';
    if (g_delegate == NULL) {
        record_error("the delegate library is not loaded");
        return 0;
    }
    options_default_fn defaults =
            (options_default_fn) dlsym(g_delegate, "TfLiteQnnDelegateOptionsDefault");
    delegate_create_fn create =
            (delegate_create_fn) dlsym(g_delegate, "TfLiteQnnDelegateCreate");
    if (defaults == NULL || create == NULL) {
        record_error("libQnnTFLiteDelegate.so is missing TfLiteQnnDelegateCreate");
        return 0;
    }

    // Kept for the life of the process: the delegate is applied long after this
    // returns, and anything it held a pointer into would be gone by then.
    qnn_options *options = calloc(1, sizeof(qnn_options));
    if (options == NULL) {
        record_error("out of memory building delegate options");
        return 0;
    }
    *options = defaults();

    unsigned char *raw = options->bytes;
    *(int32_t *) (raw + OFFSET_BACKEND_TYPE) = backend;
    if (skel_dir != NULL) {
        *(const char **) (raw + OFFSET_SKEL_DIR) = strdup(skel_dir);
    }

    void *handle = create(options);

    if (skel_dir) (*env)->ReleaseStringUTFChars(env, skelDir, skel_dir);
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
    delegate_delete_fn destroy =
            (delegate_delete_fn) dlsym(g_delegate, "TfLiteQnnDelegateDelete");
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
