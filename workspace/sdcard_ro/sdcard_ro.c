/*
 * libsdcard_ro.so — PRoot 沙盒内的 /sdcard 纯只读写保护层 (LD_PRELOAD)
 *
 * 设计目标：
 *   当会话开启本机存储挂载时，PRoot 仅做用户态路径重映射，本身不支持内核级只读挂载 (MS_RDONLY)。
 *   本库在 glibc 动态链接层拦截所有可能修改、创建、截断、重命名或删除文件的系统调用封装：
 *   凡是解析后的目标路径（含相对路径、dirfd、父目录软链接或最终软链接目标）落在
 *   `/sdcard` 或 `/storage/emulated/0` 下（含挂载点本身），一律拒绝并返回标准 `EROFS`
 *   (Read-only file system)，使容器内所有工具将其视为物理写保护卷。
 *
 *   AI 如需向手机导出产物文件，必须调用宿主侧受用户审批控制的 `workspace_export_file` 工具。
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>
#include <utime.h>

#ifndef AT_FDCWD
#define AT_FDCWD (-100)
#endif

#ifndef AT_SYMLINK_NOFOLLOW
#define AT_SYMLINK_NOFOLLOW 0x100
#endif

#ifndef __O_TMPFILE
#define __O_TMPFILE 020000000
#endif

/* 线程局部重入锁：防止 realpath / readlink 内部调用 open/lstat 时触发递归拦截 */
static __thread int g_in_hook = 0;

static int has_prefix_dir(const char *path, const char *prefix) {
    size_t len = strlen(prefix);
    if (strncmp(path, prefix, len) != 0) return 0;
    return path[len] == '\0' || path[len] == '/';
}

static int is_protected_sdcard_path(const char *resolved) {
    if (!resolved || resolved[0] != '/') return 0;
    if (has_prefix_dir(resolved, "/sdcard")) return 1;
    if (has_prefix_dir(resolved, "/storage/emulated/0")) return 1;

    /* 仅供自动化单测附加受保护前缀（只增不减，绝不能削弱 /sdcard 与 /storage/emulated/0 保护） */
    const char *extra = getenv("RIKKAHUB_RO_TEST_PREFIX");
    if (extra && extra[0] == '/' && extra[1] != '\0') {
        if (has_prefix_dir(resolved, extra)) return 1;
    }
    return 0;
}

/* 原地折叠词法层面的 "." 与 ".."，输入必须以 "/" 开头 */
static void lexical_normalize(const char *input, char *out, size_t out_sz) {
    const char *seg_starts[256];
    size_t seg_lens[256];
    int depth = 0;

    const char *p = input;
    while (*p != '\0') {
        while (*p == '/') p++;
        if (*p == '\0') break;
        const char *start = p;
        while (*p != '\0' && *p != '/') p++;
        size_t len = (size_t)(p - start);

        if (len == 1 && start[0] == '.') {
            continue;
        }
        if (len == 2 && start[0] == '.' && start[1] == '.') {
            if (depth > 0) depth--;
            continue;
        }
        if (depth < 256) {
            seg_starts[depth] = start;
            seg_lens[depth] = len;
            depth++;
        }
    }

    if (depth == 0) {
        if (out_sz >= 2) {
            out[0] = '/';
            out[1] = '\0';
        }
        return;
    }

    size_t pos = 0;
    for (int i = 0; i < depth; i++) {
        if (pos + 1 + seg_lens[i] >= out_sz) break;
        out[pos++] = '/';
        memcpy(out + pos, seg_starts[i], seg_lens[i]);
        pos += seg_lens[i];
    }
    out[pos] = '\0';
}

/*
 * 将 (dirfd, pathname) 解析为规范化绝对路径：
 * - follow_leaf = 1：用于 open/fopen/truncate/chmod 等会跟随末端软链接的操作；
 *   若目标文件已存在，直接对其执行 realpath 以穿透末端软链接（防 `ln -s /sdcard/a /tmp/x && > /tmp/x`）。
 * - follow_leaf = 0：用于 unlink/rmdir/rename/symlink 等操作末端自身（不跟随末端软链接）的调用；
 *   仅向上回溯解析最近已存在的祖先目录的 realpath，再拼回剩余相对分量并做词法归一化。
 */
static int resolve_target_path(
    int dirfd,
    const char *pathname,
    int follow_leaf,
    char *out,
    size_t out_sz
) {
    if (!pathname || pathname[0] == '\0') return -1;

    char combined[PATH_MAX * 2 + 2];
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

    char norm[PATH_MAX];
    lexical_normalize(combined, norm, sizeof(norm));

    if (follow_leaf) {
        char resolved_full[PATH_MAX];
        if (realpath(norm, resolved_full)) {
            snprintf(out, out_sz, "%s", resolved_full);
            return 0;
        }
    }

    /* 向上查找最深的存在祖先目录并对其做 realpath（穿透任意层级的父目录软链接，即使中间有多级尚不存在的新目录） */
    char prefix[PATH_MAX];
    snprintf(prefix, sizeof(prefix), "%s", norm);

    /* follow_leaf == 0 时必须至少剥离最后一级分量，避免把指向 /sdcard 文件的外部软链接误判为 /sdcard 内部路径 */
    char *split = strrchr(prefix, '/');
    if (!split) return -1;

    while (1) {
        const char *tail = norm + (split - prefix); /* 以 '/' 开头 */
        char candidate_parent[PATH_MAX];
        if (split == prefix) {
            strcpy(candidate_parent, "/");
        } else {
            size_t plen = (size_t)(split - prefix);
            if (plen >= sizeof(candidate_parent)) plen = sizeof(candidate_parent) - 1;
            memcpy(candidate_parent, prefix, plen);
            candidate_parent[plen] = '\0';
        }

        char real_anc[PATH_MAX];
        if (realpath(candidate_parent, real_anc)) {
            char merged[PATH_MAX];
            if (strcmp(real_anc, "/") == 0) {
                snprintf(merged, sizeof(merged), "%s", tail);
            } else {
                snprintf(merged, sizeof(merged), "%s%s", real_anc, tail);
            }
            lexical_normalize(merged, out, out_sz);
            return 0;
        }

        if (split == prefix) {
            snprintf(out, out_sz, "%s", norm);
            return 0;
        }
        *split = '\0';
        split = strrchr(prefix, '/');
        if (!split) {
            snprintf(out, out_sz, "%s", norm);
            return 0;
        }
    }
}

static int check_ro_at(int dirfd, const char *pathname, int follow_leaf) {
    if (g_in_hook || !pathname) return 0;
    g_in_hook = 1;
    char resolved[PATH_MAX];
    int blocked = 0;
    if (resolve_target_path(dirfd, pathname, follow_leaf, resolved, sizeof(resolved)) == 0) {
        blocked = is_protected_sdcard_path(resolved);
    }
    g_in_hook = 0;
    return blocked;
}

static int has_tmpfile_flag(int flags) {
    return (flags & __O_TMPFILE) == __O_TMPFILE;
}

static int is_write_open_flags(int flags) {
    if ((flags & O_ACCMODE) != O_RDONLY) return 1;
    if (flags & (O_CREAT | O_TRUNC | O_APPEND)) return 1;
    if (has_tmpfile_flag(flags)) return 1;
    return 0;
}

static int is_write_fopen_mode(const char *mode) {
    if (!mode) return 0;
    for (const char *p = mode; *p != '\0'; p++) {
        if (*p == 'w' || *p == 'a' || *p == '+' || *p == 'x') return 1;
    }
    return 0;
}

/* ==================== open / creat / fopen 系列 ==================== */

typedef int (*open_fn_t)(const char *, int, ...);
typedef int (*open2_fn_t)(const char *, int);
typedef int (*openat_fn_t)(int, const char *, int, ...);
typedef int (*openat2_fn_t)(int, const char *, int);
typedef int (*creat_fn_t)(const char *, mode_t);
typedef FILE *(*fopen_fn_t)(const char *, const char *);
typedef FILE *(*freopen_fn_t)(const char *, const char *, FILE *);

int open(const char *pathname, int flags, ...) {
    static open_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (open_fn_t)dlsym(RTLD_NEXT, "open");

    mode_t mode = 0;
    if ((flags & O_CREAT) || has_tmpfile_flag(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    if (is_write_open_flags(flags)) {
        int follow = (flags & O_NOFOLLOW) ? 0 : 1;
        if (check_ro_at(AT_FDCWD, pathname, follow)) {
            errno = EROFS;
            return -1;
        }
    }
    return real_fn(pathname, flags, mode);
}

int open64(const char *pathname, int flags, ...) {
    static open_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (open_fn_t)dlsym(RTLD_NEXT, "open64");

    mode_t mode = 0;
    if ((flags & O_CREAT) || has_tmpfile_flag(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    if (is_write_open_flags(flags)) {
        int follow = (flags & O_NOFOLLOW) ? 0 : 1;
        if (check_ro_at(AT_FDCWD, pathname, follow)) {
            errno = EROFS;
            return -1;
        }
    }
    return real_fn(pathname, flags, mode);
}

int __open_2(const char *pathname, int flags) {
    static open2_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (open2_fn_t)dlsym(RTLD_NEXT, "__open_2");
    if (is_write_open_flags(flags)) {
        int follow = (flags & O_NOFOLLOW) ? 0 : 1;
        if (check_ro_at(AT_FDCWD, pathname, follow)) {
            errno = EROFS;
            return -1;
        }
    }
    return real_fn(pathname, flags);
}

int __open64_2(const char *pathname, int flags) {
    static open2_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (open2_fn_t)dlsym(RTLD_NEXT, "__open64_2");
    if (is_write_open_flags(flags)) {
        int follow = (flags & O_NOFOLLOW) ? 0 : 1;
        if (check_ro_at(AT_FDCWD, pathname, follow)) {
            errno = EROFS;
            return -1;
        }
    }
    return real_fn(pathname, flags);
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    static openat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (openat_fn_t)dlsym(RTLD_NEXT, "openat");

    mode_t mode = 0;
    if ((flags & O_CREAT) || has_tmpfile_flag(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    if (is_write_open_flags(flags)) {
        int follow = (flags & O_NOFOLLOW) ? 0 : 1;
        if (check_ro_at(dirfd, pathname, follow)) {
            errno = EROFS;
            return -1;
        }
    }
    return real_fn(dirfd, pathname, flags, mode);
}

int openat64(int dirfd, const char *pathname, int flags, ...) {
    static openat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (openat_fn_t)dlsym(RTLD_NEXT, "openat64");

    mode_t mode = 0;
    if ((flags & O_CREAT) || has_tmpfile_flag(flags)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    if (is_write_open_flags(flags)) {
        int follow = (flags & O_NOFOLLOW) ? 0 : 1;
        if (check_ro_at(dirfd, pathname, follow)) {
            errno = EROFS;
            return -1;
        }
    }
    return real_fn(dirfd, pathname, flags, mode);
}

int __openat_2(int dirfd, const char *pathname, int flags) {
    static openat2_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (openat2_fn_t)dlsym(RTLD_NEXT, "__openat_2");
    if (is_write_open_flags(flags)) {
        int follow = (flags & O_NOFOLLOW) ? 0 : 1;
        if (check_ro_at(dirfd, pathname, follow)) {
            errno = EROFS;
            return -1;
        }
    }
    return real_fn(dirfd, pathname, flags);
}

int __openat64_2(int dirfd, const char *pathname, int flags) {
    static openat2_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (openat2_fn_t)dlsym(RTLD_NEXT, "__openat64_2");
    if (is_write_open_flags(flags)) {
        int follow = (flags & O_NOFOLLOW) ? 0 : 1;
        if (check_ro_at(dirfd, pathname, follow)) {
            errno = EROFS;
            return -1;
        }
    }
    return real_fn(dirfd, pathname, flags);
}

int creat(const char *pathname, mode_t mode) {
    static creat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (creat_fn_t)dlsym(RTLD_NEXT, "creat");
    if (check_ro_at(AT_FDCWD, pathname, 1)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname, mode);
}

int creat64(const char *pathname, mode_t mode) {
    static creat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (creat_fn_t)dlsym(RTLD_NEXT, "creat64");
    if (check_ro_at(AT_FDCWD, pathname, 1)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname, mode);
}

FILE *fopen(const char *pathname, const char *mode) {
    static fopen_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (fopen_fn_t)dlsym(RTLD_NEXT, "fopen");
    if (is_write_fopen_mode(mode) && check_ro_at(AT_FDCWD, pathname, 1)) {
        errno = EROFS;
        return NULL;
    }
    return real_fn(pathname, mode);
}

FILE *fopen64(const char *pathname, const char *mode) {
    static fopen_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (fopen_fn_t)dlsym(RTLD_NEXT, "fopen64");
    if (is_write_fopen_mode(mode) && check_ro_at(AT_FDCWD, pathname, 1)) {
        errno = EROFS;
        return NULL;
    }
    return real_fn(pathname, mode);
}

static int check_ro_fd(int fd) {
    if (g_in_hook || fd < 0) return 0;
    g_in_hook = 1;
    char fd_link[64];
    char target[PATH_MAX];
    int blocked = 0;
    snprintf(fd_link, sizeof(fd_link), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(fd_link, target, sizeof(target) - 1);
    if (n > 0) {
        target[n] = '\0';
        blocked = is_protected_sdcard_path(target);
    }
    g_in_hook = 0;
    return blocked;
}

/* 绕过 glibc 头文件中 __attribute__((nonnull)) 在 -O2 下消除防御性 NULL 检查与告警 */
static int ptr_present(const void *volatile p) {
    return (uintptr_t)p != 0;
}

FILE *freopen(const char *pathname, const char *mode, FILE *stream) {
    static freopen_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (freopen_fn_t)dlsym(RTLD_NEXT, "freopen");
    if (is_write_fopen_mode(mode)) {
        int blocked = ptr_present(pathname)
            ? check_ro_at(AT_FDCWD, pathname, 1)
            : (ptr_present(stream) ? check_ro_fd(fileno(stream)) : 0);
        if (blocked) {
            errno = EROFS;
            return NULL;
        }
    }
    return real_fn(pathname, mode, stream);
}

FILE *freopen64(const char *pathname, const char *mode, FILE *stream) {
    static freopen_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (freopen_fn_t)dlsym(RTLD_NEXT, "freopen64");
    if (is_write_fopen_mode(mode)) {
        int blocked = ptr_present(pathname)
            ? check_ro_at(AT_FDCWD, pathname, 1)
            : (ptr_present(stream) ? check_ro_fd(fileno(stream)) : 0);
        if (blocked) {
            errno = EROFS;
            return NULL;
        }
    }
    return real_fn(pathname, mode, stream);
}

/* ==================== truncate / unlink / rmdir / remove ==================== */

typedef int (*truncate_fn_t)(const char *, off_t);
typedef int (*path_fn_t)(const char *);
typedef int (*unlinkat_fn_t)(int, const char *, int);

int truncate(const char *path, off_t length) {
    static truncate_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (truncate_fn_t)dlsym(RTLD_NEXT, "truncate");
    if (check_ro_at(AT_FDCWD, path, 1)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(path, length);
}

int truncate64(const char *path, off_t length) {
    static truncate_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (truncate_fn_t)dlsym(RTLD_NEXT, "truncate64");
    if (check_ro_at(AT_FDCWD, path, 1)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(path, length);
}

int unlink(const char *pathname) {
    static path_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (path_fn_t)dlsym(RTLD_NEXT, "unlink");
    if (check_ro_at(AT_FDCWD, pathname, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname);
}

int unlinkat(int dirfd, const char *pathname, int flags) {
    static unlinkat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (unlinkat_fn_t)dlsym(RTLD_NEXT, "unlinkat");
    if (check_ro_at(dirfd, pathname, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(dirfd, pathname, flags);
}

int rmdir(const char *pathname) {
    static path_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (path_fn_t)dlsym(RTLD_NEXT, "rmdir");
    if (check_ro_at(AT_FDCWD, pathname, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname);
}

int remove(const char *pathname) {
    static path_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (path_fn_t)dlsym(RTLD_NEXT, "remove");
    if (check_ro_at(AT_FDCWD, pathname, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname);
}

/* ==================== rename 系列（源或目标任一命中即拦截） ==================== */

typedef int (*rename_fn_t)(const char *, const char *);
typedef int (*renameat_fn_t)(int, const char *, int, const char *);
typedef int (*renameat2_fn_t)(int, const char *, int, const char *, unsigned int);

int rename(const char *oldpath, const char *newpath) {
    static rename_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (rename_fn_t)dlsym(RTLD_NEXT, "rename");
    if (check_ro_at(AT_FDCWD, oldpath, 0) || check_ro_at(AT_FDCWD, newpath, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(oldpath, newpath);
}

int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    static renameat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (renameat_fn_t)dlsym(RTLD_NEXT, "renameat");
    if (check_ro_at(olddirfd, oldpath, 0) || check_ro_at(newdirfd, newpath, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(olddirfd, oldpath, newdirfd, newpath);
}

int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned int flags) {
    static renameat2_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (renameat2_fn_t)dlsym(RTLD_NEXT, "renameat2");
    if (check_ro_at(olddirfd, oldpath, 0) || check_ro_at(newdirfd, newpath, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(olddirfd, oldpath, newdirfd, newpath, flags);
}

/* ==================== mkdir / symlink / link / chmod / chown ==================== */

typedef int (*mkdir_fn_t)(const char *, mode_t);
typedef int (*mkdirat_fn_t)(int, const char *, mode_t);
typedef int (*symlink_fn_t)(const char *, const char *);
typedef int (*symlinkat_fn_t)(const char *, int, const char *);
typedef int (*link_fn_t)(const char *, const char *);
typedef int (*linkat_fn_t)(int, const char *, int, const char *, int);
typedef int (*chmod_fn_t)(const char *, mode_t);
typedef int (*fchmodat_fn_t)(int, const char *, mode_t, int);
typedef int (*chown_fn_t)(const char *, uid_t, gid_t);
typedef int (*fchownat_fn_t)(int, const char *, uid_t, gid_t, int);

int mkdir(const char *pathname, mode_t mode) {
    static mkdir_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (mkdir_fn_t)dlsym(RTLD_NEXT, "mkdir");
    if (check_ro_at(AT_FDCWD, pathname, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname, mode);
}

int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    static mkdirat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (mkdirat_fn_t)dlsym(RTLD_NEXT, "mkdirat");
    if (check_ro_at(dirfd, pathname, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(dirfd, pathname, mode);
}

int symlink(const char *target, const char *linkpath) {
    static symlink_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (symlink_fn_t)dlsym(RTLD_NEXT, "symlink");
    if (check_ro_at(AT_FDCWD, linkpath, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(target, linkpath);
}

int symlinkat(const char *target, int newdirfd, const char *linkpath) {
    static symlinkat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (symlinkat_fn_t)dlsym(RTLD_NEXT, "symlinkat");
    if (check_ro_at(newdirfd, linkpath, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(target, newdirfd, linkpath);
}

int link(const char *oldpath, const char *newpath) {
    static link_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (link_fn_t)dlsym(RTLD_NEXT, "link");
    if (check_ro_at(AT_FDCWD, oldpath, 0) || check_ro_at(AT_FDCWD, newpath, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(oldpath, newpath);
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    static linkat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (linkat_fn_t)dlsym(RTLD_NEXT, "linkat");
    if (check_ro_at(olddirfd, oldpath, 0) || check_ro_at(newdirfd, newpath, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(olddirfd, oldpath, newdirfd, newpath, flags);
}

int chmod(const char *pathname, mode_t mode) {
    static chmod_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (chmod_fn_t)dlsym(RTLD_NEXT, "chmod");
    if (check_ro_at(AT_FDCWD, pathname, 1)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname, mode);
}

int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    static fchmodat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (fchmodat_fn_t)dlsym(RTLD_NEXT, "fchmodat");
    int follow = (flags & AT_SYMLINK_NOFOLLOW) ? 0 : 1;
    if (check_ro_at(dirfd, pathname, follow)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(dirfd, pathname, mode, flags);
}

int chown(const char *pathname, uid_t owner, gid_t group) {
    static chown_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (chown_fn_t)dlsym(RTLD_NEXT, "chown");
    if (check_ro_at(AT_FDCWD, pathname, 1)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname, owner, group);
}

int lchown(const char *pathname, uid_t owner, gid_t group) {
    static chown_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (chown_fn_t)dlsym(RTLD_NEXT, "lchown");
    if (check_ro_at(AT_FDCWD, pathname, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(pathname, owner, group);
}

int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    static fchownat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (fchownat_fn_t)dlsym(RTLD_NEXT, "fchownat");
    int follow = (flags & AT_SYMLINK_NOFOLLOW) ? 0 : 1;
    if (check_ro_at(dirfd, pathname, follow)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(dirfd, pathname, owner, group, flags);
}

/* ==================== utimensat / utimes / lutimes / utime ==================== */

typedef int (*utimensat_fn_t)(int, const char *, const struct timespec[2], int);
typedef int (*utimes_fn_t)(const char *, const struct timeval[2]);
typedef int (*utime_fn_t)(const char *, const struct utimbuf *);

int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags) {
    static utimensat_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (utimensat_fn_t)dlsym(RTLD_NEXT, "utimensat");
    int blocked = ptr_present(pathname)
        ? check_ro_at(dirfd, pathname, (flags & AT_SYMLINK_NOFOLLOW) ? 0 : 1)
        : check_ro_fd(dirfd);
    if (blocked) {
        errno = EROFS;
        return -1;
    }
    return real_fn(dirfd, pathname, times, flags);
}

int utimes(const char *filename, const struct timeval times[2]) {
    static utimes_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (utimes_fn_t)dlsym(RTLD_NEXT, "utimes");
    if (check_ro_at(AT_FDCWD, filename, 1)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(filename, times);
}

int lutimes(const char *filename, const struct timeval tv[2]) {
    static utimes_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (utimes_fn_t)dlsym(RTLD_NEXT, "lutimes");
    if (check_ro_at(AT_FDCWD, filename, 0)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(filename, tv);
}

int utime(const char *filename, const struct utimbuf *times) {
    static utime_fn_t real_fn = NULL;
    if (!real_fn) real_fn = (utime_fn_t)dlsym(RTLD_NEXT, "utime");
    if (check_ro_at(AT_FDCWD, filename, 1)) {
        errno = EROFS;
        return -1;
    }
    return real_fn(filename, times);
}
