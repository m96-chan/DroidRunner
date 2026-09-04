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
}
