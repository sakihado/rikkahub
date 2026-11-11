#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define AID_SHELL 2000

/*
 * libshelldrop.so — Native privilege-drop & process-group supervisor for RikkaHub
 *
 * Responsibilities:
 * 1. Privilege contract (--uid-shell, default):
 *    Drops UID 0 (e.g. Zygisk-Sui or su) cleanly to Android's standard UID 2000 (shell)
 *    + supplementary groups + best-effort u:r:shell:s0 SELinux transition, and strictly
 *    verifies effective UID/GID == 2000 (fail-closed exit 126 if unable).
 *    Pass --keep-uid when UID 0 (root=true) or current UID is explicitly desired.
 * 2. Process-group lifecycle & leak-free cancellation (--lifeline-stdin):
 *    Places /system/bin/sh and all its descendants into an isolated process group
 *    (PGID == child_pid), redirects the child's stdin to /dev/null, and monitors the
 *    caller's stdin pipe as a lifeline. When RikkaHub times out, cancels the coroutine,
 *    or terminates, EOF on the lifeline pipe (or SIGTERM/SIGINT/SIGHUP) triggers an
 *    immediate `kill(-child_pid, SIGKILL)` from the supervisor before reaping, ensuring
 *    zero orphan/grandchild processes survive timeout or stop.
 */

static int g_sig_pipe[2] = {-1, -1};
static volatile sig_atomic_t g_abort_signal = 0;

static void on_signal(int sig) {
    int saved_errno = errno;
    if (sig != SIGCHLD) {
        g_abort_signal = sig;
    }
    if (g_sig_pipe[1] >= 0) {
        const char byte = 1;
        ssize_t unused = write(g_sig_pipe[1], &byte, 1);
        (void)unused;
    }
    errno = saved_errno;
}

static int apply_shell_uid2000_or_fail(void) {
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
            ssize_t unused = write(fd, ctx, strlen(ctx));
            (void)unused;
            close(fd);
        }

        if (setresgid(AID_SHELL, AID_SHELL, AID_SHELL) != 0 ||
            setresuid(AID_SHELL, AID_SHELL, AID_SHELL) != 0) {
            fprintf(stderr, "shelldrop: failed to drop privileges to uid 2000\n");
            return -1;
        }
    }

    if (getuid() != AID_SHELL || geteuid() != AID_SHELL ||
        getgid() != AID_SHELL || getegid() != AID_SHELL) {
        fprintf(
            stderr,
            "shelldrop: fail-closed: unexpected uid=%d euid=%d gid=%d egid=%d (expected 2000)\n",
            (int)getuid(),
            (int)geteuid(),
            (int)getgid(),
            (int)getegid()
        );
        return -1;
    }
    return 0;
}

int main(int argc, char *argv[]) {
    int drop_to_shell = 1;
    int use_lifeline = 0;
    int arg_idx = 1;

    while (arg_idx < argc) {
        if (strcmp(argv[arg_idx], "--uid-shell") == 0) {
            drop_to_shell = 1;
            arg_idx++;
        } else if (strcmp(argv[arg_idx], "--keep-uid") == 0) {
            drop_to_shell = 0;
            arg_idx++;
        } else if (strcmp(argv[arg_idx], "--lifeline-stdin") == 0) {
            use_lifeline = 1;
            arg_idx++;
        } else if (strcmp(argv[arg_idx], "--") == 0) {
            arg_idx++;
            break;
        } else {
            break;
        }
    }

    int shell_argc = argc - arg_idx;
    char **sh_argv = calloc((size_t)shell_argc + 2, sizeof(char *));
    if (!sh_argv) {
        return 126;
    }
    sh_argv[0] = "/system/bin/sh";
    for (int i = 0; i < shell_argc; i++) {
        sh_argv[i + 1] = argv[arg_idx + i];
    }
    sh_argv[shell_argc + 1] = NULL;

    if (pipe2(g_sig_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        perror("shelldrop: pipe2");
        free(sh_argv);
        return 126;
    }

    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    sigaction(SIGCHLD, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);

    int lifeline_fd = -1;
    if (use_lifeline) {
        lifeline_fd = fcntl(STDIN_FILENO, F_DUPFD_CLOEXEC, 3);
        if (lifeline_fd >= 0) {
            int fl = fcntl(lifeline_fd, F_GETFL, 0);
            if (fl >= 0) {
                (void)fcntl(lifeline_fd, F_SETFL, fl | O_NONBLOCK);
            }
        }
    }

    int devnull = open("/dev/null", O_RDONLY);
    if (devnull >= 0) {
        if (devnull != STDIN_FILENO) {
            (void)dup2(devnull, STDIN_FILENO);
            close(devnull);
        }
    }

    pid_t child = fork();
    if (child < 0) {
        perror("shelldrop: fork");
        if (lifeline_fd >= 0) close(lifeline_fd);
        close(g_sig_pipe[0]);
        close(g_sig_pipe[1]);
        free(sh_argv);
        return 126;
    }

    if (child == 0) {
        /* Child: restore default signal handlers and establish isolated process group */
        signal(SIGCHLD, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);

        (void)setpgid(0, 0);

        if (drop_to_shell && apply_shell_uid2000_or_fail() != 0) {
            _exit(126);
        }

        execv("/system/bin/sh", sh_argv);
        perror("shelldrop: execv /system/bin/sh");
        _exit(127);
    }

    /* Parent supervisor: ensure child PGID is set immediately to prevent race */
    (void)setpgid(child, child);
    free(sh_argv);

    int aborted = 0;
    for (;;) {
        if (g_abort_signal != 0 && !aborted) {
            aborted = 1;
            (void)kill(-child, SIGKILL);
            (void)kill(child, SIGKILL);
        }

        int status = 0;
        pid_t w = waitpid(child, &status, WNOHANG);
        if (w == child) {
            /* Ensure any lingering background subshell in the group is also cleaned up on abort */
            if (aborted) {
                (void)kill(-child, SIGKILL);
                if (lifeline_fd >= 0) close(lifeline_fd);
                close(g_sig_pipe[0]);
                close(g_sig_pipe[1]);
                return 124;
            }
            if (lifeline_fd >= 0) close(lifeline_fd);
            close(g_sig_pipe[0]);
            close(g_sig_pipe[1]);
            if (WIFEXITED(status)) {
                return WEXITSTATUS(status);
            }
            if (WIFSIGNALED(status)) {
                return 128 + WTERMSIG(status);
            }
            return 1;
        } else if (w < 0 && errno != EINTR) {
            break;
        }

        struct pollfd pfds[2];
        nfds_t nfds = 0;

        pfds[nfds].fd = g_sig_pipe[0];
        pfds[nfds].events = POLLIN;
        pfds[nfds].revents = 0;
        nfds++;

        if (lifeline_fd >= 0) {
            pfds[nfds].fd = lifeline_fd;
            pfds[nfds].events = POLLIN;
            pfds[nfds].revents = 0;
            nfds++;
        }

        int pr = poll(pfds, nfds, 1000);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (pfds[0].revents & POLLIN) {
            char drain[64];
            while (read(g_sig_pipe[0], drain, sizeof(drain)) > 0) {
                /* drain signal notification bytes */
            }
        }

        if (lifeline_fd >= 0 && nfds > 1 &&
            (pfds[1].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL))) {
            char buf[64];
            ssize_t n = read(lifeline_fd, buf, sizeof(buf));
            if (n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
                close(lifeline_fd);
                lifeline_fd = -1;
                if (!aborted) {
                    aborted = 1;
                    (void)kill(-child, SIGKILL);
                    (void)kill(child, SIGKILL);
                }
            }
        }
    }

    (void)kill(-child, SIGKILL);
    (void)waitpid(child, NULL, 0);
    if (lifeline_fd >= 0) close(lifeline_fd);
    close(g_sig_pipe[0]);
    close(g_sig_pipe[1]);
    return aborted ? 124 : 1;
}
