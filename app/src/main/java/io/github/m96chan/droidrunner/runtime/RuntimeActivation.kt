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
    ) {
        check(staging.isDirectory) { "Nothing to activate" }
        previous.deleteRecursively()

        val hadPrevious = target.exists()
        if (hadPrevious) {
            check(rename(target, previous)) { "Cannot set the previous runtime aside" }
        }
        if (!rename(staging, target)) {
            // Put back what was working before reporting the failure.
            if (hadPrevious) rename(previous, target)
            error("Cannot activate the new runtime")
        }
        previous.deleteRecursively()
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
