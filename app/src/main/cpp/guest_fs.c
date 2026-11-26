// Opens, publishes and deletes files inside the PRoot guest without following
// a symlink anywhere on the way. Job code controls every directory under the
// guest, so each step is taken relative to a directory already held open and
// with O_NOFOLLOW: a link planted or swapped in at any point fails the call
// instead of redirecting it (CWE-59). android.system.Os has no openat family,
// which is why this is native.
//
// Only POSIX.1-2008 calls, so the same file builds for the device and for the
// host JVM that runs the unit tests.
#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "guest_fs.h"

// A tree deeper than this was built to exhaust the stack or the descriptor
// table; PATH_MAX already rules out anything legitimate that deep.
#define GFS_MAX_DEPTH 1024
#define GFS_COPY_CHUNK (64 * 1024)

static int valid_name(const char *name) {
    return name[0] != '\0' && strchr(name, '/') == NULL &&
        strcmp(name, ".") != 0 && strcmp(name, "..") != 0;
}

int gfs_open_root(const char *path) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    return fd < 0 ? -errno : fd;
}

int gfs_open_dir(int dir_fd, const char *name, int create) {
    if (!valid_name(name)) return -EINVAL;
    if (create && mkdirat(dir_fd, name, 0700) < 0 && errno != EEXIST) return -errno;
    int fd = openat(dir_fd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    return fd < 0 ? -errno : fd;
}

int gfs_open_file(int dir_fd, const char *name) {
    if (!valid_name(name)) return -EINVAL;
    // O_NONBLOCK so a FIFO planted in place of a model cannot hang the agent
    // in open(); the type check below then refuses it.
    int fd = openat(dir_fd, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;
    struct stat st;
    if (fstat(fd, &st) < 0) {
        int error = errno;
        close(fd);
        return -error;
    }
    if (!S_ISREG(st.st_mode)) {
        close(fd);
        return -EINVAL;
    }
    return fd;
}

long long gfs_size(int fd) {
    struct stat st;
    return fstat(fd, &st) < 0 ? -errno : (long long) st.st_size;
}

int gfs_is_symlink(int dir_fd, const char *name) {
    struct stat st;
    return fstatat(dir_fd, name, &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(st.st_mode);
}

long long gfs_copy_out(int fd, const char *destination, long long bytes) {
    int out = open(destination, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (out < 0) return -errno;
    char *buffer = malloc(GFS_COPY_CHUNK);
    long long error = buffer == NULL ? -ENOMEM : 0;
    long long offset = 0;
    while (error == 0 && offset < bytes) {
        long long left = bytes - offset;
        size_t want = left < GFS_COPY_CHUNK ? (size_t) left : GFS_COPY_CHUNK;
        ssize_t got = pread(fd, buffer, want, (off_t) offset);
        if (got < 0) {
            if (errno == EINTR) continue;
            error = -errno;
            break;
        }
        // The job truncated the file after it was opened: report the short count.
        if (got == 0) break;
        for (ssize_t written = 0; written < got;) {
            ssize_t step = write(out, buffer + written, (size_t) (got - written));
            if (step < 0) {
                if (errno == EINTR) continue;
                error = -errno;
                break;
            }
            written += step;
        }
        offset += got;
    }
    free(buffer);
    if (close(out) < 0 && error == 0) error = -errno;
    if (error != 0 || offset != bytes) unlink(destination);
    return error != 0 ? error : offset;
}

int gfs_move_in(const char *source, int dir_fd, const char *name) {
    if (!valid_name(name)) return -EINVAL;
    // rename() replaces whatever is at the name, a symlink included, and never
    // writes through it.
    return renameat(AT_FDCWD, source, dir_fd, name) < 0 ? -errno : 0;
}

static int remove_at(int dir_fd, const char *name, int depth);

static int empty_directory(int fd, int depth) {
    // A job may leave a directory read-only (a Go module cache is): its
    // entries cannot be unlinked until it is writable again. Through the
    // descriptor, so no name is looked up.
    fchmod(fd, 0700);
    DIR *directory = fdopendir(fd);
    if (directory == NULL) {
        int error = errno;
        close(fd);
        return -error;
    }
    int result = 0;
    struct dirent *entry;
    errno = 0;
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        int removed = remove_at(dirfd(directory), entry->d_name, depth + 1);
        if (removed < 0 && result == 0) result = removed;
        errno = 0;
    }
    if (errno != 0 && result == 0) result = -errno;
    closedir(directory);
    return result;
}

static int remove_at(int dir_fd, const char *name, int depth) {
    if (depth > GFS_MAX_DEPTH) return -ELOOP;
    // Bounded, because job code may still be swapping entries under us.
    for (int attempt = 0; attempt < 4; attempt++) {
        // A real directory opens; a symlink or a file does not, and is then
        // unlinked as the entry it is. Nothing here ever follows a link.
        int fd = openat(dir_fd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (fd >= 0) {
            int emptied = empty_directory(fd, depth);
            if (emptied < 0) return emptied;
            if (unlinkat(dir_fd, name, AT_REMOVEDIR) == 0 || errno == ENOENT) return 0;
            // Refilled or swapped since it was emptied: go round again.
            if (errno != ENOTEMPTY && errno != EEXIST && errno != ENOTDIR) return -errno;
            continue;
        }
        if (errno == ENOENT) return 0;
        // A directory with no read permission cannot be opened to empty it.
        // AT_SYMLINK_NOFOLLOW makes the chmod refuse a link swapped in here;
        // bionic and glibc implement it through an O_PATH descriptor.
        if (errno == EACCES) {
            if (fchmodat(dir_fd, name, 0700, AT_SYMLINK_NOFOLLOW) < 0) return -EACCES;
            continue;
        }
        if (errno != ENOTDIR && errno != ELOOP) return -errno;
        if (unlinkat(dir_fd, name, 0) == 0 || errno == ENOENT) return 0;
        // Replaced by a directory since the open: go round again.
        if (errno != EISDIR && errno != EPERM) return -errno;
    }
    return -EAGAIN;
}

int gfs_remove_tree(int dir_fd, const char *name) {
    if (!valid_name(name)) return -EINVAL;
    return remove_at(dir_fd, name, 0);
}

// JNI bindings for io.github.m96chan.droidrunner.runtime.GuestFsNative.

#define GFS_JNI(name) Java_io_github_m96chan_droidrunner_runtime_GuestFsNative_##name

JNIEXPORT jint JNICALL GFS_JNI(openRoot)(JNIEnv *env, jclass type, jstring path) {
    (void) type;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (chars == NULL) return -ENOMEM;
    int result = gfs_open_root(chars);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return result;
}

JNIEXPORT jint JNICALL GFS_JNI(openDir)(JNIEnv *env, jclass type, jint dir_fd, jstring name, jboolean create) {
    (void) type;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (chars == NULL) return -ENOMEM;
    int result = gfs_open_dir(dir_fd, chars, create == JNI_TRUE);
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return result;
}

JNIEXPORT jint JNICALL GFS_JNI(openFile)(JNIEnv *env, jclass type, jint dir_fd, jstring name) {
    (void) type;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (chars == NULL) return -ENOMEM;
    int result = gfs_open_file(dir_fd, chars);
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return result;
}

JNIEXPORT jlong JNICALL GFS_JNI(size)(JNIEnv *env, jclass type, jint fd) {
    (void) env;
    (void) type;
    return gfs_size(fd);
}

JNIEXPORT jboolean JNICALL GFS_JNI(isSymlink)(JNIEnv *env, jclass type, jint dir_fd, jstring name) {
    (void) type;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (chars == NULL) return JNI_FALSE;
    int result = gfs_is_symlink(dir_fd, chars);
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL GFS_JNI(copyOut)(JNIEnv *env, jclass type, jint fd, jstring destination, jlong bytes) {
    (void) type;
    const char *chars = (*env)->GetStringUTFChars(env, destination, NULL);
    if (chars == NULL) return -ENOMEM;
    long long result = gfs_copy_out(fd, chars, bytes);
    (*env)->ReleaseStringUTFChars(env, destination, chars);
    return result;
}

JNIEXPORT jint JNICALL GFS_JNI(moveIn)(JNIEnv *env, jclass type, jstring source, jint dir_fd, jstring name) {
    (void) type;
    const char *from = (*env)->GetStringUTFChars(env, source, NULL);
    if (from == NULL) return -ENOMEM;
    const char *to = (*env)->GetStringUTFChars(env, name, NULL);
    if (to == NULL) {
        (*env)->ReleaseStringUTFChars(env, source, from);
        return -ENOMEM;
    }
    int result = gfs_move_in(from, dir_fd, to);
    (*env)->ReleaseStringUTFChars(env, name, to);
    (*env)->ReleaseStringUTFChars(env, source, from);
    return result;
}

JNIEXPORT jint JNICALL GFS_JNI(removeTree)(JNIEnv *env, jclass type, jint dir_fd, jstring name) {
    (void) type;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (chars == NULL) return -ENOMEM;
    int result = gfs_remove_tree(dir_fd, chars);
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return result;
}

JNIEXPORT void JNICALL GFS_JNI(close)(JNIEnv *env, jclass type, jint fd) {
    (void) env;
    (void) type;
    close(fd);
}

JNIEXPORT jstring JNICALL GFS_JNI(describe)(JNIEnv *env, jclass type, jint error) {
    (void) type;
    return (*env)->NewStringUTF(env, strerror(error));
}
