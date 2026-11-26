// The syscall layer behind GuestFs. Each function returns a negative errno on
// failure. guest_fs.c explains why each one exists.
#ifndef GUEST_FS_H
#define GUEST_FS_H

int gfs_open_root(const char *path);
int gfs_open_dir(int dir_fd, const char *name, int create);
int gfs_open_file(int dir_fd, const char *name);
long long gfs_size(int fd);
int gfs_is_symlink(int dir_fd, const char *name);
// Returns the bytes copied: fewer than `bytes` when the file shrank meanwhile.
long long gfs_copy_out(int fd, const char *destination, long long bytes);
int gfs_move_in(const char *source, int dir_fd, const char *name);
int gfs_remove_tree(int dir_fd, const char *name);

#endif
