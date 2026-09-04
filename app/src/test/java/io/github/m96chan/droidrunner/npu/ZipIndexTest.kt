package io.github.m96chan.droidrunner.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipIndexTest {

    @Test fun everyEntryIsFoundWithItsPositionAndSize() {
        val zip = zipOf("first.txt" to "hello".repeat(100), "second.bin" to "x".repeat(5000))

        val entries = ZipIndex.entriesIn(directoryOf(zip)).associateBy { it.name }

        assertEquals(setOf("first.txt", "second.bin"), entries.keys)
        assertEquals(500L, entries.getValue("first.txt").uncompressedBytes)
        assertEquals(5000L, entries.getValue("second.bin").uncompressedBytes)
        // Compressible text must actually have been deflated, or the extractor
        // would be reading raw bytes through an inflater.
        assertTrue(entries.getValue("second.bin").deflated)
        assertTrue(entries.getValue("second.bin").compressedBytes < 5000)
    }

    @Test fun anEntrysDataStartsAfterItsOwnLocalHeader() {
        // The local header carries its own extra-field length, which zip
        // writers use for alignment and which need not match the central
        // directory's. Trusting the directory's copy would start the read a few
        // bytes into the compressed stream, and inflate garbage.
        val zip = zipOf("a/deeply/nested/name.txt" to "payload")
        val entry = ZipIndex.entriesIn(directoryOf(zip)).single()

        val header = zip.copyOfRange(
            entry.localHeaderOffset.toInt(),
            entry.localHeaderOffset.toInt() + ZipIndex.LOCAL_HEADER_BYTES,
        )
        val start = ZipIndex.dataStart(entry, header)

        assertEquals(entry.localHeaderOffset + 30 + "a/deeply/nested/name.txt".length, start)
    }

    @Test fun aCommentThatLooksLikeTheRecordDoesNotFoolTheScan() {
        // The end-of-directory signature can appear inside a trailing comment.
        // Scanning forwards would stop at the decoy; the real record is the
        // last one.
        val zip = zipOf("f" to "v", comment = "PK decoy")

        val directory = ZipIndex.directoryIn(zip)
        val entries = ZipIndex.entriesIn(
            zip.copyOfRange(directory.offset.toInt(), (directory.offset + directory.bytes).toInt()),
        )

        assertEquals(listOf("f"), entries.map { it.name })
    }

    @Test fun somethingThatIsNotAZipIsRefused() {
        assertThrows(IllegalStateException::class.java) {
            ZipIndex.directoryIn(ByteArray(200) { 0 })
        }
    }

    private fun directoryOf(zip: ByteArray): ByteArray {
        val directory = ZipIndex.directoryIn(zip)
        return zip.copyOfRange(
            directory.offset.toInt(),
            (directory.offset + directory.bytes).toInt(),
        )
    }

    private fun zipOf(vararg files: Pair<String, String>, comment: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            comment?.let { zip.setComment(it) }
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
