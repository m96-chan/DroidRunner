package io.github.m96chan.droidrunner.npu

import io.github.m96chan.droidrunner.runtime.RuntimeDownload
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Pulling single files out of a zip that stays on the server (issue #82).
 *
 * Downloading Qualcomm's whole runtime AAR to keep 38MB of it would cost a
 * phone 64MB of transfer and, briefly, 165MB of storage. Reading the central
 * directory first and then fetching only the ranges that matter costs what the
 * files themselves cost, which is the difference between an install a metered
 * connection can afford and one it cannot.
 *
 * The transfer reuses [RuntimeDownload], so an entry interrupted half way
 * resumes rather than starting again — the same problem the runtime download
 * already solved.
 */
internal class RemoteZip(private val file: RemoteFile) {

    /** A zip readable in pieces, so tests need bytes rather than a server. */
    interface RemoteFile {
        val sizeBytes: Long

        /** Reads exactly [length] bytes from [offset]. */
        fun read(offset: Long, length: Int): ByteArray

        /** A resumable source for the bytes [start]..[endInclusive]. */
        fun source(start: Long, endInclusive: Long): RuntimeDownload.Source
    }

    /**
     * Read once and kept: the directory of a 64MB AAR is a few hundred bytes,
     * and every entry extracted from it would otherwise re-read the tail.
     */
    private val entries: Map<String, ZipIndex.Entry> by lazy {
        val tailLength = minOf(ZipIndex.TAIL_BYTES.toLong(), file.sizeBytes).toInt()
        val tail = file.read(file.sizeBytes - tailLength, tailLength)
        val directory = ZipIndex.directoryIn(tail)
        ZipIndex.entriesIn(file.read(directory.offset, directory.bytes.toInt()))
            .associateBy { it.name }
    }

    /** What [name] will occupy once unpacked, for a caller sizing the install. */
    fun unpackedBytes(name: String): Long = entry(name).uncompressedBytes

    /**
     * Writes [name] to [target], using [scratch] to hold the compressed bytes
     * while they are fetched.
     *
     * Fails, and leaves nothing behind, unless the result hashes to
     * [sha256] — a truncated or tampered transfer must not become a library
     * this device later loads.
     */
    fun extract(
        name: String,
        target: File,
        scratch: File,
        sha256: String,
        progress: (Float) -> Unit = {},
    ) {
        val entry = entry(name)
        val header = file.read(entry.localHeaderOffset, ZipIndex.LOCAL_HEADER_BYTES)
        val start = ZipIndex.dataStart(entry, header)

        RuntimeDownload.fetch(
            target = scratch,
            source = file.source(start, start + entry.compressedBytes - 1),
            progress = progress,
        )
        check(scratch.length() == entry.compressedBytes) {
            "$name arrived ${scratch.length()} bytes long, expected ${entry.compressedBytes}"
        }

        try {
            val digest = unpack(entry, scratch, target)
            check(digest.equals(sha256, ignoreCase = true)) {
                "$name does not match its pinned SHA-256"
            }
        } catch (failed: Throwable) {
            target.delete()
            scratch.delete()
            throw failed
        }
        scratch.delete()
    }

    /** Expands [entry] from [scratch] into [target], returning its SHA-256. */
    private fun unpack(entry: ZipIndex.Entry, scratch: File, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Raw deflate: a zip entry carries no zlib header, hence nowrap.
        val inflater = Inflater(true)
        try {
            val source: InputStream = scratch.inputStream().let {
                if (entry.deflated) InflaterInputStream(it, inflater) else it
            }
            source.use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            inflater.end()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun entry(name: String): ZipIndex.Entry =
        entries[name] ?: error("$name is not in this archive")

    private companion object {
        const val BUFFER_BYTES = 128 * 1024
    }
}
