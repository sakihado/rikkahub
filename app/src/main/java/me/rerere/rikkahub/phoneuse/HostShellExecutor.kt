package me.rerere.rikkahub.phoneuse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit

enum class HostPrivilegeState {
    UNKNOWN,
    /** 已确认具备 Root (su) 能力，可按需以 UID 2000 (shell) 或 UID 0 (root) 执行 */
    ROOT_GRANTED,
    /** 未获取 Root，仅能以当前 App 普通沙盒身份执行 */
    UNPRIVILEGED,
}

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
 * 宿主机底层命令执行器（Magisk / KernelSU / APatch / Sui 通用）
 *
 * - 默认优先降权以 UID 2000 (shell) 运行，既拥有 dumpsys / logcat / uiautomator / screencap / input / pm / am 能力，
 *   又由内核与 SELinux 隔离其他应用私有目录与系统分区写入。
 * - 显式指定 [asRoot] = true 时以 UID 0 运行。
 * - 支持字节流无损采集（兼容 screencap -p 等二进制输出）、进程组级超时与协程取消强杀。
 */
class HostShellExecutor {

    private val _privilegeState = MutableStateFlow(HostPrivilegeState.UNKNOWN)
    val privilegeState: StateFlow<HostPrivilegeState> = _privilegeState.asStateFlow()

    /** 当前进程生命周期内用户已审批通过的可复用只读/轻操作签名集合 */
    private val sessionApprovedKeys: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    @Volatile
    private var suBinaryPath: String? = null

    @Volatile
    private var supportsUid2000Flag: Boolean? = null

    fun hasSessionApproval(command: String, asRoot: Boolean): Boolean =
        approvalKey(command, asRoot) in sessionApprovedKeys

    fun recordSessionApproval(command: String, asRoot: Boolean) {
        sessionApprovedKeys += approvalKey(command, asRoot)
    }

    private fun approvalKey(command: String, asRoot: Boolean): String =
        "${if (asRoot) "root" else "shell"}:${command.trim()}"

    /**
     * 探测设备是否存在 su 并请求/确认授权。
     * 首次调用时会触发 Magisk / Sui 授权弹窗（若尚未授权）。
     */
    suspend fun probeRootAccess(forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!forceRefresh && _privilegeState.value != HostPrivilegeState.UNKNOWN) {
            return@withContext _privilegeState.value == HostPrivilegeState.ROOT_GRANTED
        }
        val candidate = findSuBinary()
        if (candidate == null) {
            _privilegeState.value = HostPrivilegeState.UNPRIVILEGED
            return@withContext false
        }
        val probe = runRawProcess(
            commandArgs = listOf(candidate, "-c", "id -u"),
            timeoutMs = 12_000L,
            maxStdoutBytes = 1024,
        )
        val granted = !probe.timedOut && probe.exitCode == 0 && probe.stdout.trim() == "0"
        if (granted) {
            suBinaryPath = candidate
            // 探测该 su 实现是否支持 `su 2000 -c ...`（Magisk / KernelSU / APatch 均支持）
            val shellProbe = runRawProcess(
                commandArgs = listOf(candidate, "2000", "-c", "id -u"),
                timeoutMs = 5_000L,
                maxStdoutBytes = 1024,
            )
            supportsUid2000Flag = !shellProbe.timedOut && shellProbe.exitCode == 0 && shellProbe.stdout.trim() == "2000"
            _privilegeState.value = HostPrivilegeState.ROOT_GRANTED
        } else {
            _privilegeState.value = HostPrivilegeState.UNPRIVILEGED
        }
        granted
    }

    /** 快速检查系统是否存在 su 二进制（不触发弹窗） */
    fun hasSuBinaryOnDevice(): Boolean = findSuBinary() != null

    /**
     * 获得 Root 后自动为本应用开启 MANAGE_EXTERNAL_STORAGE appops，
     * 使 PRoot 工作区的 /sdcard 挂载直接具备完整文件访问能力，免去手动跳系统设置页。
     */
    suspend fun ensureStorageAccessViaRoot(packageName: String) {
        if (!probeRootAccess()) return
        val su = suBinaryPath ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                runRawProcess(
                    commandArgs = listOf(
                        su,
                        "-c",
                        "appops set $packageName MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; " +
                            "pm grant $packageName android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; " +
                            "pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null"
                    ),
                    timeoutMs = 5_000L,
                    maxStdoutBytes = 512,
                )
            }
        }
    }

    suspend fun execute(
        command: String,
        asRoot: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxStdoutBytes: Int = MAX_CAPTURE_BYTES,
    ): HostShellResult = withContext(Dispatchers.IO) {
        val hasRoot = probeRootAccess()
        val su = suBinaryPath

        // 构造带有完整 Android 系统环境的包装脚本，并将子进程 PID 记录以便超时整组清理
        val wrappedScript = buildAndroidEnvScript(command)

        val args: List<String>
        val identityLabel: String
        if (hasRoot && su != null) {
            if (asRoot) {
                args = listOf(su, "-c", wrappedScript)
                identityLabel = "root (uid=0)"
            } else if (supportsUid2000Flag == true) {
                args = listOf(su, "2000", "-c", wrappedScript)
                identityLabel = "shell (uid=2000)"
            } else {
                // 极少数不支持 su <uid> 参数的变体回退
                args = listOf(su, "-c", wrappedScript)
                identityLabel = "root-fallback (uid=0)"
            }
        } else {
            if (asRoot) {
                return@withContext HostShellResult(
                    exitCode = 126,
                    stdoutBytes = ByteArray(0),
                    stderrBytes = "Root permission denied or unavailable. Grant root access to RikkaHub in Magisk / Sui first."
                        .toByteArray(Charsets.UTF_8),
                    effectiveIdentity = "unprivileged",
                )
            }
            val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
            args = listOf(sh, "-c", wrappedScript)
            identityLabel = "app-sandbox"
        }

        runRawProcess(
            commandArgs = args,
            timeoutMs = timeoutMs.coerceIn(500L, MAX_TIMEOUT_MS),
            maxStdoutBytes = maxStdoutBytes,
            killAsPrivileged = hasRoot && su != null,
        ).copy(effectiveIdentity = identityLabel)
    }

    private fun buildAndroidEnvScript(userCommand: String): String {
        return buildString {
            append("export PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:\$PATH; ")
            append("export ANDROID_ROOT=\${ANDROID_ROOT:-/system}; ")
            append("export ANDROID_DATA=\${ANDROID_DATA:-/data}; ")
            append("export ANDROID_STORAGE=\${ANDROID_STORAGE:-/storage}; ")
            append(userCommand)
        }
    }

    private suspend fun runRawProcess(
        commandArgs: List<String>,
        timeoutMs: Long,
        maxStdoutBytes: Int,
        killAsPrivileged: Boolean = false,
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

        // 立即关闭 stdin，防止交互式子命令等待输入卡死
        runCatching { process.outputStream.close() }

        val stdoutCollector = ByteStreamCollector(process.inputStream, maxStdoutBytes)
        val stderrCollector = ByteStreamCollector(process.errorStream, MAX_STDERR_BYTES)

        fun forceKillTree() {
            val pid = getProcessPid(process)
            process.destroyForcibly()
            if (killAsPrivileged && pid != null && suBinaryPath != null) {
                runCatching {
                    ProcessBuilder(
                        suBinaryPath!!,
                        "-c",
                        "pkill -9 -P $pid 2>/dev/null; kill -9 -$pid $pid 2>/dev/null"
                    ).start().waitFor(1500, TimeUnit.MILLISECONDS)
                }
            }
        }

        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                forceKillTree()
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
        }
    }

    private fun getProcessPid(process: Process): Long? = runCatching {
        // Android API 33+ 支持 Process.pid()，低版本通过 UNIXProcess 反射兜底
        val method = Process::class.java.methods.firstOrNull { it.name == "pid" && it.parameterCount == 0 }
        if (method != null) {
            (method.invoke(process) as? Number)?.toLong()
        } else {
            val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            (field.get(process) as? Number)?.toLong()
        }
    }.getOrNull()

    private fun findSuBinary(): String? {
        val knownPaths = listOf(
            "/debug_ramdisk/su",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
        )
        knownPaths.firstOrNull { File(it).exists() }?.let { return it }

        val envPath = System.getenv("PATH").orEmpty().split(':')
        for (dir in envPath) {
            if (dir.isBlank()) continue
            val f = File(dir, "su")
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val MAX_TIMEOUT_MS = 120_000L
        /** 默认最大捕获 16MB 二进制/大日志输出（供截图或落盘到 /tool_outputs） */
        const val MAX_CAPTURE_BYTES = 16 * 1024 * 1024
        private const val MAX_STDERR_BYTES = 256 * 1024
    }
}

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
