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
