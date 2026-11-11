package me.rerere.rikkahub.phoneuse

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "HostShellExecutor"

/**
 * 宿主机特权后端类型（按 **Sui / Shizuku 优先 -> su 兜底** 自动协商）
 */
enum class HostBackendType {
    /** 尚未探测 */
    UNKNOWN,

    /** Zygisk-Sui 或 Root 启动的 Shizuku（服务端 UID 0，支持 Binder 极速输入 + UID 2000 降权 + UID 0） */
    SUI_ROOT,

    /** 无线调试 / ADB 启动的 Shizuku（服务端 UID 2000，支持 Binder 极速输入 + UID 2000，无 Root） */
    SHIZUKU_SHELL,

    /** 传统 su 守护进程（Magisk / KernelSU / APatch，无 Sui/Shizuku 时的兜底） */
    SU_ROOT,

    /** 无特权或用户已拒绝授权，仅普通 App 沙盒 */
    UNPRIVILEGED;

    val isPrivileged: Boolean
        get() = this == SUI_ROOT || this == SHIZUKU_SHELL || this == SU_ROOT

    val usesShizukuBinder: Boolean
        get() = this == SUI_ROOT || this == SHIZUKU_SHELL

    val supportsUid0: Boolean
        get() = this == SUI_ROOT || this == SU_ROOT
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
 * 统一宿主机命令与特权通道执行器（**Sui / Shizuku 第一优先 + Magisk/KSU `su` 兜底**）
 *
 * 1. **Sui / Shizuku 优先**：
 *    - 若检测到 Sui 或运行中的 Shizuku，全程通过常驻 Binder `IShizukuService.newProcess` 派发进程，
 *      完全不触发 Magisk `su` 弹窗，也不刷 Magisk 超级用户日志。
 *    - 当服务端为 UID 0（Zygisk-Sui）且 [asRoot] = false 时，通过内置原生 `libshelldrop.so` 在 0.1ms 内
 *      于内核层切换至 `UID 2000 (shell)` + `u:r:shell:s0` SELinux 上下文，严格维持最小权限隔离。
 * 2. **传统 `su` 无缝兜底**：
 *    - 仅当设备未安装/未运行 Sui 与 Shizuku（或在 Sui 面板中对本应用隐藏）时，自动回退 `su` / `su 2000`。
 * 3. **单次弹窗、持久静默重连**：
 *    - 首次按需触发唯一一次授权框；授权后后续冷启动自动静默挂载，0 弹窗打扰。
 */
class HostShellExecutor(
    private val context: Context? = null,
) {
    private val _backendState = MutableStateFlow(HostBackendType.UNKNOWN)
    val backendState: StateFlow<HostBackendType> = _backendState.asStateFlow()

    private val authMutex = Mutex()
    private val listenersRegistered = AtomicBoolean(false)
    private val permissionReqSeq = AtomicInteger(20260)

    /** 当前进程生命周期内用户已审批通过的可复用命令集合 */
    private val sessionApprovedKeys: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    @Volatile
    private var suBinaryPath: String? = null

    @Volatile
    private var supportsSuUid2000: Boolean? = null

    @Volatile
    private var verifiedShellDropPath: String? = null

    @Volatile
    private var shellDropProbed: Boolean = false

    @Volatile
    private var onBackendChangedCallback: ((HostBackendType) -> Unit)? = null

    private val suAuthorizedMarker: File?
        get() = context?.filesDir?.let { File(it, "host_bridge/su_authorized") }

    private val shellDropBinary: File?
        get() = context?.applicationInfo?.nativeLibraryDir
            ?.let { File(it, "libshelldrop.so") }
            ?.takeIf { it.exists() && it.canExecute() }

    val canUseBinderFastInput: Boolean
        get() = _backendState.value.usesShizukuBinder && isShizukuBinderReadyAndGranted()

    fun hasSessionApproval(command: String, asRoot: Boolean): Boolean =
        approvalKey(command, asRoot) in sessionApprovedKeys

    fun recordSessionApproval(command: String, asRoot: Boolean) {
        sessionApprovedKeys += approvalKey(command, asRoot)
    }

    private fun approvalKey(command: String, asRoot: Boolean): String =
        "${if (asRoot) "root" else "shell"}:${command.trim()}"

    /**
     * 注册 Shizuku / Sui Binder 生命周期监听，并在已获授权时静默完成连接（绝不在冷启动主动弹窗）。
     */
    suspend fun initializeOnStartup(onBackendReady: (HostBackendType) -> Unit) {
        if (BuildConfig.IS_PLAY_STORE) return
        onBackendChangedCallback = onBackendReady
        registerShizukuListenersOnce()

        // 兜底触发一次 Sui.init（正常情况下 ShizukuProvider.onCreate 已自动执行）
        val pkg = context?.packageName
        if (pkg != null && !isShizukuBinderAlive()) {
            runCatching { Sui.init(pkg) }
        }

        // 1. 若 Sui / Shizuku 在线且之前已点过允许，直接 0 弹窗激活
        if (isShizukuBinderReadyAndGranted()) {
            val resolved = resolveGrantedShizukuType()
            updateBackendState(resolved)
            return
        }

        // 2. 若 Sui / Shizuku 在线但尚未授权：等待用户开开关或首次用工具时再弹 Sui 框，切勿此时去碰 su！
        if (isShizukuBinderAlive()) {
            return
        }

        // 3. 设备未运行 Sui / Shizuku，且此前已经给过 Magisk su 授权：冷启动静默恢复 su 通道
        if (suAuthorizedMarker?.exists() == true && hasSuBinaryOnDevice()) {
            if (probeSuAccessInternal()) {
                updateBackendState(HostBackendType.SU_ROOT)
            }
        }
    }

    /**
     * 确保特权通道就绪。
     * @param interactive 为 true 时允许弹出系统授权框（严格遵循 Sui/Shizuku 优先，绝不连弹两个窗）。
     */
    suspend fun ensurePrivilegedAccess(interactive: Boolean = true): Boolean = authMutex.withLock {
        if (BuildConfig.IS_PLAY_STORE) return@withLock false
        registerShizukuListenersOnce()

        val pkg = context?.packageName
        if (pkg != null && !isShizukuBinderAlive()) {
            runCatching { Sui.init(pkg) }
        }

        // 优先级 1：Sui / Shizuku Binder
        if (isShizukuBinderAlive()) {
            if (isShizukuBinderReadyAndGranted()) {
                val resolved = resolveGrantedShizukuType()
                updateBackendState(resolved)
                return@withLock true
            }
            if (!interactive) {
                return@withLock false
            }
            val granted = requestShizukuPermissionSuspend()
            return@withLock if (granted && isShizukuBinderReadyAndGranted()) {
                val resolved = resolveGrantedShizukuType()
                updateBackendState(resolved)
                true
            } else {
                // 用户在 Sui / Shizuku 弹窗点了拒绝时尊重选择，不紧接着弹第二次 Magisk 窗骚扰用户
                updateBackendState(HostBackendType.UNPRIVILEGED)
                false
            }
        }

        // 优先级 2：Sui / Shizuku 不存在或未运行时，回退传统 su
        if (_backendState.value == HostBackendType.SU_ROOT && suBinaryPath != null) {
            return@withLock true
        }
        if (!hasSuBinaryOnDevice()) {
            updateBackendState(HostBackendType.UNPRIVILEGED)
            return@withLock false
        }
        if (!interactive && suAuthorizedMarker?.exists() != true) {
            return@withLock false
        }
        val suGranted = probeSuAccessInternal()
        if (suGranted) {
            updateBackendState(HostBackendType.SU_ROOT)
            return@withLock true
        }
        updateBackendState(HostBackendType.UNPRIVILEGED)
        return@withLock false
    }

    /**
     * 通过当前已授权的 Sui / Shizuku (UID 0 或 2000) 或 Root `su` 自动授予 `/sdcard` 访问权限，
     * 省去手动跳系统设置开启「所有文件访问权限」。
     */
    suspend fun ensureStorageAccess(packageName: String) {
        if (!_backendState.value.isPrivileged) return
        withContext(Dispatchers.IO) {
            runCatching {
                execute(
                    command = "appops set $packageName MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; " +
                        "pm grant $packageName android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; " +
                        "pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null",
                    asRoot = false,
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
        if (!_backendState.value.isPrivileged) {
            ensurePrivilegedAccess(interactive = true)
        }

        val wrappedScript = buildAndroidEnvScript(command)
        val clampedTimeout = timeoutMs.coerceIn(500L, MAX_TIMEOUT_MS)

        when (_backendState.value) {
            HostBackendType.SUI_ROOT -> {
                if (asRoot) {
                    executeViaShizuku(
                        cmdArgs = arrayOf("/system/bin/sh", "-c", wrappedScript),
                        timeoutMs = clampedTimeout,
                        maxStdoutBytes = maxStdoutBytes,
                    ).copy(effectiveIdentity = "sui-root (uid=0)")
                } else {
                    val dropPath = getVerifiedShellDropViaShizuku()
                    if (dropPath != null) {
                        executeViaShizuku(
                            cmdArgs = arrayOf(dropPath, "-c", wrappedScript),
                            timeoutMs = clampedTimeout,
                            maxStdoutBytes = maxStdoutBytes,
                        ).copy(effectiveIdentity = "sui-shell (uid=2000)")
                    } else {
                        executeViaShizuku(
                            cmdArgs = arrayOf("/system/bin/sh", "-c", wrappedScript),
                            timeoutMs = clampedTimeout,
                            maxStdoutBytes = maxStdoutBytes,
                        ).copy(effectiveIdentity = "sui (uid=0-fallback)")
                    }
                }
            }

            HostBackendType.SHIZUKU_SHELL -> {
                if (asRoot) {
                    // 纯 ADB Shizuku 本身只有 UID 2000；若设备同时有 su，允许显式 root=true 请求回退 su 提权
                    if (hasSuBinaryOnDevice() && probeSuAccessInternal()) {
                        val su = suBinaryPath!!
                        runLocalProcess(
                            commandArgs = listOf(su, "-c", wrappedScript),
                            timeoutMs = clampedTimeout,
                            maxStdoutBytes = maxStdoutBytes,
                            killAsSu = true,
                        ).copy(effectiveIdentity = "su-root (uid=0)")
                    } else {
                        HostShellResult(
                            exitCode = 126,
                            stdoutBytes = ByteArray(0),
                            stderrBytes = "Current backend is Shizuku (UID 2000 ADB shell); UID 0 root commands are unavailable without Root/Sui."
                                .toByteArray(Charsets.UTF_8),
                            effectiveIdentity = "shizuku-shell (uid=2000)",
                        )
                    }
                } else {
                    executeViaShizuku(
                        cmdArgs = arrayOf("/system/bin/sh", "-c", wrappedScript),
                        timeoutMs = clampedTimeout,
                        maxStdoutBytes = maxStdoutBytes,
                    ).copy(effectiveIdentity = "shizuku-shell (uid=2000)")
                }
            }

            HostBackendType.SU_ROOT -> {
                val su = suBinaryPath
                if (su != null) {
                    val args: List<String>
                    val label: String
                    if (asRoot) {
                        args = listOf(su, "-c", wrappedScript)
                        label = "su-root (uid=0)"
                    } else if (supportsSuUid2000 == true) {
                        args = listOf(su, "2000", "-c", wrappedScript)
                        label = "su-shell (uid=2000)"
                    } else if (shellDropBinary != null) {
                        args = listOf(su, "-c", "${shellDropBinary!!.absolutePath} -c ${shellSingleQuote(wrappedScript)}")
                        label = "su-shell (uid=2000)"
                    } else {
                        args = listOf(su, "-c", wrappedScript)
                        label = "su-root-fallback (uid=0)"
                    }
                    runLocalProcess(
                        commandArgs = args,
                        timeoutMs = clampedTimeout,
                        maxStdoutBytes = maxStdoutBytes,
                        killAsSu = true,
                    ).copy(effectiveIdentity = label)
                } else {
                    executeUnprivileged(wrappedScript, asRoot, clampedTimeout, maxStdoutBytes)
                }
            }

            HostBackendType.UNKNOWN, HostBackendType.UNPRIVILEGED -> {
                executeUnprivileged(wrappedScript, asRoot, clampedTimeout, maxStdoutBytes)
            }
        }
    }

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
        return runLocalProcess(
            commandArgs = listOf(sh, "-c", wrappedScript),
            timeoutMs = timeoutMs,
            maxStdoutBytes = maxStdoutBytes,
            killAsSu = false,
        ).copy(effectiveIdentity = "app-sandbox")
    }

    private fun registerShizukuListenersOnce() {
        if (!listenersRegistered.compareAndSet(false, true)) return
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                if (isShizukuBinderReadyAndGranted()) {
                    updateBackendState(resolveGrantedShizukuType())
                }
            }
            Shizuku.addBinderDeadListener {
                if (_backendState.value.usesShizukuBinder) {
                    if (suBinaryPath != null) {
                        updateBackendState(HostBackendType.SU_ROOT)
                    } else {
                        updateBackendState(HostBackendType.UNKNOWN)
                    }
                }
            }
        }
    }

    private fun updateBackendState(newState: HostBackendType) {
        val prev = _backendState.value
        _backendState.value = newState
        if (prev != newState) {
            onBackendChangedCallback?.invoke(newState)
        }
    }

    private fun isShizukuBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun isShizukuBinderReadyAndGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun resolveGrantedShizukuType(): HostBackendType = runCatching {
        if (Shizuku.getUid() == 0) HostBackendType.SUI_ROOT else HostBackendType.SHIZUKU_SHELL
    }.getOrDefault(HostBackendType.SHIZUKU_SHELL)

    private suspend fun requestShizukuPermissionSuspend(): Boolean {
        if (!isShizukuBinderAlive()) return false
        if (isShizukuBinderReadyAndGranted()) return true

        val reqCode = permissionReqSeq.incrementAndGet()
        return withTimeoutOrNull(25_000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode == reqCode) {
                            runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                            if (cont.isActive) {
                                cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                            }
                        }
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                }
                runCatching {
                    Shizuku.addRequestPermissionResultListener(listener)
                    Shizuku.requestPermission(reqCode)
                }.onFailure {
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    private suspend fun getVerifiedShellDropViaShizuku(): String? {
        if (shellDropProbed) return verifiedShellDropPath
        val bin = shellDropBinary ?: run {
            shellDropProbed = true
            return null
        }

        // 优先直接执行 nativeLibraryDir 下的 libshelldrop.so
        val directProbe = executeViaShizuku(
            cmdArgs = arrayOf(bin.absolutePath, "-c", "id -u"),
            timeoutMs = 3_000L,
            maxStdoutBytes = 256,
        )
        if (!directProbe.timedOut && directProbe.exitCode == 0 && directProbe.stdout.trim() == "2000") {
            verifiedShellDropPath = bin.absolutePath
            shellDropProbed = true
            return verifiedShellDropPath
        }

        // 个别严格 SELinux 策略限制执行 apk_data_file 时，自动拷贝至 /data/local/tmp 执行
        val stagedPath = "/data/local/tmp/.rikkahub_shelldrop_${context?.packageName.orEmpty().hashCode().toUInt()}"
        val stageProbe = executeViaShizuku(
            cmdArgs = arrayOf(
                "/system/bin/sh",
                "-c",
                "cp ${shellSingleQuote(bin.absolutePath)} $stagedPath && chmod 755 $stagedPath && $stagedPath -c 'id -u'",
            ),
            timeoutMs = 4_000L,
            maxStdoutBytes = 256,
        )
        if (!stageProbe.timedOut && stageProbe.exitCode == 0 && stageProbe.stdout.trim() == "2000") {
            verifiedShellDropPath = stagedPath
            shellDropProbed = true
            return verifiedShellDropPath
        }

        Log.w(TAG, "libshelldrop probe failed (exit=${stageProbe.exitCode}, err=${stageProbe.stderr})")
        shellDropProbed = true
        return null
    }

    private suspend fun executeViaShizuku(
        cmdArgs: Array<String>,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): HostShellResult = runInterruptible(Dispatchers.IO) {
        val binder = Shizuku.getBinder()
            ?: return@runInterruptible HostShellResult(
                exitCode = 127,
                stdoutBytes = ByteArray(0),
                stderrBytes = "Shizuku/Sui binder disconnected".toByteArray(Charsets.UTF_8),
            )

        val remote: IRemoteProcess = try {
            IShizukuService.Stub.asInterface(binder).newProcess(cmdArgs, null, null)
        } catch (e: Throwable) {
            return@runInterruptible HostShellResult(
                exitCode = 127,
                stdoutBytes = ByteArray(0),
                stderrBytes = (e.message ?: "IShizukuService.newProcess failed").toByteArray(Charsets.UTF_8),
            )
        }

        // 立即关闭远程进程 stdin，避免交互命令阻塞等待输入
        runCatching {
            remote.outputStream?.let { ParcelFileDescriptor.AutoCloseOutputStream(it).close() }
        }

        val stdoutStream = runCatching {
            remote.inputStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        }.getOrNull() ?: InputStream.nullInputStream()

        val stderrStream = runCatching {
            remote.errorStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        }.getOrNull() ?: InputStream.nullInputStream()

        val stdoutCollector = ByteStreamCollector(stdoutStream, maxStdoutBytes)
        val stderrCollector = ByteStreamCollector(stderrStream, MAX_STDERR_BYTES)

        try {
            val finished = remote.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.name)
            if (!finished) {
                runCatching { remote.destroy() }
            }
            stdoutCollector.join(1_000)
            stderrCollector.join(1_000)
            val code = if (finished) runCatching { remote.exitValue() }.getOrDefault(1) else 124
            HostShellResult(
                exitCode = code,
                stdoutBytes = stdoutCollector.bytes(),
                stderrBytes = stderrCollector.bytes(),
                timedOut = !finished,
                truncated = stdoutCollector.truncated || stderrCollector.truncated,
            )
        } catch (e: InterruptedException) {
            runCatching { remote.destroy() }
            stdoutCollector.join(500)
            stderrCollector.join(500)
            throw e
        } catch (e: Throwable) {
            runCatching { remote.destroy() }
            HostShellResult(
                exitCode = 127,
                stdoutBytes = stdoutCollector.bytes(),
                stderrBytes = (e.message ?: "Shizuku process error").toByteArray(Charsets.UTF_8),
            )
        }
    }

    private suspend fun probeSuAccessInternal(): Boolean = withContext(Dispatchers.IO) {
        val candidate = findSuBinary() ?: return@withContext false
        val probe = runLocalProcess(
            commandArgs = listOf(candidate, "-c", "id -u"),
            timeoutMs = 15_000L,
            maxStdoutBytes = 512,
        )
        val granted = !probe.timedOut && probe.exitCode == 0 && probe.stdout.trim() == "0"
        if (granted) {
            suBinaryPath = candidate
            val shellProbe = runLocalProcess(
                commandArgs = listOf(candidate, "2000", "-c", "id -u"),
                timeoutMs = 5_000L,
                maxStdoutBytes = 512,
            )
            supportsSuUid2000 = !shellProbe.timedOut && shellProbe.exitCode == 0 && shellProbe.stdout.trim() == "2000"
            runCatching {
                suAuthorizedMarker?.apply {
                    parentFile?.mkdirs()
                    writeText("1")
                }
            }
        }
        granted
    }

    fun hasSuBinaryOnDevice(): Boolean = findSuBinary() != null

    private fun buildAndroidEnvScript(userCommand: String): String = buildString {
        append("export PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:\$PATH; ")
        append("export ANDROID_ROOT=\${ANDROID_ROOT:-/system}; ")
        append("export ANDROID_DATA=\${ANDROID_DATA:-/data}; ")
        append("export ANDROID_STORAGE=\${ANDROID_STORAGE:-/storage}; ")
        append(userCommand)
    }

    private suspend fun runLocalProcess(
        commandArgs: List<String>,
        timeoutMs: Long,
        maxStdoutBytes: Int,
        killAsSu: Boolean = false,
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

        runCatching { process.outputStream.close() }

        val stdoutCollector = ByteStreamCollector(process.inputStream, maxStdoutBytes)
        val stderrCollector = ByteStreamCollector(process.errorStream, MAX_STDERR_BYTES)

        fun forceKillTree() {
            val pid = getProcessPid(process)
            process.destroyForcibly()
            if (killAsSu && pid != null && suBinaryPath != null) {
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
