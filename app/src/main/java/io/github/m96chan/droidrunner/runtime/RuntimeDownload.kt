package io.github.m96chan.droidrunner.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Fetching the runtime archive, resiliently (issue #43).
 *
 * A ~200MB transfer on a phone will be interrupted: the screen sleeps, the
 * network changes, the tunnel drops. Starting again from zero each time is
 * what made this worth extracting — and extracting it is what makes it
 * testable without a device, since the failure it guards against is exactly
 * the one nobody reproduces on demand.
 */
internal object RuntimeDownload {

    /** One attempt at reading the archive, possibly from part-way in. */
    data class Chunk(
        val stream: InputStream,
        /** Whether the server honoured the requested offset. */
        val resumed: Boolean,
        /** Total size of the whole archive, or -1 when the server won't say. */
        val totalBytes: Long,
    )

    fun interface Source {
        /** Opens the archive from [offset] bytes in. */
        fun open(offset: Long): Chunk
    }

    /**
     * Writes the archive to [target], resuming after a failure rather than
     * starting over. Callers verify the SHA-256 afterwards, which is what
     * makes resuming safe: a resumed file that does not match is thrown away
     * like any other corrupt download.
     *
     * [beforeFirstByte] runs once the total size is known and nothing has been
     * written yet — the moment to refuse for lack of space.
     */
    fun fetch(
        target: File,
        source: Source,
        attempts: Int = 3,
        beforeFirstByte: (Long) -> Unit = {},
        progress: (Float) -> Unit = {},
    ) {
        var attempt = 0
        while (true) {
            attempt++
            val alreadyHave = if (target.isFile) target.length() else 0L
            try {
                val chunk = source.open(alreadyHave)
                val startAt = if (chunk.resumed) alreadyHave else 0L
                if (startAt == 0L) beforeFirstByte(chunk.totalBytes)

                chunk.stream.use { input ->
                    FileOutputStream(target, startAt > 0).use { out ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var written = startAt
                        var lastStep = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            written += count
                            if (chunk.totalBytes > 0) {
                                val step = (written * PROGRESS_STEPS / chunk.totalBytes).toInt()
                                if (step != lastStep) {
                                    lastStep = step
                                    progress(written.toFloat() / chunk.totalBytes)
                                }
                            }
                        }
                    }
                }
                return
            } catch (failed: IOException) {
                if (attempt >= attempts) throw failed
            }
        }
    }

    private const val BUFFER_BYTES = 128 * 1024
    private const val PROGRESS_STEPS = 20
}
