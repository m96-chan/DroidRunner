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

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads back what was said while a model was being prepared (issue #82).
 *
 * How much of a graph reached the accelerator is stated once, in a log, while
 * the delegate is being applied — there is no API for it. So the `:qnn`
 * process points its own stdout and stderr at a file (see
 * `QnnNative.captureOutput`) and this reads the part written since a run
 * began.
 *
 * A file rather than logcat, which was the first attempt. The nubia in the
 * fleet ships with the system property `log.tag=S`, which silences every tag
 * at the source: `logd` runs, `events` and `radio` carry traffic, and `main`
 * stays empty however you ask — even a line written on purpose never lands.
 * (`adb shell setprop log.tag V` lifts it until the next reboot, which is
 * useful when debugging by hand and no use at all to the app, which cannot set
 * system properties and would be relying on someone having done so.)
 *
 * Everything worth reading here is printed rather than logged, so a redirected
 * file descriptor catches it wherever the process runs.
 */
internal object DelegateLog {

    /** Where the isolated process's output is collected. */
    fun fileIn(directory: File): File = File(directory, "qnn-output.txt")

    /** A point to read from afterwards: how much had been written by now. */
    fun mark(log: File): Long = if (log.isFile) log.length() else 0L

    /** Everything written to [log] since [mark], capped so a chatty run cannot fill memory. */
    fun since(log: File, mark: Long): String = runCatching {
        if (!log.isFile) return ""
        val from = mark.coerceAtMost(log.length())
        val length = (log.length() - from).coerceAtMost(MAX_BYTES)
        RandomAccessFile(log, "r").use { handle ->
            handle.seek(from)
            val bytes = ByteArray(length.toInt())
            handle.readFully(bytes)
            String(bytes)
        }
    }.getOrElse { "" }

    private const val MAX_BYTES = 512L * 1024
}
