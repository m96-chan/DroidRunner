/*
 * Part of DroidRunner. GPL-2.0-only, with the additional permission below.
 *
 * Additional permission under GNU GPL version 2, as a special exception:
 *
 * The copyright holders of this file give you permission to combine it with
 * Qualcomm's QNN runtime and LiteRT delegate libraries, and to convey the
 * resulting work. This permission covers this file only; it does not extend to
 * any other part of DroidRunner, which remains GPL-2.0-only.
 */
package io.github.m96chan.droidrunner.qnn

import io.github.m96chan.droidrunner.npu.QnnArtifacts
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Checks Qualcomm's libraries against their pinned SHA-256 before this process
 * loads them.
 *
 * The installer checks each file once, when it downloads it. A file changed on
 * disk after that would run as this app, which can ask the Keystore to decrypt
 * the stored GitHub tokens. No known path writes there; this is the second
 * layer in case one appears.
 *
 * What it does not cover:
 *  - The gap between the hash and dlopen, which opens the file again by path.
 *    Loading from the descriptor that was hashed would close it.
 *  - The skel is checked here, but the DSP loads it, and falls back to the
 *    vendor's copy when ours is absent. Nothing here can attest that copy.
 */
internal object QnnIntegrity {
    /** Libraries dlopen keeps in this process, so a change on disk cannot reach them. */
    private val loaded = mutableSetOf<String>()

    /**
     * Why [libraries] in [directory] must not be loaded, or null when each one
     * matches its pin. For an HTP load, the skel the DSP will read is checked
     * too, on every call, because this process never holds it.
     */
    @Synchronized
    fun problem(
        directory: File,
        libraries: List<String>,
        pinOf: (String) -> String? = QnnArtifacts::sha256Of,
    ): String? {
        // A stub brings its generation's skel, which the DSP reads.
        for (name in (libraries + libraries.flatMap(QnnArtifacts::generationOf)).distinct()) {
            val file = File(directory, name)
            if (file.path in loaded) continue
            val pinned = pinOf(name) ?: return "$name has no pinned SHA-256; reinstall the QNN runtime"
            val actual = try {
                sha256(file)
            } catch (unreadable: IOException) {
                return "cannot read $name (${unreadable.message}); reinstall the QNN runtime"
            }
            if (!actual.equals(pinned, ignoreCase = true)) {
                return "$name does not match its pinned SHA-256; reinstall the QNN runtime"
            }
        }
        return null
    }

    /** Records that this process has loaded [libraries] from [directory]. */
    @Synchronized
    fun loaded(directory: File, libraries: List<String>) {
        libraries.forEach { loaded += File(directory, it).path }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.forEachBlock(1 shl 16) { buffer, read -> digest.update(buffer, 0, read) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
