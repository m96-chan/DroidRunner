package dev.devenus.droidrunner.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The runtime bundle is remote content, so extraction is a trust boundary:
 * a malicious archive must not be able to write outside the staging directory.
 */
class TarExtractorTest {
    @get:Rule val temp = TemporaryFolder()

    private val createdLinks = mutableListOf<Pair<String, String>>()
    private val recordingSymlink = TarExtractor.SymlinkCreator { target, path ->
        createdLinks += target to path
        File(path).writeText("symlink->$target")
    }

    private fun archiveOf(build: TarArchiveOutputStream.() -> Unit): File {
        val archive = temp.newFile("bundle-${System.nanoTime()}.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(archive.outputStream().buffered())).use {
            it.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            it.build()
            it.finish()
        }
        return archive
    }

    private fun TarArchiveOutputStream.addFile(name: String, content: String, mode: Int = 0b110100100) {
        val entry = TarArchiveEntry(name)
        val bytes = content.toByteArray()
        entry.size = bytes.size.toLong()
        entry.mode = mode
        putArchiveEntry(entry)
        write(bytes)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.addSymlink(name: String, linkTarget: String) {
        val entry = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
        entry.linkName = linkTarget
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    @Test fun extractsFilesDirectoriesAndSymlinks() {
        val archive = archiveOf {
            putArchiveEntry(TarArchiveEntry("rootfs/usr/bin/"))
            closeArchiveEntry()
            addFile("rootfs/usr/bin/env", "#!/bin/sh\n", mode = 0b111101101)
            addFile("home/runner/run.sh", "echo hi\n", mode = 0b111101101)
            addFile("home/runner/notes.txt", "plain", mode = 0b110100100)
            addSymlink("rootfs/bin/sh", "/usr/bin/env")
        }
        val target = temp.newFolder("staging")

        TarExtractor.extract(archive, target, recordingSymlink)

        assertEquals("echo hi\n", File(target, "home/runner/run.sh").readText())
        assertTrue(File(target, "home/runner/run.sh").canExecute())
        assertFalse("non-executable entries stay non-executable", File(target, "home/runner/notes.txt").canExecute())
        assertEquals(listOf("/usr/bin/env" to File(target, "rootfs/bin/sh").path), createdLinks)
    }

    @Test fun rejectsParentTraversalEntries() {
        val archive = archiveOf { addFile("../escaped.txt", "pwned") }
        val target = temp.newFolder("staging")

        val error = assertThrows(IllegalStateException::class.java) {
            TarExtractor.extract(archive, target, recordingSymlink)
        }
        assertTrue(error.message!!.contains("Unsafe archive path"))
        assertFalse(File(target.parentFile, "escaped.txt").exists())
    }

    @Test fun containsAbsolutePathEntriesInsideTheTarget() {
        // Absolute entry names are re-rooted under the target (tar strips the
        // leading slash, and File(target, name) resolves the rest relatively),
        // so they are contained rather than rejected — what matters is that
        // nothing lands at the absolute location.
        val outside = temp.newFolder("outside")
        val archive = archiveOf { addFile("${outside.absolutePath}/escaped.txt", "pwned") }
        val target = temp.newFolder("staging")

        TarExtractor.extract(archive, target, recordingSymlink)

        assertFalse("must not write to the absolute path", File(outside, "escaped.txt").exists())
        val extracted = target.walkTopDown().filter { it.isFile }.toList()
        assertTrue("everything must stay under the target", extracted.all {
            it.canonicalPath.startsWith(target.canonicalPath + File.separator)
        })
    }

    @Test fun rejectsWritesThroughAnExtractedSymlink() {
        // Classic tar attack: plant a symlink, then write "into" it so the
        // write lands outside the staging directory.
        val outside = temp.newFolder("outside")
        val archive = archiveOf {
            addSymlink("escape", outside.absolutePath)
            addFile("escape/payload.txt", "pwned")
        }
        val target = temp.newFolder("staging")

        // Use real symlinks here so path resolution behaves as it does on device.
        val realSymlink = TarExtractor.SymlinkCreator { linkTarget, path ->
            java.nio.file.Files.createSymbolicLink(
                java.nio.file.Paths.get(path),
                java.nio.file.Paths.get(linkTarget),
            )
        }

        assertThrows(IllegalStateException::class.java) {
            TarExtractor.extract(archive, target, realSymlink)
        }
        assertFalse("payload must not land outside the target", File(outside, "payload.txt").exists())
    }

    @Test fun rejectsNestedTraversalInsideAPath() {
        val archive = archiveOf { addFile("home/../../escaped.txt", "pwned") }
        val target = temp.newFolder("staging")

        assertThrows(IllegalStateException::class.java) {
            TarExtractor.extract(archive, target, recordingSymlink)
        }
    }
}
