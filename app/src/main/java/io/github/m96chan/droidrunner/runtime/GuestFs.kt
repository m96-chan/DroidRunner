package io.github.m96chan.droidrunner.runtime

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The only code outside PRoot that opens, creates, replaces or deletes files
 * inside the guest.
 *
 * Job code owns every directory under the guest, and this process runs with
 * the app's own access. Checking a path and then opening it again by name
 * lets a job swap in a symlink between the two and send a read, write or
 * delete anywhere the app can reach. So nothing here reopens a path by name:
 * each step starts from a directory already held open and uses O_NOFOLLOW,
 * and a symlink anywhere on the way fails the call with a message that names
 * it. The guest still sees through its own links; only this process refuses
 * them.
 */
internal object GuestFs {
    /** The runner's home as the job sees it. */
    const val GUEST_HOME = "/home/runner"

    /** The same directory below the runtime directory: the guest's `/home` is a bind of `runtimeDir/home`. */
    val RUNNER_HOME = listOf("home", "runner")

    /** The caller's path cannot be used; the message names the part and why. */
    class Refused(message: String) : IOException(message)

    /** A directory held open: no later rename or symlink can redirect it. */
    class Directory(val fd: Int, val shown: String) : Closeable {
        override fun close() = GuestFsNative.close(fd)
    }

    /** A private directory for files on their way into or out of the guest, deleted on [close]. */
    class Stage(val directory: File) : Closeable {
        override fun close() {
            directory.deleteRecursively()
        }
    }

    /**
     * Opens [components] below [root], creating missing ones when [create].
     * [root] itself is trusted, so a link in it is followed; nothing below it
     * is.
     */
    fun openDirectory(root: File, components: List<String>, create: Boolean = false): Directory {
        var fd = GuestFsNative.openRoot(root.path)
        if (fd < 0) throw IOException("cannot open ${root.path}: ${describe(fd)}")
        try {
            components.forEachIndexed { index, name ->
                val next = GuestFsNative.openDir(fd, name, create)
                if (next < 0) throw failure(fd, name, next, shown(components, index))
                GuestFsNative.close(fd)
                fd = next
            }
            return Directory(fd, shown(components, components.lastIndex)).also { fd = -1 }
        } finally {
            // Still ours only when something threw before the Directory took it.
            if (fd >= 0) GuestFsNative.close(fd)
        }
    }

    /** Opens the job's directory at [guestPath], creating what is missing. */
    fun openJobDirectory(runtimeDir: File, guestPath: String): Directory =
        openDirectory(runtimeDir, jobComponents(guestPath), create = true)

    /**
     * Copies the job's regular file at [guestPath] into [into] under its own
     * name, and returns the copy.
     *
     * A copy rather than the open file, because the job can still rewrite a
     * file's contents while a model runs on it.
     */
    fun stageJobFile(runtimeDir: File, guestPath: String, into: File): File {
        val components = jobComponents(guestPath)
        val name = components.last()
        return openDirectory(runtimeDir, components.dropLast(1)).use { parent ->
            val fd = GuestFsNative.openFile(parent.fd, name)
            if (fd < 0) throw failure(parent.fd, name, fd, shown(components, components.lastIndex))
            try {
                val size = GuestFsNative.size(fd)
                if (size < 0) throw IOException("cannot read $guestPath: ${describe(size.toInt())}")
                Files.createDirectories(into.toPath())
                // st_size, not the blocks used: a sparse file costs the job
                // nothing and would cost the copy every byte.
                val free = into.usableSpace - STAGING_RESERVE_BYTES
                if (size > free) {
                    throw IOException("$guestPath is $size bytes, and only ${free.coerceAtLeast(0)} can be staged")
                }
                val copy = File(into, name)
                val copied = GuestFsNative.copyOut(fd, copy.path, size)
                if (copied < 0) throw IOException("cannot read $guestPath: ${describe(copied.toInt())}")
                if (copied != size) throw Refused("$guestPath changed while it was being read")
                copy
            } finally {
                GuestFsNative.close(fd)
            }
        }
    }

    /**
     * Moves the private file [source] into [into] as [name]. A rename replaces
     * whatever is at [name], a symlink included, and never writes through it.
     * [source] must be on the same file system, which [newStage] guarantees.
     */
    fun publish(source: File, into: Directory, name: String) {
        val moved = GuestFsNative.moveIn(source.path, into.fd, name)
        if (moved < 0) throw IOException("cannot write ${into.shown}/$name: ${describe(moved)}")
    }

    /**
     * Replaces [name] in the directory at [components] below [root] with what
     * [write] puts in a private file, with [permissions] such as `rw-------`.
     * Written privately and renamed in, so a link a job left at [name] is
     * replaced rather than written through.
     */
    fun replaceFile(
        root: File,
        components: List<String>,
        name: String,
        permissions: String,
        write: (File) -> Unit,
    ) = newStage(root).use { stage ->
        val staged = File(stage.directory, name).also(write)
        Files.setPosixFilePermissions(staged.toPath(), PosixFilePermissions.fromString(permissions))
        openDirectory(root, components, create = true).use { publish(staged, it, name) }
    }

    /**
     * Deletes the last of [components] below [root] and everything under it.
     * A symlink is removed as the link itself and never entered.
     */
    fun removeTree(root: File, components: List<String>) {
        openDirectory(root, components.dropLast(1)).use { parent ->
            val removed = GuestFsNative.removeTree(parent.fd, components.last())
            if (removed < 0) {
                throw IOException("cannot delete ${shown(components, components.lastIndex)}: ${describe(removed)}")
            }
        }
    }

    /** Deletes [tree], whose parent is trusted, and everything under it. */
    fun removeTree(tree: File) = removeTree(tree.absoluteFile.parentFile, listOf(tree.name))

    /**
     * A fresh private directory for files on their way into or out of the
     * guest. A sibling of [runtimeDir], so a [publish] rename stays on one file
     * system, and outside the guest, so no job can reach it.
     */
    fun newStage(runtimeDir: File): Stage {
        // createDirectories, not mkdirs: two requests may create it at once.
        val root = Files.createDirectories(stagingRoot(runtimeDir).toPath())
        return Stage(Files.createTempDirectory(root, "stage-").toFile())
    }

    fun stagingRoot(runtimeDir: File): File = File(runtimeDir.parentFile, "guest-staging")

    /**
     * Where [guestPath] sits below the runtime directory: [RUNNER_HOME], then
     * the rest.
     */
    private fun jobComponents(guestPath: String): List<String> {
        if (!guestPath.startsWith("$GUEST_HOME/") || Char(0) in guestPath) {
            throw Refused("$guestPath is not under $GUEST_HOME")
        }
        val rest = guestPath.removePrefix("$GUEST_HOME/").split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (".." in rest) throw Refused("$guestPath contains '..'; pass the path without it")
        if (rest.isEmpty()) throw Refused("$guestPath is $GUEST_HOME itself")
        return RUNNER_HOME + rest
    }

    private fun failure(parentFd: Int, name: String, error: Int, shown: String): IOException {
        val why = when {
            GuestFsNative.isSymlink(parentFd, name) ->
                "is a symlink, and the device agent follows none; pass the path it points to"
            error == -ENOENT -> "does not exist"
            error == -ENOTDIR -> "is not a directory"
            error == -EINVAL -> "is not a regular file"
            else -> return IOException("cannot open $shown: ${describe(error)}")
        }
        return Refused("$shown $why")
    }

    private fun shown(components: List<String>, index: Int): String =
        "/" + components.take(index + 1).joinToString("/")

    private fun describe(error: Int): String = GuestFsNative.describe(-error)

    /** Left free after staging, so a large model cannot starve the app's own writes. */
    private const val STAGING_RESERVE_BYTES = 256L * 1024 * 1024

    // The same numbers on Linux and on the macOS hosts that run the unit tests.
    private const val ENOENT = 2
    private const val ENOTDIR = 20
    private const val EINVAL = 22
}
