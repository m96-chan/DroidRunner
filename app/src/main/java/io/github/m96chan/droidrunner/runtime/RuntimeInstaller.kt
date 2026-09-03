package io.github.m96chan.droidrunner.runtime

import android.content.Context
import io.github.m96chan.droidrunner.BuildConfig
import io.github.m96chan.droidrunner.runner.RunnerRegistration
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

    /**
     * [progress] reports the current phase and, while downloading, how far
     * along it is (0..1); null means the phase has no measurable length.
     */
    fun install(manifestUrl: String, progress: (String, Float?) -> Unit = { _, _ -> }) {
        require(manifestUrl.startsWith("https://")) { "Manifest must use HTTPS" }
        progress("reading manifest", null)
        // Verify the bytes as served: re-serialising would change what the
        // signature covers.
        val manifestBytes = URL(manifestUrl).readBytes()
        verifySignature(manifestUrl, manifestBytes, progress)
        val manifest = parseManifest(String(manifestBytes))
        require(manifest.url.startsWith("https://")) { "Runtime must use HTTPS" }
        val archive = File(context.cacheDir, "runtime-${manifest.version}.tar.gz")
        progress("downloading runtime", 0f)
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
                            progress("downloading runtime", copied.toFloat() / totalBytes)
                        }
                    }
                }
            }
        }
        check(sha256(archive).equals(manifest.sha256, ignoreCase = true)) { "Runtime SHA-256 mismatch" }

        progress("extracting runtime", null)
        val staging = File(context.filesDir, "runner-runtime.new").apply { deleteRecursively(); mkdirs() }
        try {
            TarExtractor.extract(archive, staging)
        } catch (failed: Throwable) {
            staging.deleteRecursively()
            archive.delete()
            throw failed
        }
        // The bundle is data only (proot ships inside the APK): validate the
        // pieces the runner needs before activating it.
        check(File(staging, "rootfs/usr/bin/env").exists()) { "Runtime bundle has no rootfs" }
        check(File(staging, "home/runner/run.sh").isFile) { "Runtime bundle has no runner" }
        File(staging, "home/runner/run.sh").setExecutable(true)
        File(staging, "home/runner/config.sh").setExecutable(true)
        File(staging, ".installed").writeText(manifest.version)
        // Activating replaces the whole directory, and what this device
        // registered as is stored inside it. Carrying it over is what lets a
        // registered device reinstall a missing runtime and still be the same
        // runner afterwards (issue #46).
        RunnerRegistration.copyDetails(runtimeDir, staging)
        runtimeDir.deleteRecursively()
        check(staging.renameTo(runtimeDir)) { "Cannot activate runtime" }
        archive.delete()
    }

    /**
     * Fetches the manifest's detached signature and checks it. A build without
     * trusted keys cannot verify anything; that is reported through [progress]
     * rather than failing, so self-builders are not blocked — but a build that
     * does carry keys refuses an unsigned or wrongly signed manifest.
     */
    private fun verifySignature(
        manifestUrl: String,
        manifestBytes: ByteArray,
        progress: (String, Float?) -> Unit,
    ) {
        val signature = runCatching { URL("$manifestUrl.sig").readText() }.getOrNull()
        when (val result = ManifestSignature.verify(
            manifestBytes,
            signature,
            BuildConfig.RUNTIME_SIGNING_KEYS,
        )) {
            is ManifestSignature.Result.Valid ->
                progress("manifest signature verified", null)

            is ManifestSignature.Result.Unverifiable ->
                progress("manifest signature not checked (no trusted key in this build)", null)

            is ManifestSignature.Result.Invalid ->
                error("Refusing this runtime: ${result.reason}")
        }
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
