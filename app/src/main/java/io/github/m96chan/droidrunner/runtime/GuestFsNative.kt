package io.github.m96chan.droidrunner.runtime

/**
 * JNI bridge to `cpp/guest_fs.c`. Every call that can fail returns a negative
 * errno; [GuestFs] turns those into messages.
 */
internal object GuestFsNative {
    init {
        System.loadLibrary("guest_fs")
    }

    @JvmStatic external fun openRoot(path: String): Int

    @JvmStatic external fun openDir(dirFd: Int, name: String, create: Boolean): Int

    @JvmStatic external fun openFile(dirFd: Int, name: String): Int

    @JvmStatic external fun size(fd: Int): Long

    @JvmStatic external fun isSymlink(dirFd: Int, name: String): Boolean

    /** The bytes copied, which fall short of [bytes] when the file shrank meanwhile. */
    @JvmStatic external fun copyOut(fd: Int, destination: String, bytes: Long): Long

    @JvmStatic external fun moveIn(source: String, dirFd: Int, name: String): Int

    @JvmStatic external fun removeTree(dirFd: Int, name: String): Int

    @JvmStatic external fun close(fd: Int)

    @JvmStatic external fun describe(errno: Int): String
}
