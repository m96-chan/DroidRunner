package io.github.m96chan.droidrunner.runtime

import android.content.Context
import android.util.Log
import io.github.m96chan.droidrunner.BuildConfig
import io.github.m96chan.droidrunner.runner.RunnerRegistration
import org.json.JSONObject
import java.io.File
import java.net.URL

data class RuntimeManifest(val version: String, val url: String, val sha256: String)

class RuntimeInstaller(private val context: Context) {
    val runtimeDir = File(context.filesDir, "runner-runtime")
    private val previousDir = File(context.filesDir, "runner-runtime.old")
    private var recoveryFailed = false

    init {
        // Keep setup accessible if storage prevents a rename. An explicit
        // install retries recovery and surfaces its error before changing files.
        runCatching { recover() }.onFailure { Log.w("RuntimeInstaller", "Runtime recovery failed", it) }
    }

    private fun recover(cleanupPrevious: Boolean = false) {
        recoveryFailed = true
        RuntimeActivation.recover(
            runtimeDir, previousDir, RuntimeActivation::isRuntime,
            cleanupPrevious = cleanupPrevious,
        )
        recoveryFailed = false
    }

    val installed: Boolean get() = !recoveryFailed && File(runtimeDir, ".installed").isFile
    val installedVersion: String?
        get() = File(runtimeDir, ".installed").takeIf { installed }?.readText()?.trim()

    /**
     * [progress] reports the current phase and, while downloading, how far
     * along it is (0..1); null means the phase has no measurable length.
     */
    fun install(manifestUrl: String, progress: (String, Float?) -> Unit = { _, _ -> }) {
        recover(cleanupPrevious = true)
        require(manifestUrl.startsWith("https://")) { "Manifest must use HTTPS" }
        progress("reading manifest", null)
        // Verify the bytes as served: re-serialising would change what the
        // signature covers.
        val manifestBytes = URL(manifestUrl).readBytes()
        verifySignature(manifestUrl, manifestBytes, progress)
        val manifest = parseManifest(String(manifestBytes))
        require(manifest.url.startsWith("https://")) { "Runtime must use HTTPS" }
        // Kept under filesDir, not cacheDir: the system is free to reclaim the
        // cache under storage pressure, which is exactly what a 200MB download
        // creates.
        val archive = File(context.filesDir, "runtime-download.tar.gz")
        download(manifest, archive, progress)

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
        RuntimeActivation.activate(
            staging = staging,
            target = runtimeDir,
            previous = previousDir,
            valid = RuntimeActivation::isRuntime,
            prepare = { RunnerRegistration.copyDetails(runtimeDir, staging) },
        )
        archive.delete()
        File(archive.path + ".identity").delete()
    }

    /**
     * Wires [RuntimeDownload] to HTTP. Everything worth testing lives there;
     * this is the part that only a real server can exercise.
     */
    private fun download(
        manifest: RuntimeManifest,
        archive: File,
        progress: (String, Float?) -> Unit,
    ) {
        progress("downloading runtime", 0f)
        RuntimeDownload.fetchVerified(
            target = archive,
            identity = manifest.version + "\n" + manifest.url,
            expectedSha256 = manifest.sha256,
            source = { offset ->
                val connection = URL(manifest.url).openConnection() as java.net.HttpURLConnection
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                try {
                    if (connection.responseCode == 416) throw RuntimeDownload.RangeRejected()
                    val resumed = offset > 0 && connection.responseCode == 206
                    val length = connection.contentLengthLong
                    RuntimeDownload.Chunk(
                        stream = object : java.io.FilterInputStream(connection.inputStream) {
                            override fun close() {
                                try { super.close() } finally { connection.disconnect() }
                            }
                        },
                        resumed = resumed,
                        totalBytes = if (length > 0) length + (if (resumed) offset else 0L) else -1L,
                    )
                } catch (failed: Throwable) {
                    connection.disconnect()
                    throw failed
                }
            },
            beforeFirstByte = { checkSpaceFor(it) },
            progress = { progress("downloading runtime", it) },
        )
    }

    /**
     * Refuses to start rather than failing part-way through an extraction with
     * whatever error the filesystem happens to raise. The app already watches
     * free storage before accepting jobs; it should hold itself to the same
     * check.
     */
    private fun checkSpaceFor(archiveBytes: Long) {
        if (archiveBytes <= 0) return
        val needed = RuntimeActivation.requiredBytes(archiveBytes)
        val free = context.filesDir.usableSpace
        check(free >= needed) {
            "Not enough free space: the runtime needs about ${needed / MB}MB " +
                "and this device has ${free / MB}MB"
        }
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

    private companion object {
        const val MB = 1024L * 1024
    }

}
