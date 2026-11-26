// Runs the guest_fs.c syscall layer on a real Android kernel, outside the app.
// tools/test-guest-fs-on-device.sh builds it with the NDK and runs it over adb.
// The unit tests cover the same cases on the host; this proves that Android's
// kernel and SELinux policy give the same answers.
//
// Usage: guest_fs_test <empty scratch directory>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "guest_fs.h"

static int failures = 0;
static char root[512];

static void check(const char *name, int ok) {
    printf("  %s %s\n", ok ? "ok  " : "FAIL", name);
    if (!ok) failures++;
}

static const char *at(const char *relative) {
    static char paths[8][1024];
    static int next = 0;
    char *path = paths[next++ % 8];
    snprintf(path, sizeof paths[0], "%s/%s", root, relative);
    return path;
}

static void write_file(const char *relative, const char *text) {
    FILE *file = fopen(at(relative), "w");
    fputs(text, file);
    fclose(file);
}

static int read_is(const char *path, const char *text) {
    char buffer[64] = {0};
    FILE *file = fopen(path, "r");
    if (file == NULL) return 0;
    size_t read = fread(buffer, 1, sizeof buffer - 1, file);
    fclose(file);
    return read == strlen(text) && memcmp(buffer, text, read) == 0;
}

static int is_link(const char *path) {
    struct stat st;
    return lstat(path, &st) == 0 && S_ISLNK(st.st_mode);
}

static int exists(const char *path) {
    struct stat st;
    return lstat(path, &st) == 0;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s <empty scratch directory>\n", argv[0]);
        return 2;
    }
    snprintf(root, sizeof root, "%s", argv[1]);

    mkdir(at("guest"), 0700);
    mkdir(at("guest/home"), 0700);
    mkdir(at("outside"), 0700);
    write_file("outside/secret", "app data");
    write_file("guest/home/model", "model");
    symlink(at("outside/secret"), at("guest/home/link"));
    symlink(at("outside"), at("guest/dirlink"));
    // SELinux denies the adb shell user FIFOs on some phones; the host unit
    // tests cover the case there.
    int have_fifo = mkfifo(at("guest/home/fifo"), 0600) == 0;
    int fifo_error = errno;
    mkdir(at("guest/home/dir"), 0700);

    int guest = gfs_open_root(at("guest"));
    check("opens the trusted root", guest >= 0);
    int home = gfs_open_dir(guest, "home", 0);
    check("opens a real directory", home >= 0);

    printf("reading\n");
    int model = gfs_open_file(home, "model");
    check("opens a regular file", model >= 0);
    check("reports its size", gfs_size(model) == 5);
    check("copies it out", gfs_copy_out(model, at("copy"), 5) == 5 && read_is(at("copy"), "model"));
    close(model);
    check("refuses a symlinked file", gfs_open_file(home, "link") < 0);
    check("names it as a symlink", gfs_is_symlink(home, "link"));
    if (have_fifo) {
        check("refuses a FIFO without hanging", gfs_open_file(home, "fifo") == -EINVAL);
    } else {
        printf("  skip refuses a FIFO without hanging (mkfifo: %s)\n", strerror(fifo_error));
    }
    check("refuses a directory as a file", gfs_open_file(home, "dir") == -EINVAL);
    check("refuses a symlinked directory", gfs_open_dir(guest, "dirlink", 0) < 0);
    check("refuses '..'", gfs_open_dir(home, "..", 0) == -EINVAL);
    check("refuses a name with a slash", gfs_open_file(guest, "home/model") == -EINVAL);

    printf("creating\n");
    int created = gfs_open_dir(home, "out", 1);
    check("creates a missing directory", created >= 0);
    check("does not create through a link", gfs_open_dir(guest, "dirlink", 1) < 0);

    printf("publishing\n");
    symlink(at("outside/secret"), at("guest/home/out/output-0.bin"));
    write_file("staged", "tensor");
    check("moves a file in over a planted symlink", gfs_move_in(at("staged"), created, "output-0.bin") == 0);
    check("the link's target is unchanged", read_is(at("outside/secret"), "app data"));
    check("the published file is not a link", !is_link(at("guest/home/out/output-0.bin")));
    check("the published file holds the tensor", read_is(at("guest/home/out/output-0.bin"), "tensor"));

    // A job moves a held directory away and plants a link in its place.
    rename(at("guest/home/out"), at("guest/home/moved"));
    symlink(at("outside"), at("guest/home/out"));
    write_file("staged", "second");
    check("publishes into the held directory after a swap", gfs_move_in(at("staged"), created, "output-1.bin") == 0);
    check("nothing lands behind the swapped-in link", !exists(at("outside/output-1.bin")));
    check("the file lands in the moved directory", read_is(at("guest/home/moved/output-1.bin"), "second"));
    close(created);

    printf("deleting\n");
    mkdir(at("guest/home/_work"), 0700);
    mkdir(at("guest/home/_work/a"), 0700);
    write_file("guest/home/_work/a/file", "job");
    symlink(at("outside"), at("guest/home/_work/a/dirlink"));
    symlink(at("outside/secret"), at("guest/home/_work/filelink"));
    // An ordinary job leaves read-only directories, and a locked one stops a
    // delete that cannot open it.
    mkdir(at("guest/home/_work/readonly"), 0700);
    write_file("guest/home/_work/readonly/file", "job");
    chmod(at("guest/home/_work/readonly"), 0555);
    mkdir(at("guest/home/_work/locked"), 0700);
    write_file("guest/home/_work/locked/file", "job");
    chmod(at("guest/home/_work/locked"), 0);
    check("removes a tree", gfs_remove_tree(home, "_work") == 0);
    check("the tree is gone", !exists(at("guest/home/_work")));
    check("a linked directory's contents survive", read_is(at("outside/secret"), "app data"));
    check("removing a missing tree is not an error", gfs_remove_tree(home, "_work") == 0);
    check("removes a symlink as itself", gfs_remove_tree(home, "link") == 0 && !exists(at("guest/home/link")));
    check("and leaves its target alone", read_is(at("outside/secret"), "app data"));

    close(home);
    close(guest);
    printf("%d failed\n", failures);
    return failures == 0 ? 0 : 1;
}
