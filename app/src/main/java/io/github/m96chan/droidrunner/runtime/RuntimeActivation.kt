package io.github.m96chan.droidrunner.runtime

import java.io.File

/**
 * Putting a freshly extracted runtime in place without risking the one that
 * works (issue #43).
 *
 * The order matters more than it looks. Deleting the live runtime and then
 * renaming the new one over it leaves a device with nothing at all if the
 * rename fails or the process dies in between — and the only way back from
 * that is another ~200MB download. Moving the old one aside first means every
 * failure still leaves something that runs.
 */
internal object RuntimeActivation {
    // Installation may perform lengthy cleanup; startup only shares the
    // separate, short directory-swap monitor (this object).
    private val installationLock = Any()
    fun isRuntime(dir: File): Boolean =
        File(dir, ".installed").isFile &&
            File(dir, ".installed").readText().isNotBlank() &&
            File(dir, "rootfs/usr/bin/env").exists() &&
            File(dir, "home/runner/run.sh").isFile

    /**
     * Recover either side of an interrupted directory swap before inspecting
     * installation state. Startup only validates or renames directories;
     * [cleanupPrevious] is reserved for installation on a worker thread.
     */
    fun recover(
        target: File,
        previous: File,
        valid: (File) -> Boolean = File::isDirectory,
        rename: (File, File) -> Boolean = File::renameTo,
        cleanupPrevious: Boolean = false,
    ) {
        if (!cleanupPrevious && valid(target)) return
        if (cleanupPrevious) {
            synchronized(installationLock) {
                recover(target, previous, valid, rename)
                val retired = synchronized(this) {
                    if (previous.exists() && valid(target)) retire(previous) else null
                }
                cleanRetired(previous, retired)
            }
            return
        }
        synchronized(this) {
            if (!previous.exists() || valid(target)) return
            check(valid(previous)) { "Previous runtime is incomplete; preserving it for recovery" }
            // Preserve an incomplete target too: it may contain registration details.
            val rejected = File(target.parentFile, target.name + ".failed")
            val hadTarget = target.exists()
            if (hadTarget) {
                check(!rejected.exists() && rename(target, rejected)) {
                    "Cannot preserve the incomplete runtime"
                }
            }
            if (!rename(previous, target)) {
                if (hadTarget) rename(rejected, target)
                error("Cannot restore the previous runtime; backup retained")
            }
        }
    }

    /**
     * Moves [staging] to [target], keeping the previous contents at
     * [previous] until the move has succeeded.
     *
     * [rename] exists so a test can make a rename fail; production always
     * passes `File::renameTo`.
     */
    fun activate(
        staging: File,
        target: File,
        previous: File,
        rename: (File, File) -> Boolean = File::renameTo,
        valid: (File) -> Boolean = File::isDirectory,
        prepare: () -> Unit = {},
    ) = synchronized(installationLock) {
        check(valid(staging)) { "Nothing valid to activate" }
        recover(target, previous, valid, rename, cleanupPrevious = true)
        val retired = synchronized(this) {
            prepare()
            val hadPrevious = target.exists()
            if (hadPrevious) {
                check(rename(target, previous)) { "Cannot set the previous runtime aside" }
            }
            if (!rename(staging, target)) {
                // Put back what was working before reporting the failure.
                if (hadPrevious && !rename(previous, target)) {
                    error("Cannot activate the new runtime or restore the previous runtime; backup retained")
                }
                error("Cannot activate the new runtime")
            }
            if (previous.exists()) retire(previous) else null
        }
        cleanRetired(previous, retired)
    }

    /** Detach under the swap monitor, then delete on the installation thread. */
    private fun retire(previous: File): File {
        val retired = File(previous.parentFile, previous.name + ".cleanup-" + java.util.UUID.randomUUID())
        check(previous.renameTo(retired)) { "Cannot set aside the previous runtime for cleanup" }
        return retired
    }

    private fun cleanRetired(previous: File, retired: File?) {
        if (retired != null) check(retired.deleteRecursively()) { "Cannot clean up the previous runtime" }
        // A process death during cleanup must not accumulate abandoned trees.
        previous.parentFile?.listFiles { file -> file.name.startsWith(previous.name + ".cleanup-") }
            ?.forEach { check(it.deleteRecursively()) { "Cannot clean up an interrupted runtime deletion" } }
    }

    /**
     * Free bytes needed before starting a download of [archiveBytes].
     *
     * The archive is kept while it is extracted, and a rootfs expands to
     * roughly three times its compressed size, so four times the download is
     * the floor. During an update the previous runtime is still on disk as
     * well — that is already accounted for, because the caller measures the
     * space actually free at that moment.
     */
    fun requiredBytes(archiveBytes: Long): Long = archiveBytes * 4
}
