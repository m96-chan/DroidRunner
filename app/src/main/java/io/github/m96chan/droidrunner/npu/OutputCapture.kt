package io.github.m96chan.droidrunner.npu

import java.io.File

/**
 * Catches what the interpreter prints while it is being built (issue #93).
 *
 * TFLite says how much of a graph a delegate took, and the NNAPI delegate
 * names the operators it refused — both by printing, and on Android an app's
 * stdout and stderr go nowhere by default. There is no API for either fact, so
 * this is where the answer is.
 *
 * The redirect is undone afterwards. The isolated process can keep its output
 * pointed at a file for its whole short life; this one is the app, and leaving
 * its descriptors moved would swallow anything else that ever wrote there.
 */
internal object OutputCapture {

    /**
     * Runs [block] with output going to [log], and returns what was written.
     *
     * Falls back to running [block] with no capture when the native helper is
     * unavailable: a missing diagnostic must not cost the caller its result.
     */
    fun <T> around(log: File, block: () -> T): Pair<T, String> {
        val token = runCatching {
            if (NnapiProbe.available()) redirect(log.absolutePath) else 0L
        }.getOrDefault(0L)
        try {
            val value = block()
            return value to if (token != 0L) log.takeIf { it.isFile }?.readText().orEmpty() else ""
        } finally {
            if (token != 0L) runCatching { restore(token) }
            runCatching { log.delete() }
        }
    }

    @JvmStatic
    private external fun redirect(path: String): Long

    @JvmStatic
    private external fun restore(token: Long)
}
