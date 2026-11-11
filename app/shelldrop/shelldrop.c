#define _GNU_SOURCE
#include <fcntl.h>
#include <grp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#define AID_SHELL 2000

/*
 * Drops UID 0 (e.g. Zygisk-Sui daemon child) cleanly to Android's standard
 * UID 2000 (shell) + supplementary groups + u:r:shell:s0 SELinux context
 * before exec'ing /system/bin/sh, without invoking Magisk `su`.
 */
int main(int argc, char *argv[]) {
    static const gid_t shell_groups[] = {
        2000, /* shell */
        1004, /* input */
        1007, /* log */
        1011, /* adb */
        1015, /* sdcard_rw */
        1028, /* sdcard_r */
        1078, /* ext_data_rw */
        1079, /* ext_obb_rw */
        3001, /* net_bt_admin */
        3002, /* net_bt */
        3003, /* inet */
        3006, /* net_bw_stats */
        3009, /* readproc */
        3011, /* uhid */
        3012  /* readtracefs */
    };

    if (getuid() == 0) {
        (void)setgroups(sizeof(shell_groups) / sizeof(shell_groups[0]), shell_groups);

        /* Best-effort SELinux transition to u:r:shell:s0 upon execve(/system/bin/sh) */
        int fd = open("/proc/self/attr/exec", O_WRONLY | O_CLOEXEC);
        if (fd >= 0) {
            const char *ctx = "u:r:shell:s0";
            (void)write(fd, ctx, strlen(ctx));
            close(fd);
        }

        if (setresgid(AID_SHELL, AID_SHELL, AID_SHELL) != 0 ||
            setresuid(AID_SHELL, AID_SHELL, AID_SHELL) != 0) {
            fprintf(stderr, "shelldrop: failed to drop privileges to uid 2000\n");
            return 126;
        }
    }

    if (getuid() != AID_SHELL || geteuid() != AID_SHELL) {
        fprintf(stderr, "shelldrop: unexpected effective uid %d\n", (int)geteuid());
        return 126;
    }

    char **sh_argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!sh_argv) {
        return 126;
    }
    sh_argv[0] = "/system/bin/sh";
    for (int i = 1; i < argc; i++) {
        sh_argv[i] = argv[i];
    }
    sh_argv[argc] = NULL;

    execv("/system/bin/sh", sh_argv);
    perror("shelldrop: execv /system/bin/sh");
    return 127;
}
