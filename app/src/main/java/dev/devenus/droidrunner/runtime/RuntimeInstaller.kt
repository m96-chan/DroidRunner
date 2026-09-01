package dev.devenus.droidrunner.runtime

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

data class RuntimeManifest(val version: String, val url: String, val sha256: String)

class RuntimeInstaller(private val context: Context) {
    val runtimeDir = File(context.filesDir, "runner-runtime")
    val installed: Boolean get() = File(runtimeDir, ".installed").isFile

    fun install(manifestUrl: String, progress: (String) -> Unit = {}) {
        require(manifestUrl.startsWith("https://")) { "Manifest must use HTTPS" }
        progress("manifest")
        val manifest = parseManifest(URL(manifestUrl).readText())
        require(manifest.url.startsWith("https://")) { "Runtime must use HTTPS" }
        val archive = File(context.cacheDir, "runtime-${manifest.version}.tar.gz")
        progress("download")
        URL(manifest.url).openStream().use { input -> archive.outputStream().use(input::copyTo) }
        check(sha256(archive).equals(manifest.sha256, ignoreCase = true)) { "Runtime SHA-256 mismatch" }

        progress("extract")
        val staging = File(context.filesDir, "runner-runtime.new").apply { deleteRecursively(); mkdirs() }
        extractTarGz(archive, staging)
        File(staging, "bin/proot").setExecutable(true)
        File(staging, "home/runner/run.sh").setExecutable(true)
        check(File(staging, "bin/proot").canExecute()) { "Runtime bundle has no executable proot" }
        File(staging, ".installed").writeText(manifest.version)
        runtimeDir.deleteRecursively()
        check(staging.renameTo(runtimeDir)) { "Cannot activate runtime" }
        archive.delete()
    }

    private fun parseManifest(text: String): RuntimeManifest = JSONObject(text).let {
        RuntimeManifest(it.getString("version"), it.getString("url"), it.getString("sha256"))
    }

    private fun extractTarGz(archive: File, target: File) {
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val out = File(target, entry.name).canonicalFile
                check(out.path.startsWith(target.canonicalPath + File.separator)) { "Unsafe archive path" }
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        android.system.Os.symlink(entry.linkName, out.path)
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                        if (entry.mode and 0b001001001 != 0) out.setExecutable(true, false)
                    }
                }
                entry = tar.nextTarEntry
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
