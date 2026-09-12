package io.github.m96chan.droidrunner.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

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
    /** A rejected Range request must retry from zero, not repeat the same offset. */
    class RangeRejected : IOException("Server rejected the download range")

    /** Bind partial bytes to the exact signed manifest before allowing a resume. */
    fun fetchVerified(
        target: File,
        identity: String,
        expectedSha256: String,
        source: Source,
        attempts: Int = 3,
        beforeFirstByte: (Long) -> Unit = {},
        progress: (Float) -> Unit = {},
    ) {
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid runtime SHA-256" }
        val metadata = File(target.path + ".identity")
        val binding = identity + "\n" + expectedSha256.lowercase()
        if (!metadata.isFile || metadata.readText() != binding) {
            discard(target)
            metadata.writeText(binding)
        }
        if (target.isFile && sha256(target).equals(expectedSha256, ignoreCase = true)) {
            progress(1f)
            return
        }
        fetch(target, source, attempts, beforeFirstByte, progress) {
            sha256(it).equals(expectedSha256, ignoreCase = true)
        }
    }

    private fun discard(target: File) {
        check(!target.exists() || target.delete()) { "Cannot discard invalid download" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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
     * starting over. [verify], when supplied, rejects corrupt bytes and
     * retries from zero within the same attempt budget. Other consumers
     * verify the downloaded entry themselves.
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
        verify: ((File) -> Boolean)? = null,
    ) {
        require(attempts > 0)
        var attempt = 0
        while (true) {
            attempt++
            val alreadyHave = if (target.isFile) target.length() else 0L
            try {
                val chunk = source.open(alreadyHave)
                val startAt = if (chunk.resumed) alreadyHave else 0L
                chunk.stream.use { input ->
                    if (startAt == 0L) beforeFirstByte(chunk.totalBytes)
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
                if (verify != null && !verify(target)) {
                    discard(target)
                    throw IOException("Runtime SHA-256 mismatch")
                }
                return
            } catch (failed: IOException) {
                if (failed is RangeRejected) discard(target)
                if (attempt >= attempts) throw failed
            }
        }
    }

    private const val BUFFER_BYTES = 128 * 1024
    private const val PROGRESS_STEPS = 20
}
