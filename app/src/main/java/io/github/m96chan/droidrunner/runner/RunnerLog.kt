package io.github.m96chan.droidrunner.runner

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The listener's output on disk (issue #40).
 *
 * The supervisor respawns a listener that dies, so the attempt worth reading
 * is rarely the current one: opening the file fresh on every start threw away
 * the restart loop and kept only its aftermath. Attempts are appended one
 * after another instead, each announcing itself so the file reads as a
 * sequence rather than one stream.
 *
 * It cannot grow freely — it sits in `filesDir` beside the runner's own work
 * directory, and admission control stops taking jobs once free storage runs
 * short (issue #2) — so it is capped, keeping one previous generation.
 */
class RunnerLog(
    private val directory: File,
    private val maxBytes: Long = MAX_BYTES,
) : Closeable {

    private val file = File(directory, FILE_NAME)
    private var writer: BufferedWriter = open()

    /** Separates one listener's output from the last. */
    fun startAttempt(startedAtMillis: Long) {
        if (file.length() > 0) append("")
        append(attemptHeader(startedAtMillis))
    }

    fun append(line: String) {
        // Checked per line, not per attempt: a listener that stays up for
        // weeks is the one that fills the disk, and it never restarts.
        if (shouldRotate(file.length(), maxBytes)) rotate()
        writer.appendLine(line)
        // Whatever kills the listener usually kills this process too, and a
        // buffered tail would take the explanation with it.
        writer.flush()
    }

    override fun close() {
        runCatching { writer.close() }
    }

    private fun open(): BufferedWriter = FileWriter(file, true).buffered()

    private fun rotate() {
        writer.close()
        val previous = File(directory, PREVIOUS_FILE_NAME)
        previous.delete()
        file.renameTo(previous)
        writer = open()
    }

    companion object {
        const val FILE_NAME = "runner.log"

        /** The generation before the current one; older than this is dropped. */
        const val PREVIOUS_FILE_NAME = "runner.log.1"

        /**
         * Two generations of this are still small next to the 2GB of free
         * storage admission control insists on, while holding days of a
         * talkative listener — long enough to cover a night that went wrong.
         */
        const val MAX_BYTES = 4L * 1024 * 1024

        /**
         * Rotation happens before the write that would cross the cap, so the
         * cap bounds the file instead of being the point it is noticed past.
         */
        fun shouldRotate(currentBytes: Long, maxBytes: Long = MAX_BYTES): Boolean =
            currentBytes >= maxBytes

        /**
         * Timestamped in UTC to match the listener's own lines, which is what
         * makes an appended file readable as a sequence of attempts.
         */
        fun attemptHeader(startedAtMillis: Long): String {
            val at = Instant.ofEpochMilli(startedAtMillis).truncatedTo(ChronoUnit.SECONDS)
            return "=== listener started ${DateTimeFormatter.ISO_INSTANT.format(at)} ==="
        }
    }
}
