package me.rerere.rikkahub.phoneuse

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "HostShellExecutor"

data class HostShellResult(
    val exitCode: Int,
    val stdoutBytes: ByteArray,
    val stderrBytes: ByteArray,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val effectiveIdentity: String = "",
) {
    val stdout: String get() = stdoutBytes.toString(Charsets.UTF_8)
    val stderr: String get() = stderrBytes.toString(Charsets.UTF_8)
}

/**
 * 统一宿主机命令进程执行与取消器（职责收敛：专职命令派发、身份隔离契约与整棵子进程树生命周期清理）。
 *
 * 安全与可靠性保证：
 * 1. **严格身份契约（Fail-Closed）**：
 *    当 `asRoot == false` 时，绝不在 `libshelldrop.so` 或 `su 2000` 降权失败时静默退回 `UID 0` 执行；
 *    若无法降权至 `UID 2000`，立即返回退出码 126 并要求显式传 `root=true` 走 Root 审批。
 * 2. **进程组 + Lifeline 管道零残留清理**：
 *    优先通过内置 `libshelldrop.so --lifeline-stdin` 为每次命令创建独立进程组 (`setpgid`)，
 *    当客户端超时或点击「停止生成」断开 lifeline 管道时，守护父进程在毫秒级向 `-child_pgid` 发送 `SIGKILL`，
 *    彻底杜绝后台孙进程逃逸。即使在无 `libshelldrop` 的兜底路径下，也先采集并清理后代进程树再销毁外层进程。
 */
class HostShellExecutor(
    private val context: Context? = null,
    val privilegeManager: HostPrivilegeManager = HostPrivilegeManager(context),
) {
    val backendState: StateFlow<HostBackendType>
        get() = privilegeManager.backendState

    val canUseBinderFastInput: Boolean
        get() = privilegeManager.canUseBinderFastInput

    private val shellDropProbeMutex = Mutex()

    @Volatile
    private var verifiedShizukuSupervisorPath: String? = null

    @Volatile
    private var shizukuSupervisorProbed: Boolean = false

    @Volatile
    private var verifiedSuSupervisorPath: String? = null

    @Volatile
    private var suSupervisorProbed: Boolean = false

    private val nativeShellDropBinary: File?
        get() = context?.applicationInfo?.nativeLibraryDir
            ?.let { File(it, "libshelldrop.so") }
            ?.takeIf { it.exists() && it.canExecute() }

    suspend fun ensurePrivilegedAccess(interactive: Boolean = true): Boolean =
        privilegeManager.ensurePrivilegedAccess(interactive)

    suspend fun execute(
        command: String,
        asRoot: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxStdoutBytes: Int = MAX_CAPTURE_BYTES,
    ): HostShellResult = withContext(Dispatchers.IO) {
        if (!backendState.value.isPrivileged) {
            privilegeManager.ensurePrivilegedAccess(interactive = true)
        }

        val wrappedScript = buildAndroidEnvScript(command)
        val clampedTimeout = timeoutMs.coerceIn(500L, MAX_TIMEOUT_MS)

        when (backendState.value) {
            HostBackendType.SUI_ROOT -> executeOnSuiRoot(wrappedScript, asRoot, clampedTimeout, maxStdoutBytes)
            HostBackendType.SHIZUKU_SHELL -> executeOnShizukuShell(wrappedScript, asRoot, clampedTimeout, maxStdoutBytes)
            HostBackendType.SU_ROOT -> executeOnSuRoot(wrappedScript, asRoot, clampedTimeout, maxStdoutBytes)
            HostBackendType.UNKNOWN, HostBackendType.UNPRIVILEGED -> {
                executeUnprivileged(wrappedScript, asRoot, clampedTimeout, maxStdoutBytes)
            }
        }
    }

    private suspend fun executeOnSuiRoot(
        wrappedScript: String,
        asRoot: Boolean,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): HostShellResult {
        val supervisor = getVerifiedSupervisorViaShizuku()
        if (asRoot) {
            val args = if (supervisor != null) {
                arrayOf(supervisor, "--keep-uid", "--lifeline-stdin", "-c", wrappedScript)
            } else {
                arrayOf("/system/bin/sh", "-c", buildLifelineFallbackScript(wrappedScript))
            }
            return executeViaShizuku(
                cmdArgs = args,
                keepStdinAsLifeline = true,
                timeoutMs = timeoutMs,
                maxStdoutBytes = maxStdoutBytes,
            ).copy(effectiveIdentity = "sui-root (uid=0)")
        }

        // asRoot == false: 严格要求降权至 UID 2000，绝不静默退回 UID 0！
        if (supervisor == null) {
            return failClosedUid2000Unavailable("Sui backend could not verify UID 2000 privilege-drop helper (libshelldrop.so).")
        }
        return executeViaShizuku(
            cmdArgs = arrayOf(supervisor, "--uid-shell", "--lifeline-stdin", "-c", wrappedScript),
            keepStdinAsLifeline = true,
            timeoutMs = timeoutMs,
            maxStdoutBytes = maxStdoutBytes,
        ).copy(effectiveIdentity = "sui-shell (uid=2000)")
    }

    private suspend fun executeOnShizukuShell(
        wrappedScript: String,
        asRoot: Boolean,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): HostShellResult {
        if (asRoot) {
            val su = privilegeManager.ensureSuForRootEscalation()
            return if (su != null) {
                executeWithSu(su, wrappedScript, asRoot = true, timeoutMs = timeoutMs, maxStdoutBytes = maxStdoutBytes)
            } else {
                HostShellResult(
                    exitCode = 126,
                    stdoutBytes = ByteArray(0),
                    stderrBytes = "Current backend is Shizuku (UID 2000 ADB shell); UID 0 root commands are unavailable without Root/Sui."
                        .toByteArray(Charsets.UTF_8),
                    effectiveIdentity = "shizuku-shell (uid=2000)",
                )
            }
        }

        val supervisor = getVerifiedSupervisorViaShizuku()
        val args = if (supervisor != null) {
            arrayOf(supervisor, "--keep-uid", "--lifeline-stdin", "-c", wrappedScript)
        } else {
            arrayOf("/system/bin/sh", "-c", buildLifelineFallbackScript(wrappedScript))
        }
        return executeViaShizuku(
            cmdArgs = args,
            keepStdinAsLifeline = true,
            timeoutMs = timeoutMs,
            maxStdoutBytes = maxStdoutBytes,
        ).copy(effectiveIdentity = "shizuku-shell (uid=2000)")
    }

    private suspend fun executeOnSuRoot(
        wrappedScript: String,
        asRoot: Boolean,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): HostShellResult {
        val su = privilegeManager.suBinaryPath
            ?: return executeUnprivileged(wrappedScript, asRoot, timeoutMs, maxStdoutBytes)
        return executeWithSu(su, wrappedScript, asRoot, timeoutMs, maxStdoutBytes)
    }

    private suspend fun executeWithSu(
        su: String,
        wrappedScript: String,
        asRoot: Boolean,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): HostShellResult {
        val supervisor = getVerifiedSupervisorViaSu(su)
        if (asRoot) {
            val cmdStr = if (supervisor != null) {
                "exec ${shellSingleQuote(supervisor)} --keep-uid --lifeline-stdin -c ${shellSingleQuote(wrappedScript)}"
            } else {
                buildLifelineFallbackScript(wrappedScript)
            }
            return runLocalProcess(
                commandArgs = listOf(su, "-c", cmdStr),
                keepStdinAsLifeline = true,
                timeoutMs = timeoutMs,
                maxStdoutBytes = maxStdoutBytes,
                suPathForCleanup = su,
            ).copy(effectiveIdentity = "su-root (uid=0)")
        }

        // asRoot == false: 优先用 libshelldrop 降权至 UID 2000 并建立独立进程组；其次用原生 `su 2000`；都不可用则明确报错
        return when {
            supervisor != null -> {
                val cmdStr = "exec ${shellSingleQuote(supervisor)} --uid-shell --lifeline-stdin -c ${shellSingleQuote(wrappedScript)}"
                runLocalProcess(
                    commandArgs = listOf(su, "-c", cmdStr),
                    keepStdinAsLifeline = true,
                    timeoutMs = timeoutMs,
                    maxStdoutBytes = maxStdoutBytes,
                    suPathForCleanup = su,
                ).copy(effectiveIdentity = "su-shell (uid=2000)")
            }

            privilegeManager.supportsSuUid2000 == true -> {
                runLocalProcess(
                    commandArgs = listOf(su, "2000", "-c", buildLifelineFallbackScript(wrappedScript)),
                    keepStdinAsLifeline = true,
                    timeoutMs = timeoutMs,
                    maxStdoutBytes = maxStdoutBytes,
                    suPathForCleanup = su,
                ).copy(effectiveIdentity = "su-shell (uid=2000)")
            }

            else -> failClosedUid2000Unavailable("Current `su` binary cannot drop privileges to UID 2000 (shell).")
        }
    }

    private fun failClosedUid2000Unavailable(detail: String): HostShellResult = HostShellResult(
        exitCode = 126,
        stdoutBytes = ByteArray(0),
        stderrBytes = (
            "$detail Refusing to silently escalate `root=false` command to UID 0. " +
                "Pass `root=true` explicitly if root execution is intended so it undergoes root approval."
            ).toByteArray(Charsets.UTF_8),
        effectiveIdentity = "uid2000-unavailable",
    )

    private suspend fun executeUnprivileged(
        wrappedScript: String,
        asRoot: Boolean,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): HostShellResult {
        if (asRoot) {
            return HostShellResult(
                exitCode = 126,
                stdoutBytes = ByteArray(0),
                stderrBytes = "Root/Sui permission denied or unavailable. Grant access in Sui / Shizuku / Magisk first."
                    .toByteArray(Charsets.UTF_8),
                effectiveIdentity = "unprivileged",
            )
        }
        val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
        val supervisor = nativeShellDropBinary?.absolutePath
        val args = if (supervisor != null) {
            listOf(supervisor, "--keep-uid", "--lifeline-stdin", "-c", wrappedScript)
        } else {
            listOf(sh, "-c", buildLifelineFallbackScript(wrappedScript))
        }
        return runLocalProcess(
            commandArgs = args,
            keepStdinAsLifeline = true,
            timeoutMs = timeoutMs,
            maxStdoutBytes = maxStdoutBytes,
            suPathForCleanup = null,
        ).copy(effectiveIdentity = "app-sandbox")
    }

    private suspend fun getVerifiedSupervisorViaShizuku(): String? = shellDropProbeMutex.withLock {
        if (shizukuSupervisorProbed) return@withLock verifiedShizukuSupervisorPath
        val bin = nativeShellDropBinary ?: run {
            shizukuSupervisorProbed = true
            return@withLock null
        }

        val expectFlag = if (backendState.value == HostBackendType.SUI_ROOT) "--uid-shell" else "--keep-uid"
        val directProbe = executeViaShizuku(
            cmdArgs = arrayOf(bin.absolutePath, expectFlag, "-c", "id -u"),
            keepStdinAsLifeline = false,
            timeoutMs = 3_000L,
            maxStdoutBytes = 256,
        )
        if (!directProbe.timedOut && directProbe.exitCode == 0 && directProbe.stdout.trim() == "2000") {
            verifiedShizukuSupervisorPath = bin.absolutePath
            shizukuSupervisorProbed = true
            return@withLock verifiedShizukuSupervisorPath
        }

        val stagedPath = "/data/local/tmp/.rikkahub_shelldrop_${context?.packageName.orEmpty().hashCode().toUInt()}"
        val stageProbe = executeViaShizuku(
            cmdArgs = arrayOf(
                "/system/bin/sh",
                "-c",
                "cp -f ${shellSingleQuote(bin.absolutePath)} $stagedPath && chmod 755 $stagedPath && $stagedPath $expectFlag -c 'id -u'",
            ),
            keepStdinAsLifeline = false,
            timeoutMs = 4_000L,
            maxStdoutBytes = 256,
        )
        if (!stageProbe.timedOut && stageProbe.exitCode == 0 && stageProbe.stdout.trim() == "2000") {
            verifiedShizukuSupervisorPath = stagedPath
            shizukuSupervisorProbed = true
            return@withLock verifiedShizukuSupervisorPath
        }

        Log.w(TAG, "Shizuku supervisor probe failed (exit=${stageProbe.exitCode}, err=${stageProbe.stderr})")
        shizukuSupervisorProbed = true
        return@withLock null
    }

    private suspend fun getVerifiedSupervisorViaSu(su: String): String? = shellDropProbeMutex.withLock {
        if (suSupervisorProbed) return@withLock verifiedSuSupervisorPath
        val bin = nativeShellDropBinary ?: run {
            suSupervisorProbed = true
            return@withLock null
        }

        val directProbe = runLocalProcess(
            commandArgs = listOf(su, "-c", "${shellSingleQuote(bin.absolutePath)} --uid-shell -c 'id -u'"),
            keepStdinAsLifeline = false,
            timeoutMs = 4_000L,
            maxStdoutBytes = 256,
            suPathForCleanup = su,
        )
        if (!directProbe.timedOut && directProbe.exitCode == 0 && directProbe.stdout.trim() == "2000") {
            verifiedSuSupervisorPath = bin.absolutePath
            suSupervisorProbed = true
            return@withLock verifiedSuSupervisorPath
        }

        val stagedPath = "/data/local/tmp/.rikkahub_shelldrop_${context?.packageName.orEmpty().hashCode().toUInt()}"
        val stageProbe = runLocalProcess(
            commandArgs = listOf(
                su,
                "-c",
                "cp -f ${shellSingleQuote(bin.absolutePath)} $stagedPath && chmod 755 $stagedPath && $stagedPath --uid-shell -c 'id -u'",
            ),
            keepStdinAsLifeline = false,
            timeoutMs = 4_000L,
            maxStdoutBytes = 256,
            suPathForCleanup = su,
        )
        if (!stageProbe.timedOut && stageProbe.exitCode == 0 && stageProbe.stdout.trim() == "2000") {
            verifiedSuSupervisorPath = stagedPath
            suSupervisorProbed = true
            return@withLock verifiedSuSupervisorPath
        }

        suSupervisorProbed = true
        return@withLock null
    }

    private suspend fun executeViaShizuku(
        cmdArgs: Array<String>,
        keepStdinAsLifeline: Boolean,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): HostShellResult = withContext(Dispatchers.IO) {
        val binder = Shizuku.getBinder()
            ?: return@withContext HostShellResult(
                exitCode = 127,
                stdoutBytes = ByteArray(0),
                stderrBytes = "Shizuku/Sui binder disconnected".toByteArray(Charsets.UTF_8),
            )

        val remote: IRemoteProcess = try {
            IShizukuService.Stub.asInterface(binder).newProcess(cmdArgs, null, null)
        } catch (e: Throwable) {
            return@withContext HostShellResult(
                exitCode = 127,
                stdoutBytes = ByteArray(0),
                stderrBytes = (e.message ?: "IShizukuService.newProcess failed").toByteArray(Charsets.UTF_8),
            )
        }

        val lifelineHandle: Closeable? = runCatching {
            remote.outputStream?.let { pfd ->
                val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                if (!keepStdinAsLifeline) {
                    out.close()
                    null
                } else {
                    out
                }
            }
        }.getOrNull()

        val stdoutStream = runCatching {
            remote.inputStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        }.getOrNull() ?: InputStream.nullInputStream()

        val stderrStream = runCatching {
            remote.errorStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        }.getOrNull() ?: InputStream.nullInputStream()

        awaitBlockingRemoteProcess(
            lifelineHandle = lifelineHandle,
            stdoutStream = stdoutStream,
            stderrStream = stderrStream,
            timeoutMs = timeoutMs,
            maxStdoutBytes = maxStdoutBytes,
            waitForTimeout = { ms -> remote.waitForTimeout(ms, TimeUnit.MILLISECONDS.name) },
            exitValue = { remote.exitValue() },
            destroyRemote = { remote.destroy() },
        )
    }

    /**
     * 将「取消控制流（关闭本地 lifeline 管道 + 后台异步销毁）」与「同步阻塞 Binder 等待 (`waitForTimeout`)」彻底解耦：
     * 即使内核 Binder `ioctl` 完全不响应 `Thread.interrupt()`，协程一旦取消（用户点击停止），
     * `deferred.await()` 也会在毫秒级立即抛出 [CancellationException] 并当场关闭本地 [lifelineHandle]，
     * 唤醒远端 `libshelldrop` 瞬间 `kill(-child_pgid, SIGKILL)`，绝不卡在同步 Binder 等待后面。
     */
    internal suspend fun awaitBlockingRemoteProcess(
        lifelineHandle: Closeable?,
        stdoutStream: InputStream,
        stderrStream: InputStream,
        timeoutMs: Long,
        maxStdoutBytes: Int,
        waitForTimeout: (Long) -> Boolean,
        exitValue: () -> Int,
        destroyRemote: () -> Unit,
    ): HostShellResult {
        val stdoutCollector = ByteStreamCollector(stdoutStream, maxStdoutBytes)
        val stderrCollector = ByteStreamCollector(stderrStream, MAX_STDERR_BYTES)
        val abortedOnce = AtomicBoolean(false)

        fun abortNow(asyncDestroy: Boolean) {
            if (!abortedOnce.compareAndSet(false, true)) return
            // 本地管道写端 close() 永不阻塞在远端 Binder 上，毫秒级触发远端 POLLHUP / EOF
            runCatching { lifelineHandle?.close() }
            if (asyncDestroy) {
                Thread {
                    runCatching { waitForTimeout(120L) }
                    runCatching { destroyRemote() }
                    runCatching { stdoutStream.close() }
                    runCatching { stderrStream.close() }
                }.apply {
                    isDaemon = true
                    name = "rikkahub-remote-abort"
                    start()
                }
            } else {
                runCatching { waitForTimeout(150L) }
                runCatching { destroyRemote() }
            }
        }

        val waitResult = CompletableDeferred<Result<Boolean>>()
        Thread {
            val outcome = runCatching { waitForTimeout(timeoutMs) }
            waitResult.complete(outcome)
        }.apply {
            isDaemon = true
            name = "rikkahub-binder-wait"
            start()
        }

        return try {
            val outcome = waitResult.await()
            val finished = outcome.getOrElse { err ->
                abortNow(asyncDestroy = false)
                stdoutCollector.join(500)
                stderrCollector.join(500)
                return HostShellResult(
                    exitCode = 127,
                    stdoutBytes = stdoutCollector.bytes(),
                    stderrBytes = (err.message ?: "Shizuku process error").toByteArray(Charsets.UTF_8),
                )
            }
            if (!finished) {
                abortNow(asyncDestroy = false)
            } else {
                runCatching { lifelineHandle?.close() }
            }
            stdoutCollector.join(1_000)
            stderrCollector.join(1_000)
            val code = if (finished) runCatching { exitValue() }.getOrDefault(1) else 124
            HostShellResult(
                exitCode = code,
                stdoutBytes = stdoutCollector.bytes(),
                stderrBytes = stderrCollector.bytes(),
                timedOut = !finished,
                truncated = stdoutCollector.truncated || stderrCollector.truncated,
            )
        } catch (e: CancellationException) {
            abortNow(asyncDestroy = true)
            throw e
        } finally {
            runCatching { lifelineHandle?.close() }
        }
    }

    internal suspend fun runLocalProcess(
        commandArgs: List<String>,
        keepStdinAsLifeline: Boolean,
        timeoutMs: Long,
        maxStdoutBytes: Int,
        suPathForCleanup: String? = null,
    ): HostShellResult = runInterruptible(Dispatchers.IO) {
        val pb = ProcessBuilder(commandArgs).redirectErrorStream(false)
        val process = try {
            pb.start()
        } catch (e: IOException) {
            return@runInterruptible HostShellResult(
                exitCode = 127,
                stdoutBytes = ByteArray(0),
                stderrBytes = (e.message ?: "Failed to start shell process").toByteArray(Charsets.UTF_8),
            )
        }

        val lifelineStream = process.outputStream
        if (!keepStdinAsLifeline) {
            runCatching { lifelineStream.close() }
        }

        val stdoutCollector = ByteStreamCollector(process.inputStream, maxStdoutBytes)
        val stderrCollector = ByteStreamCollector(process.errorStream, MAX_STDERR_BYTES)

        fun forceKillTree() {
            val rootPid = getProcessPid(process)
            // 1. 先关闭 lifeline 管道，让 libshelldrop 或兜底监控器自行把进程组 SIGKILL 干净
            runCatching { lifelineStream.close() }
            if (runCatching { process.waitFor(150, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                return
            }
            // 2. 在销毁父进程之前（趁子进程尚未被 init 收养），自底向上递归收集并强杀整棵后代树！
            if (rootPid != null) {
                if (suPathForCleanup != null) {
                    runCatching {
                        ProcessBuilder(
                            suPathForCleanup,
                            "-c",
                            buildRecursiveKillScript(rootPid),
                        ).start().waitFor(1_500, TimeUnit.MILLISECONDS)
                    }
                } else {
                    killLocalDescendantTree(rootPid)
                }
            }
            process.destroyForcibly()
        }

        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                forceKillTree()
            } else {
                runCatching { lifelineStream.close() }
            }
            stdoutCollector.join(1_000)
            stderrCollector.join(1_000)
            HostShellResult(
                exitCode = if (finished) process.exitValue() else 124,
                stdoutBytes = stdoutCollector.bytes(),
                stderrBytes = stderrCollector.bytes(),
                timedOut = !finished,
                truncated = stdoutCollector.truncated || stderrCollector.truncated,
            )
        } catch (e: InterruptedException) {
            forceKillTree()
            stdoutCollector.join(500)
            stderrCollector.join(500)
            throw e
        } finally {
            runCatching { lifelineStream.close() }
        }
    }

    private fun buildAndroidEnvScript(userCommand: String): String = buildString {
        append("export PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:\$PATH; ")
        append("export ANDROID_ROOT=\${ANDROID_ROOT:-/system}; ")
        append("export ANDROID_DATA=\${ANDROID_DATA:-/data}; ")
        append("export ANDROID_STORAGE=\${ANDROID_STORAGE:-/storage}; ")
        append(userCommand)
    }

    /**
     * 当极少数环境无 `libshelldrop.so` 可用时（如纯 JVM 宿主测试或无 native 库环境），
     * 显式将真实 lifeline 输入备份到 FD 3（规避非交互 `/bin/sh` 后台命令 `&` 默认绑定 `/dev/null` 的标准行为），
     * 确保正常执行绝不误触发 EOF，而在客户端超时/取消关闭 stdin 时立即递归强杀整棵后代进程树。
     */
    internal fun buildLifelineFallbackScript(innerScript: String): String =
        "exec 3<&0 </dev/null; " +
            "sh -c ${shellSingleQuote(innerScript)} 3<&- </dev/null & " +
            "__rk_child=\$!; " +
            "( " +
            "kill_tree() { " +
            "for __c in \$(pgrep -P \"\$1\" 2>/dev/null); do kill_tree \"\$__c\"; done; " +
            "kill -9 \"\$1\" 2>/dev/null; " +
            "}; " +
            "IFS= read -r _ <&3 || true; " +
            "kill_tree \"\$__rk_child\" " +
            ") >/dev/null 2>&1 & " +
            "__rk_watch=\$!; " +
            "exec 3<&-; " +
            "wait \"\$__rk_child\"; __rk_rc=\$?; " +
            "kill -9 \"\$__rk_watch\" 2>/dev/null; exit \$__rk_rc"

    private fun buildRecursiveKillScript(rootPid: Long): String =
        "kill_tree() { " +
            "local p=\$1; local c; " +
            "for c in \$(pgrep -P \"\$p\" 2>/dev/null); do kill_tree \"\$c\"; done; " +
            "kill -9 \"\$p\" 2>/dev/null; " +
            "}; kill_tree $rootPid"

    private fun killLocalDescendantTree(rootPid: Long) {
        runCatching {
            val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
            ProcessBuilder(sh, "-c", buildRecursiveKillScript(rootPid))
                .start()
                .waitFor(1_000, TimeUnit.MILLISECONDS)
        }
    }

    private fun getProcessPid(process: Process): Long? = runCatching {
        val method = Process::class.java.methods.firstOrNull { it.name == "pid" && it.parameterCount == 0 }
        if (method != null) {
            (method.invoke(process) as? Number)?.toLong()
        } else {
            val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            (field.get(process) as? Number)?.toLong()
        }
    }.getOrNull()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_CAPTURE_BYTES = 16 * 1024 * 1024
        private const val MAX_STDERR_BYTES = 256 * 1024
    }
}

private fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

private class ByteStreamCollector(
    private val stream: InputStream,
    private val maxBytes: Int,
) {
    private val out = ByteArrayOutputStream()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    synchronized(out) {
                        val remaining = maxBytes - out.size()
                        if (remaining > 0) {
                            out.write(buf, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 超时或取消关闭流时正常退出
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun bytes(): ByteArray = synchronized(out) { out.toByteArray() }
}
