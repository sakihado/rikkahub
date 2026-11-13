package me.rerere.workspace

import java.io.File
import java.util.UUID

/**
 * 本地 PRoot 绑定挂载点的读写访问策略。
 */
enum class MountAccess {
    /** 容器内可见但严格禁止任何修改（AI 文件工具硬拒绝 + PRoot LD_PRELOAD 层返回 EROFS） */
    READ_ONLY,

    /** 容器内可读写，AI 文件工具写入时需经用户弹窗审批 */
    WRITABLE_ASK,

    /** 容器内可读写，AI 文件工具可直接免审批写入（如 `/skills`） */
    WRITABLE_AUTO,
}

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    /** 该挂载点在容器与工具层的读写策略，默认严格只读 */
    val access: MountAccess = MountAccess.READ_ONLY,
    /** 是否属于会话级宿主机只读透视挂载（如 `/sdcard`、`/storage/emulated/0`、`/run/host_bridge`），仅在当前会话显式开启时暴露 */
    val sessionHostMount: Boolean = false,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        unavailableReason(context)?.let { reason ->
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = reason,
            )
        }
        val pidFile = File(context.linuxDir, "${EXEC_DIR.trimStart('/')}/${UUID.randomUUID()}.pid")
        pidFile.parentFile?.mkdirs()
        val process = processBuilder(context, buildCommand(context, TRACKED_SCRIPT, "$EXEC_DIR/${pidFile.name}"))
            .redirectErrorStream(false)
            .start()
        return try {
            // 超时或取消时直接结束, 不留宽限期: 调用方已经不再等这条命令
            process.readResult(context.timeoutMillis, context.stdin) { terminateProot(this, pidFile) }
        } finally {
            pidFile.delete()
        }
    }

    override fun startBackground(context: WorkspaceShellContext, id: String): WorkspaceBackgroundCommand {
        unavailableReason(context)?.let { error(it) }
        // 日志放在 Rootfs 的 /tmp 下, 模型可以用 shell 直接 tail/grep; 应用启动时随 /tmp 一起清理
        val dir = File(context.linuxDir, BACKGROUND_DIR.trimStart('/')).apply { mkdirs() }
        val logFile = File(dir, "$id.log")
        val pidFile = File(dir, "$id.pid")
        val process = processBuilder(context, buildCommand(context, TRACKED_SCRIPT, "$BACKGROUND_DIR/$id.pid"))
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .redirectInput(File("/dev/null"))
            .start()
        return ProotBackgroundCommand(process, logFile, "$BACKGROUND_DIR/$id.log", pidFile)
    }

    private fun unavailableReason(context: WorkspaceShellContext): String? = when {
        !WorkspaceManager.isUsableRootfs(context.linuxDir) -> "Rootfs is not installed"
        !File(nativeLibraryDir, PROOT_EXEC).isFile ->
            "proot executable not found: ${File(nativeLibraryDir, PROOT_EXEC).absolutePath}"
        !File(nativeLibraryDir, PROOT_LOADER).isFile ->
            "proot loader not found: ${File(nativeLibraryDir, PROOT_LOADER).absolutePath}"
        else -> null
    }

    private fun processBuilder(context: WorkspaceShellContext, command: List<String>): ProcessBuilder {
        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (context.shellCompatibilityMode) {
                    environment()["PROOT_NO_SECCOMP"] = "1"
                } else {
                    environment().remove("PROOT_NO_SECCOMP")
                }
                environment()["PROOT_LOADER"] = File(nativeLibraryDir, PROOT_LOADER).absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
    }

    override fun terminalLaunch(context: WorkspaceTerminalContext): WorkspaceTerminalLaunch {
        val shell = WorkspaceManager.rootfsShell(context.linuxDir)
        val args = prootArgs(context.linuxDir, context.filesDir, WORKSPACE_DIR, context.bindMounts)
        args += buildRootfsEnv(context.linuxDir, context.bindMounts)
        args += listOf(
            "USER=root",
            "SHELL=$shell",
            shell,
        )

        val env = mutableListOf(
            "PROOT_LOADER=${File(nativeLibraryDir, PROOT_LOADER).absolutePath}",
            "PROOT_TMP_DIR=${context.tempDir.absolutePath}",
            "TMPDIR=${context.tempDir.absolutePath}",
        )
        if (context.shellCompatibilityMode) {
            env += "PROOT_NO_SECCOMP=1"
        }

        return WorkspaceTerminalLaunch(
            executable = File(nativeLibraryDir, PROOT_EXEC).absolutePath,
            cwd = context.filesDir.absolutePath,
            args = args,
            env = env,
        )
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        script: String,
        vararg extraArgs: String,
    ): List<String> {
        val command = mutableListOf(File(nativeLibraryDir, PROOT_EXEC).absolutePath)
        command += prootArgs(context.linuxDir, context.filesDir, context.prootCwd(), context.bindMounts)
        command += buildRootfsEnv(context.linuxDir, context.bindMounts)
        command += listOf(
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            WorkspaceManager.rootfsShell(context.linuxDir),
            "-l",
            "-c",
            script,
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        command += extraArgs
        return command
    }

    /**
     * 若当前会话挂载了宿主机存储且 Rootfs 为 glibc (Ubuntu/Debian)，
     * 注入 `libsdcard_ro.so` 在系统调用层强制将 `/sdcard` 与 `/storage/emulated/0` 锁定为只读 (`EROFS`)。
     */
    private fun buildRootfsEnv(linuxDir: File, bindMounts: List<WorkspaceBindMount>): List<String> {
        val preload = resolveSdcardReadOnlyPreload(linuxDir, bindMounts)
        return if (preload != null) {
            ROOTFS_ENV + "LD_PRELOAD=$preload"
        } else {
            ROOTFS_ENV
        }
    }

    private fun resolveSdcardReadOnlyPreload(linuxDir: File, bindMounts: List<WorkspaceBindMount>): String? {
        val bridgeMount = bindMounts.firstOrNull { it.target.trimEnd('/') == "/run/host_bridge" } ?: return null
        return when {
            (File(linuxDir, "lib/aarch64-linux-gnu/libc.so.6").exists() ||
                File(linuxDir, "usr/lib/aarch64-linux-gnu/libc.so.6").exists()) &&
                File(bridgeMount.source, "libsdcard_ro_arm64.so").isFile -> {
                "/run/host_bridge/libsdcard_ro_arm64.so"
            }

            (File(linuxDir, "lib/x86_64-linux-gnu/libc.so.6").exists() ||
                File(linuxDir, "usr/lib/x86_64-linux-gnu/libc.so.6").exists()) &&
                File(bridgeMount.source, "libsdcard_ro_x86_64.so").isFile -> {
                "/run/host_bridge/libsdcard_ro_x86_64.so"
            }

            else -> null
        }
    }

    /** PRoot 自身的参数: Rootfs、工作目录与挂载表, 命令执行和交互式终端共用 */
    private fun prootArgs(
        linuxDir: File,
        filesDir: File,
        cwd: String,
        bindMounts: List<WorkspaceBindMount>,
    ): MutableList<String> {
        val args = mutableListOf(
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            cwd,
            "-b",
            "${filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        bindMounts.forEach { mount ->
            if (mount.source.exists() && mount.source.canRead()) {
                args += "-b"
                args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        return args
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR

        // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
        private const val EXEC_SCRIPT = "cd -- \"\$1\" && eval \"\$2\""

        // 先把 shell 与 PRoot 的进程号写入 $3, 终止时据此给整组进程发信号, 见 terminateProot
        private const val TRACKED_SCRIPT = "echo \"\$\$ \$PPID\" > \"\$3\"; $EXEC_SCRIPT"

        /** Rootfs 内存放后台命令日志与进程号的目录 */
        private const val BACKGROUND_DIR = "/tmp/rikkahub/background"

        /** Rootfs 内存放前台命令进程号的目录 */
        private const val EXEC_DIR = "/tmp/rikkahub/exec"

        /** 清空宿主机环境变量后 Rootfs 内的基础环境 */
        private val ROOTFS_ENV = listOf(
            "/usr/bin/env",
            "-i",
            "HOME=${WorkspaceManager.ROOTFS_HOME_DIR}",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
        )
    }
}
