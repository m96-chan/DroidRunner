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

    @JvmStatic
    private external fun loadLibraries(directory: String, libraries: Array<String>): String
}
