package io.github.m96chan.droidrunner.npu

import io.github.m96chan.droidrunner.runtime.RuntimeDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RemoteZipTest {

    @Rule @JvmField val folder = TemporaryFolder()

    @Test fun anEntryComesOutIntactAndMatchesItsPinnedDigest() {
        val wanted = "the one file this device needs".repeat(500)
        val archive = archiveOf("wanted.so" to wanted, "unwanted.so" to "other".repeat(5000))

        extract(RemoteZip(FakeRemote(archive)), "wanted.so", sha256(wanted))

        assertEquals(wanted, folder.root.resolve("wanted.so").readText())
    }

    @Test fun onlyTheRequestedEntryIsTransferred() {
        // This is the whole point of reading the archive remotely: Qualcomm
        // ships six Hexagon generations in one AAR and a phone needs one. If
        // this ever reads the archive end to end, the saving is gone and no
        // other test would notice.
        val wanted = "small".repeat(100)
        // Poorly compressible and large, so that the fixed cost of reading the
        // directory stays the small term it is against a real 64MB AAR.
        val bulk = (1..300_000).joinToString { "unique-$it" }
        val archive = archiveOf("wanted.so" to wanted, "huge.so" to bulk)
        val remote = FakeRemote(archive)

        extract(RemoteZip(remote), "wanted.so", sha256(wanted))

        assertTrue(
            "read ${remote.bytesRead} of ${archive.size}",
            remote.bytesRead < archive.size / 4,
        )
    }

    @Test fun anEntryThatDoesNotMatchItsDigestIsThrownAway() {
        val archive = archiveOf("wanted.so" to "content that will be mis-pinned")

        val failure = assertThrows(IllegalStateException::class.java) {
            extract(RemoteZip(FakeRemote(archive)), "wanted.so", sha256("something else"))
        }

        assertTrue(failure.message!!.contains("pinned SHA-256"))
        // Nothing half-installed may survive: the next start must fetch again
        // rather than load a library that failed verification.
        assertFalse(folder.root.resolve("wanted.so").exists())
        assertFalse(folder.root.resolve("wanted.so.z").exists())
    }

    @Test fun aTruncatedTransferIsRefusedBeforeItIsUnpacked() {
        val archive = archiveOf("wanted.so" to "content".repeat(200))
        val remote = object : FakeRemote(archive) {
            override fun source(start: Long, endInclusive: Long) = RuntimeDownload.Source { offset ->
                // A server that closes early, and keeps doing so on retry.
                val half = ((endInclusive - start + 1) / 2).toInt()
                RuntimeDownload.Chunk(
                    stream = ByteArrayInputStream(bytes, (start + offset).toInt(), half),
                    resumed = false,
                    totalBytes = endInclusive - start + 1,
                )
            }
        }

        val failure = assertThrows(IllegalStateException::class.java) {
            extract(RemoteZip(remote), "wanted.so", sha256("content".repeat(200)))
        }

        assertTrue(failure.message!!.contains("expected"))
    }

    @Test fun aTransferThatDropsPartWayResumesInsteadOfStartingOver() {
        val wanted = "resume me".repeat(2000)
        val archive = archiveOf("wanted.so" to wanted)
        val remote = object : FakeRemote(archive) {
            var failures = 0
            override fun source(start: Long, endInclusive: Long) = RuntimeDownload.Source { offset ->
                val length = (endInclusive - start + 1 - offset).toInt()
                val stream = ByteArrayInputStream(bytes, (start + offset).toInt(), length)
                RuntimeDownload.Chunk(
                    stream = if (failures++ == 0) failAfter(stream, length / 2) else stream,
                    resumed = offset > 0,
                    totalBytes = endInclusive - start + 1,
                )
            }
        }

        extract(RemoteZip(remote), "wanted.so", sha256(wanted))

        assertEquals(wanted, folder.root.resolve("wanted.so").readText())
        assertEquals(2, remote.failures)
    }

    private fun extract(zip: RemoteZip, name: String, sha256: String) {
        zip.extract(
            name = name,
            target = folder.root.resolve(name),
            scratch = folder.root.resolve("$name.z"),
            sha256 = sha256,
        )
    }

    private open class FakeRemote(protected val bytes: ByteArray) : RemoteZip.RemoteFile {
        var bytesRead = 0L

        override val sizeBytes = bytes.size.toLong()

        override fun read(offset: Long, length: Int): ByteArray {
            bytesRead += length
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

        override fun source(start: Long, endInclusive: Long) = RuntimeDownload.Source { offset ->
            val length = (endInclusive - start + 1 - offset).toInt()
            bytesRead += length
            RuntimeDownload.Chunk(
                stream = ByteArrayInputStream(bytes, (start + offset).toInt(), length),
                resumed = offset > 0,
                totalBytes = endInclusive - start + 1,
            )
        }
    }

    private companion object {

        /** A stream that delivers [good] bytes and then behaves like a dropped connection. */
        fun failAfter(source: InputStream, good: Int): InputStream = object : InputStream() {
            private var served = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(buffer: ByteArray, at: Int, length: Int): Int {
                if (served >= good) throw IOException("connection reset")
                val count = source.read(buffer, at, minOf(length, good - served))
                served += count
                return count
            }
        }

        fun archiveOf(vararg files: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                files.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
