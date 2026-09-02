package dev.devenus.droidrunner.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

data class RuntimeManifest(val version: String, val url: String, val sha256: String)

class RuntimeInstaller(private val context: Context) {
    val runtimeDir = File(context.filesDir, "runner-runtime")
    val installed: Boolean get() = File(runtimeDir, ".installed").isFile
    val installedVersion: String?
        get() = File(runtimeDir, ".installed").takeIf { it.isFile }?.readText()?.trim()

    fun install(manifestUrl: String, progress: (String) -> Unit = {}) {
        require(manifestUrl.startsWith("https://")) { "Manifest must use HTTPS" }
        progress("manifest")
        val manifest = parseManifest(URL(manifestUrl).readText())
        require(manifest.url.startsWith("https://")) { "Runtime must use HTTPS" }
        val archive = File(context.cacheDir, "runtime-${manifest.version}.tar.gz")
        progress("download")
        val connection = URL(manifest.url).openConnection()
        val totalBytes = connection.contentLengthLong
        connection.getInputStream().use { input ->
            archive.outputStream().use { out ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                var lastStep = -1
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    copied += count
                    if (totalBytes > 0) {
                        val step = (copied * 20 / totalBytes).toInt()
                        if (step != lastStep) {
                            lastStep = step
                            progress("download ${step * 5}%")
                        }
                    }
                }
            }
        }
        check(sha256(archive).equals(manifest.sha256, ignoreCase = true)) { "Runtime SHA-256 mismatch" }

        progress("extract")
        val staging = File(context.filesDir, "runner-runtime.new").apply { deleteRecursively(); mkdirs() }
        TarExtractor.extract(archive, staging)
        // The bundle is data only (proot ships inside the APK): validate the
        // pieces the runner needs before activating it.
        check(File(staging, "rootfs/usr/bin/env").exists()) { "Runtime bundle has no rootfs" }
        check(File(staging, "home/runner/run.sh").isFile) { "Runtime bundle has no runner" }
        File(staging, "home/runner/run.sh").setExecutable(true)
        File(staging, "home/runner/config.sh").setExecutable(true)
        File(staging, ".installed").writeText(manifest.version)
        runtimeDir.deleteRecursively()
        check(staging.renameTo(runtimeDir)) { "Cannot activate runtime" }
        archive.delete()
    }

    private fun parseManifest(text: String): RuntimeManifest = JSONObject(text).let {
        RuntimeManifest(it.getString("version"), it.getString("url"), it.getString("sha256"))
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
