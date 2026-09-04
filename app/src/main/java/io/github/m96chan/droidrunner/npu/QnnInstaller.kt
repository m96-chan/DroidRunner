package io.github.m96chan.droidrunner.npu

import android.content.Context
import io.github.m96chan.droidrunner.runtime.RuntimeActivation
import io.github.m96chan.droidrunner.runtime.RuntimeDownload
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Putting Qualcomm's NPU runtime on a device that can use it (issue #82,
 * stage 2).
 *
 * Only the Hexagon generation this phone actually has is fetched, and only
 * once: an install that already carries the right stamp is left alone, because
 * the alternative is 38MB every time the app starts. What arrives is checked
 * against the digests pinned in [QnnArtifacts] before it is activated, so a
 * transfer that was truncated or interfered with never becomes a library the
 * device loads.
 *
 * The files land in `filesDir`, not in the APK's library directory: they are
 * data this app fetched, not code it shipped, and Android will not let an app
 * write there anyway.
 */
class QnnInstaller(private val context: Context) {

    val installDir = File(context.filesDir, "qnn")

    /** Which QNN release and Hexagon generation are installed, if any. */
    val installed: String?
        get() = File(installDir, STAMP).takeIf { it.isFile }?.readText()?.trim()

    /** Where the libraries are, or null when nothing is installed. */
    fun libraryDir(): File? = installDir.takeIf { installed != null }

    /**
     * Installs the runtime for [htpVersion], reporting phases through
     * [progress] with a 0..1 fraction while bytes are moving.
     *
     * Returns false when the right runtime was already present and nothing was
     * fetched.
     */
    fun install(htpVersion: Int, progress: (String, Float?) -> Unit = { _, _ -> }): Boolean {
        val entries = QnnArtifacts.entriesFor(htpVersion)
            ?: error("No QNN runtime is published for Hexagon v$htpVersion")
        val stamp = QnnArtifacts.stamp(htpVersion)
        if (installed == stamp) return false

        checkSpaceFor(entries)
        val staging = File(context.filesDir, "qnn.new").apply { deleteRecursively(); mkdirs() }
        try {
            var done = 0L
            val total = entries.sumOf { it.bytes }
            for ((module, wanted) in entries.groupBy { it.module }) {
                val zip = RemoteZip(HttpZip(URL(QnnArtifacts.url(module))))
                for (entry in wanted) {
                    progress("fetching ${entry.library}", done.toFloat() / total)
                    zip.extract(
                        name = entry.zipEntry,
                        target = File(staging, entry.library),
                        scratch = File(staging, "${entry.library}.z"),
                        sha256 = entry.sha256,
                        progress = { within ->
                            progress(
                                "fetching ${entry.library}",
                                (done + within * entry.bytes) / total,
                            )
                        },
                    )
                    done += entry.bytes
                }
            }
            File(staging, STAMP).writeText(stamp)
        } catch (failed: Throwable) {
            staging.deleteRecursively()
            throw failed
        }

        progress("installing NPU runtime", null)
        RuntimeActivation.activate(
            staging = staging,
            target = installDir,
            previous = File(context.filesDir, "qnn.old"),
        )
        return true
    }

    /** Removes the runtime, for a device that no longer wants it on disk. */
    fun uninstall() {
        installDir.deleteRecursively()
    }

    /**
     * Refuses to start rather than filling the disk and failing part-way. The
     * unpacked libraries stay, and the largest one's compressed bytes sit
     * beside it while it is fetched — never more than the file it expands to.
     */
    private fun checkSpaceFor(entries: List<QnnArtifacts.Entry>) {
        val needed = entries.sumOf { it.bytes } + entries.maxOf { it.bytes }
        val free = context.filesDir.usableSpace
        check(free >= needed) {
            "Not enough free space: the NPU runtime needs about ${needed / MB}MB " +
                "and this device has ${free / MB}MB"
        }
    }

    /**
     * Wires [RemoteZip] to HTTP. Everything worth testing lives on the other
     * side of [RemoteZip.RemoteFile]; this is the part only a real server can
     * exercise.
     */
    private class HttpZip(private val url: URL) : RemoteZip.RemoteFile {

        override val sizeBytes: Long by lazy {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                val length = connection.contentLengthLong
                check(length > 0) { "$url did not report a size" }
                length
            } finally {
                connection.disconnect()
            }
        }

        override fun read(offset: Long, length: Int): ByteArray {
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            try {
                val bytes = ByteArray(length)
                connection.inputStream.use { input ->
                    var filled = 0
                    while (filled < length) {
                        val count = input.read(bytes, filled, length - filled)
                        check(count >= 0) { "$url ended after $filled of $length bytes" }
                        filled += count
                    }
                }
                return bytes
            } finally {
                connection.disconnect()
            }
        }

        override fun source(start: Long, endInclusive: Long) = RuntimeDownload.Source { offset ->
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=${start + offset}-$endInclusive")
            RuntimeDownload.Chunk(
                stream = connection.inputStream,
                resumed = offset > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL,
                totalBytes = endInclusive - start + 1,
            )
        }
    }

    private companion object {
        const val STAMP = ".installed"
        const val MB = 1024 * 1024
    }
}
