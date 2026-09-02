package io.github.m96chan.droidrunner.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File

/**
 * Extracts the runtime bundle. The bundle is remote content, so every entry is
 * resolved to a canonical path and rejected unless it stays inside the target
 * directory — this also stops writes that would travel *through* a symlink
 * planted by an earlier entry, since resolution follows it.
 */
object TarExtractor {

    /** Creates a symlink; injectable so the logic is testable off-device. */
    fun interface SymlinkCreator {
        fun create(linkTarget: String, linkPath: String)
    }

    private val androidSymlink = SymlinkCreator { target, path ->
        android.system.Os.symlink(target, path)
    }

    fun extract(
        archive: File,
        target: File,
        symlink: SymlinkCreator = androidSymlink,
    ) {
        val root = target.canonicalPath + File.separator
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val out = File(target, entry.name).canonicalFile
                check(out.path.startsWith(root)) { "Unsafe archive path: ${entry.name}" }
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        symlink.create(entry.linkName, out.path)
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                        // Mirror the archive's executable bits (owner/group/other).
                        if (entry.mode and 0b001001001 != 0) out.setExecutable(true, false)
                    }
                }
                entry = tar.nextTarEntry
            }
        }
    }
}
