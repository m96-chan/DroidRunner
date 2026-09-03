package io.github.m96chan.droidrunner.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDownloadTest {

    @get:Rule val temp = TemporaryFolder()

    private val archive = ByteArray(50_000) { (it % 251).toByte() }

    /** A stream that dies part-way, the way a phone changing networks does. */
    private class FailingStream(private val bytes: ByteArray, private val failAfter: Int) :
        InputStream() {
        private var position = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= failAfter) throw IOException("connection reset")
            val count = minOf(length, failAfter - position, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, count)
            position += count
            return if (count == 0) -1 else count
        }
    }

    @Test fun aWholeArchiveIsWritten() {
        val target = File(temp.root, "archive")
        RuntimeDownload.fetch(target, { offset ->
            RuntimeDownload.Chunk(
                ByteArrayInputStream(archive, offset.toInt(), archive.size - offset.toInt()),
                resumed = offset > 0,
                totalBytes = archive.size.toLong(),
            )
        })
        assertArrayEquals(archive, target.readBytes())
    }

    @Test fun anInterruptedTransferResumesWhereItStopped() {
        val target = File(temp.root, "archive")
        val offsets = mutableListOf<Long>()
        var attempt = 0

        RuntimeDownload.fetch(target, { offset ->
            offsets += offset
            attempt++
            val stream = if (attempt == 1) {
                FailingStream(archive, failAfter = 20_000)
            } else {
                ByteArrayInputStream(archive, offset.toInt(), archive.size - offset.toInt())
            }
            RuntimeDownload.Chunk(stream, resumed = offset > 0, totalBytes = archive.size.toLong())
        })

        assertEquals("the retry asks for the bytes already on disk", listOf(0L, 20_000L), offsets)
        assertArrayEquals("and the file is whole", archive, target.readBytes())
    }

    @Test fun aServerThatIgnoresTheRangeStartsTheFileAgain() {
        // Resuming is a request, not a guarantee. A server that answers 200
        // instead of 206 must not have its bytes appended to a partial file.
        val target = File(temp.root, "archive")
        target.writeBytes(ByteArray(20_000) { 0 })

        RuntimeDownload.fetch(target, { _ ->
            RuntimeDownload.Chunk(
                ByteArrayInputStream(archive),
                resumed = false,
                totalBytes = archive.size.toLong(),
            )
        })

        assertArrayEquals(archive, target.readBytes())
    }

    @Test fun itGivesUpRatherThanRetryingForever() {
        val target = File(temp.root, "archive")
        var attempts = 0

        val failure = runCatching {
            RuntimeDownload.fetch(target, { _ ->
                attempts++
                RuntimeDownload.Chunk(FailingStream(archive, 0), false, archive.size.toLong())
            }, attempts = 3)
        }

        assertTrue(failure.exceptionOrNull() is IOException)
        assertEquals(3, attempts)
    }

    @Test fun spaceIsCheckedBeforeAnyBytesAreWritten() {
        val target = File(temp.root, "archive")
        var checkedWith = -1L

        val failure = runCatching {
            RuntimeDownload.fetch(
                target,
                { RuntimeDownload.Chunk(ByteArrayInputStream(archive), false, archive.size.toLong()) },
                beforeFirstByte = { total ->
                    checkedWith = total
                    error("not enough space")
                },
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(archive.size.toLong(), checkedWith)
        assertTrue("nothing was written", !target.exists() || target.length() == 0L)
    }

    @Test fun aResumedTransferDoesNotRecheckSpace() {
        // The check is about starting a download, not continuing one; asking
        // again mid-transfer would fail on the space the partial file uses.
        val target = File(temp.root, "archive")
        target.writeBytes(archive.copyOfRange(0, 20_000))
        var checks = 0

        RuntimeDownload.fetch(
            target,
            { offset ->
                RuntimeDownload.Chunk(
                    ByteArrayInputStream(archive, offset.toInt(), archive.size - offset.toInt()),
                    resumed = true,
                    totalBytes = archive.size.toLong(),
                )
            },
            beforeFirstByte = { checks++ },
        )

        assertEquals(0, checks)
        assertArrayEquals(archive, target.readBytes())
    }
}
