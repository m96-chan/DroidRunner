/*
 * Part of DroidRunner. GPL-2.0-only, with the additional permission below.
 *
 * Additional permission under GNU GPL version 2, as a special exception:
 *
 * The copyright holders of this file give you permission to combine it with
 * Qualcomm's QNN runtime and LiteRT delegate libraries, and to convey the
 * resulting work. This permission covers this file only; it does not extend to
 * any other part of DroidRunner, which remains GPL-2.0-only.
 */
package io.github.m96chan.droidrunner.qnn

/**
 * JNI bridge to the QNN loader (see `cpp/qnn_probe.c`), issue #82 stage 4.
 *
 * Everything in this package runs in the `:qnn` process and nowhere else. The
 * separation is a licensing boundary as much as a safety one — PRoot is
 * GPL-2.0 and runs in the main process — so this file carries an additional
 * permission and holds nothing that would drag the rest of the app in with it.
 */
internal object QnnNative {

    @Volatile
    private var loaded: Boolean? = null

    private fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        return runCatching { System.loadLibrary("qnn_probe") }
            .isSuccess
            .also { loaded = it }
    }

    /**
     * Opens [libraries], in the order given, from [directory], and asks the
     * delegate what this device supports.
     *
     * Returns JSON: `{pid, libraries:[{name,loaded,error?}], capabilities?, ok}`.
     */
    fun load(directory: String, libraries: List<String>): String =
        if (ensureLoaded()) {
            loadLibraries(directory, libraries.toTypedArray())
        } else {
            """{"ok":false,"error":"qnn_probe library not loaded"}"""
        }

    /**
     * Creates a delegate for [backend], returning the `TfLiteDelegate*` as a
     * handle, or 0 with [lastError] set.
     *
     * [skelDir] is where the DSP should look for the Hexagon half of the
     * runtime; null leaves the library's own default alone.
     */
    fun createDelegate(backend: Int, skelDir: String?): Long =
        if (ensureLoaded()) createDelegate2(backend, skelDir) else 0L

    fun destroy(handle: Long) {
        if (loaded == true) destroyDelegate(handle)
    }

    /**
     * Bytes of profiling the backend recorded. Nothing is recorded unless a QNN
     * graph actually ran, which makes this a second opinion on whether the work
     * reached the accelerator. -1 when it cannot be asked.
     */
    fun profiling(handle: Long): Int = if (loaded == true) profilingBytes(handle) else -1

    /** Whatever the delegate last complained about, or blank. */
    fun error(): String = if (loaded == true) lastError() else "qnn_probe library not loaded"

    /**
     * Points this process's stdout and stderr at [path].
     *
     * QNN writes its diagnostics with printf, and at least one phone in the
     * fleet has a ROM whose logcat returns nothing at all. Without somewhere
     * to put that output, a backend refusing a graph is unexplainable.
     */
    fun captureOutput(path: String): Boolean = ensureLoaded() && redirectOutput(path)

    @JvmStatic
    private external fun redirectOutput(path: String): Boolean

    @JvmStatic
    private external fun loadLibraries(directory: String, libraries: Array<String>): String

    @JvmStatic
    private external fun createDelegate2(backend: Int, skelDir: String?): Long

    @JvmStatic
    private external fun destroyDelegate(handle: Long)

    @JvmStatic
    private external fun profilingBytes(handle: Long): Int

    @JvmStatic
    private external fun lastError(): String
}
