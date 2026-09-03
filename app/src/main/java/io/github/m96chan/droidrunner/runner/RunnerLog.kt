package io.github.m96chan.droidrunner.runner

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * What happened on this device, on disk (issues #40, #52).
 *
 * The supervisor respawns a listener that dies, so the attempt worth reading
 * is rarely the current one: opening the file fresh on every start threw away
 * the restart loop and kept only its aftermath. Attempts are appended one
 * after another instead, each announcing itself so the file reads as a
 * sequence rather than one stream.
 *
 * Both voices are written here. The listener's output says what the runner
 * did; the app's own lines say why it was made to do it, and a night that
 * went wrong is only legible when the two are read in one order. They are
 * tagged rather than merged blindly, because "the runner said this" and "we
 * decided this" are different kinds of claim.
 *
 * Writers arrive from several threads — the supervisor, the `runner-output`
 * thread, and anything reporting through [RunnerStatus] — so appends are
 * serialised here, on the object that owns the file handle, rather than by
 * whatever happens to be calling.
 *
 * It cannot grow freely — it sits in `filesDir` beside the runner's own work
 * directory, and admission control stops taking jobs once free storage runs
 * short (issue #2) — so it is capped, keeping one previous generation.
 */
class RunnerLog(
    private val directory: File,
    private val maxBytes: Long = MAX_BYTES,
) : Closeable {

    /** Who is speaking on a line. */
    enum class Source(val tag: String) {
        /** The listener, or the runner CLI: transcribed, never paraphrased. */
        RUNNER("runner"),

        /** DroidRunner itself: an admission hold, a restart, a cleanup. */
        APP("app"),
    }

    private val file = File(directory, FILE_NAME)

    /**
     * Null while the file cannot be written. Logging is never worth failing a
     * build over, so a disk that has filled up costs the explanation and
     * nothing else; the next line tries again, since the condition that
     * blocked this one usually clears.
     */
    private var writer: BufferedWriter? = openOrNull()

    /** Separates one listener's output from the last. */
    @Synchronized fun startAttempt(startedAtMillis: Long) {
        if (file.length() > 0) write("")
        write(attemptHeader(startedAtMillis))
    }

    @Synchronized fun append(line: String, source: Source) {
        write(format(source, line))
    }

    @Synchronized override fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    /**
     * One line, whole. Callers hold the lock, so a line from the supervisor
     * can never land inside a line from the listener.
     */
    private fun write(text: String) {
        runCatching {
            // Checked per line, not per attempt: a listener that stays up for
            // weeks is the one that fills the disk, and it never restarts.
            if (shouldRotate(file.length(), maxBytes)) rotate()
            val out = writer ?: openOrNull()?.also { writer = it } ?: return
            out.appendLine(text)
            // Whatever kills the listener usually kills this process too, and a
            // buffered tail would take the explanation with it.
            out.flush()
        }.onFailure {
            // A half-written handle is worse than none; drop it and let the
            // next line reopen.
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun openOrNull(): BufferedWriter? =
        runCatching { FileWriter(file, true).buffered() }.getOrNull()

    private fun rotate() {
        close()
        val previous = File(directory, PREVIOUS_FILE_NAME)
        previous.delete()
        file.renameTo(previous)
        writer = openOrNull()
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
         * The source leads, so the file can be read — or grepped — for one
         * voice at a time. What follows it is the line as it was given: the
         * listener's output has to stay quotable against its own logs.
         */
        fun format(source: Source, line: String): String = "[${source.tag}] $line"

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
