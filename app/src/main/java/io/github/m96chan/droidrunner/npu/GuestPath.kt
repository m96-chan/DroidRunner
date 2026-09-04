package io.github.m96chan.droidrunner.npu

import java.io.File

/**
 * Translates a path as a job sees it into a path on the device.
 *
 * A job hands the agent a model it just checked out, e.g.
 * `/home/runner/_work/repo/repo/model.tflite`. That file already lives in
 * app-private storage — the guest's `/home` is a bind mount of the runtime
 * directory — so nothing needs uploading. What does need care is the
 * translation: job code is untrusted, so a path must be proven to stay inside
 * the runner's home before anything opens it.
 */
object GuestPath {
    private const val GUEST_HOME = "/home/runner"

    /**
     * Resolves [guestPath] under [runtimeDir], or null when it is not an
     * absolute path under the guest's runner home, or escapes it via `..`
     * or a symlink.
     */
    fun resolve(runtimeDir: File, guestPath: String): File? {
        if (!guestPath.startsWith("$GUEST_HOME/")) return null
        val home = File(runtimeDir, "home/runner")
        val candidate = File(home, guestPath.removePrefix("$GUEST_HOME/"))
        val root = runCatching { home.canonicalPath }.getOrNull() ?: return null
        val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!resolved.path.startsWith(root + File.separator)) return null
        return resolved.takeIf { it.isFile }
    }

    /**
     * Resolves a directory for a job to receive files in, creating it when it
     * is not there yet (issue #92).
     *
     * Separate from [resolve] because that one insists on an existing file, and
     * an output directory is by definition somewhere nothing has been written
     * yet. The confinement is the same and is checked the same way: job code is
     * untrusted, and "write these tensors wherever I say" is a worse primitive
     * than "read this model" if it escapes.
     */
    fun resolveDirectory(runtimeDir: File, guestPath: String): File? {
        if (!guestPath.startsWith("$GUEST_HOME/")) return null
        val home = File(runtimeDir, "home/runner")
        val root = runCatching { home.canonicalPath }.getOrNull() ?: return null
        val candidate = File(home, guestPath.removePrefix("$GUEST_HOME/"))
        // Canonicalised before it exists, so a symlink in the existing part of
        // the path cannot smuggle the rest of it outside.
        val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!resolved.path.startsWith(root + File.separator)) return null
        if (!resolved.exists() && !resolved.mkdirs()) return null
        return resolved.takeIf { it.isDirectory }
    }
}
