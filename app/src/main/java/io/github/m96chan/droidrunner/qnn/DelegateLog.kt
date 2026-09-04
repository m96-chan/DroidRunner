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

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads back what the QNN delegate said about itself (issue #82, stage 5).
 *
 * The delegate states how much of the graph it took while applying itself, and
 * it says so only in its log. There is no API for it: TFLite's Java surface
 * does not expose the partitioning, and the delegate's create function reports
 * errors through a callback but not this. So the log is where it is.
 *
 * An app without READ_LOGS sees only its own process's entries, which is
 * exactly the scope wanted here — and this runs in the `:qnn` process, where
 * the only thing writing is the delegate.
 */
internal object DelegateLog {

    /**
     * A point in the log to read from afterwards.
     *
     * A timestamp rather than a clear: `logcat -c` needs a permission this app
     * does not have, and clearing a shared buffer to measure one run would be
     * rude even if it worked.
     */
    fun mark(): String = TIMESTAMP.format(System.currentTimeMillis() - CLOCK_SLACK_MS)

    /** Everything this process has logged since [mark]. */
    fun since(mark: String): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-T", mark)
            .redirectErrorStream(true)
            .start()
        val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        text
    }.getOrElse { "" }

    /**
     * logcat's `-T` compares against the device clock, and a line written a
     * moment before the mark is still the line we want. Reaching back a little
     * costs nothing: the caller looks for the last match, not the first.
     */
    private const val CLOCK_SLACK_MS = 2_000L

    private val TIMESTAMP = java.text.SimpleDateFormat(
        "MM-dd HH:mm:ss.SSS",
        java.util.Locale.US,
    )
}
