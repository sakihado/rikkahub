#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1 << 0)
#endif

/*
 * libsafesdcard.so — PRoot 沙盒内的 /sdcard 防误删保护层 (LD_PRELOAD)
 *
 * 拦截 unlink / unlinkat / rmdir / remove：
 * - /workspace、/tmp 等沙盒目录原样透传；
 * - /sdcard (/storage/emulated/0) 下的文件/空目录禁止直接物理抹除，自动以无覆盖唯一名移入 /sdcard/.Trash-RikkaHub/；
 * - 严格保留 POSIX 错误码语义（EISDIR / ENOTDIR / ENOTEMPTY / ENOENT）；
 * - 禁止删除 /sdcard/.Trash-RikkaHub 内的回收站文件 (EPERM)。
 */

#define TRASH_DIR "/sdcard/.Trash-RikkaHub"

typedef int (*unlink_fn)(const char *);
typedef int (*unlinkat_fn)(int, const char *, int);
typedef int (*rmdir_fn)(const char *);
typedef int (*remove_fn)(const char *);

static atomic_ulong g_trash_seq = 0;

static int is_sdcard_path(const char *resolved) {
    if (!resolved) return 0;
    if (strncmp(resolved, "/sdcard/", 8) == 0 || strcmp(resolved, "/sdcard") == 0) return 1;
    if (strncmp(resolved, "/storage/emulated/0/", 20) == 0 || strcmp(resolved, "/storage/emulated/0") == 0) return 1;
    return 0;
}

static int is_inside_trash(const char *resolved) {
    if (!resolved) return 0;
    if (strncmp(resolved, TRASH_DIR "/", strlen(TRASH_DIR) + 1) == 0 ||
        strcmp(resolved, TRASH_DIR) == 0) {
        return 1;
    }
    if (strncmp(resolved, "/storage/emulated/0/.Trash-RikkaHub/", 36) == 0 ||
        strcmp(resolved, "/storage/emulated/0/.Trash-RikkaHub") == 0) {
        return 1;
    }
    return 0;
}

static int is_dir_non_empty(const char *path) {
    DIR *dir = opendir(path);
    if (!dir) return 0;
    struct dirent *ent;
    int has_entry = 0;
    while ((ent = readdir(dir)) != NULL) {
        if (strcmp(ent->d_name, ".") != 0 && strcmp(ent->d_name, "..") != 0) {
            has_entry = 1;
            break;
        }
    }
    closedir(dir);
    return has_entry;
}

static int resolve_at_path(int dirfd, const char *pathname, char *out, size_t out_sz) {
    if (!pathname || pathname[0] == '\0') return -1;

    char combined[PATH_MAX];
    if (pathname[0] == '/') {
        snprintf(combined, sizeof(combined), "%s", pathname);
    } else if (dirfd == AT_FDCWD) {
        char cwd[PATH_MAX];
        if (!getcwd(cwd, sizeof(cwd))) return -1;
        snprintf(combined, sizeof(combined), "%s/%s", cwd, pathname);
    } else {
        char fd_link[64];
        char base[PATH_MAX];
        snprintf(fd_link, sizeof(fd_link), "/proc/self/fd/%d", dirfd);
        ssize_t n = readlink(fd_link, base, sizeof(base) - 1);
        if (n <= 0) return -1;
        base[n] = '\0';
        snprintf(combined, sizeof(combined), "%s/%s", base, pathname);
    }

    char copy[PATH_MAX];
    snprintf(copy, sizeof(copy), "%s", combined);
    size_t len = strlen(copy);
    while (len > 1 && copy[len - 1] == '/') {
        copy[--len] = '\0';
    }

    char *slash = strrchr(copy, '/');
    if (!slash) return -1;

    const char *leaf = slash + 1;
    char parent[PATH_MAX];
    if (slash == copy) {
        strcpy(parent, "/");
    } else {
        *slash = '\0';
        snprintf(parent, sizeof(parent), "%s", copy);
    }

    char real_parent[PATH_MAX];
    if (!realpath(parent, real_parent)) {
        snprintf(real_parent, sizeof(real_parent), "%s", parent);
    }

    if (strcmp(real_parent, "/") == 0) {
        snprintf(out, out_sz, "/%s", leaf);
    } else {
        snprintf(out, out_sz, "%s/%s", real_parent, leaf);
    }
    return 0;
}

/* mode: 0 = unlink (files/symlinks only), 1 = rmdir (empty dirs only), 2 = remove (file or empty dir) */
static int move_to_trash(const char *resolved_path, int mode) {
    if (strcmp(resolved_path, "/sdcard") == 0 ||
        strcmp(resolved_path, "/storage/emulated/0") == 0 ||
        is_inside_trash(resolved_path)) {
        errno = EPERM;
        return -1;
    }

    struct stat st;
    if (lstat(resolved_path, &st) != 0) {
        return -1;
    }

    if (mode == 0 && S_ISDIR(st.st_mode)) {
        errno = EISDIR;
        return -1;
    }
    if (mode == 1 && !S_ISDIR(st.st_mode)) {
        errno = ENOTDIR;
        return -1;
    }
    if (S_ISDIR(st.st_mode) && is_dir_non_empty(resolved_path)) {
        errno = ENOTEMPTY;
        return -1;
    }

    mkdir(TRASH_DIR, 0775);

    const char *base = strrchr(resolved_path, '/');
    base = base ? (base + 1) : resolved_path;

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    pid_t pid = getpid();

    for (int attempt = 0; attempt < 64; attempt++) {
        unsigned long seq = atomic_fetch_add(&g_trash_seq, 1UL) + 1UL;
        char dest[PATH_MAX];
        snprintf(
            dest,
            sizeof(dest),
            "%s/%lld_%09ld_%d_%lu_%s",
            TRASH_DIR,
            (long long)ts.tv_sec,
            ts.tv_nsec,
            (int)pid,
            seq,
            base
        );

#ifdef SYS_renameat2
        if (syscall(SYS_renameat2, AT_FDCWD, resolved_path, AT_FDCWD, dest, RENAME_NOREPLACE) == 0) {
            return 0;
        }
        if (errno == EEXIST) {
            continue;
        }
#endif
        struct stat dst_st;
        if (lstat(dest, &dst_st) == 0) {
            continue;
        }
        if (rename(resolved_path, dest) == 0) {
            return 0;
        }
        break;
    }
    errno = EPERM;
    return -1;
}

__attribute__((visibility("default")))
int unlink(const char *pathname) {
    static unlink_fn real_unlink = NULL;
    if (!real_unlink) {
        real_unlink = (unlink_fn)dlsym(RTLD_NEXT, "unlink");
    }
    char resolved[PATH_MAX];
    if (resolve_at_path(AT_FDCWD, pathname, resolved, sizeof(resolved)) == 0 &&
        is_sdcard_path(resolved)) {
        return move_to_trash(resolved, 0);
    }
    return real_unlink ? real_unlink(pathname) : -1;
}

__attribute__((visibility("default")))
int unlinkat(int dirfd, const char *pathname, int flags) {
    static unlinkat_fn real_unlinkat = NULL;
    if (!real_unlinkat) {
        real_unlinkat = (unlinkat_fn)dlsym(RTLD_NEXT, "unlinkat");
    }
    char resolved[PATH_MAX];
    if (resolve_at_path(dirfd, pathname, resolved, sizeof(resolved)) == 0 &&
        is_sdcard_path(resolved)) {
        int mode = (flags & AT_REMOVEDIR) ? 1 : 0;
        return move_to_trash(resolved, mode);
    }
    return real_unlinkat ? real_unlinkat(dirfd, pathname, flags) : -1;
}

__attribute__((visibility("default")))
int rmdir(const char *pathname) {
    static rmdir_fn real_rmdir = NULL;
    if (!real_rmdir) {
        real_rmdir = (rmdir_fn)dlsym(RTLD_NEXT, "rmdir");
    }
    char resolved[PATH_MAX];
    if (resolve_at_path(AT_FDCWD, pathname, resolved, sizeof(resolved)) == 0 &&
        is_sdcard_path(resolved)) {
        return move_to_trash(resolved, 1);
    }
    return real_rmdir ? real_rmdir(pathname) : -1;
}

__attribute__((visibility("default")))
int remove(const char *pathname) {
    static remove_fn real_remove = NULL;
    if (!real_remove) {
        real_remove = (remove_fn)dlsym(RTLD_NEXT, "remove");
    }
    char resolved[PATH_MAX];
    if (resolve_at_path(AT_FDCWD, pathname, resolved, sizeof(resolved)) == 0 &&
        is_sdcard_path(resolved)) {
        return move_to_trash(resolved, 2);
    }
    return real_remove ? real_remove(pathname) : -1;
}
