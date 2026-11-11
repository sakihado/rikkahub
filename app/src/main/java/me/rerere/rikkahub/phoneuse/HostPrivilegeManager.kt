package me.rerere.rikkahub.phoneuse

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "HostPrivilegeManager"

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

/**
 * 宿主机特权连接、授权协商与就绪生命周期管理器（单一职责，与底层进程执行器 [HostShellExecutor] 和会话审批 [ShellApprovalStore] 解耦）。
 *
 * - 统一管理 Shizuku / Sui Binder 监听、按需单次弹窗授权与传统 `su` 探测回退；
 * - 提供单一响应式就绪管道：无论从冷启动静默恢复、Sui 动态重启、设置页开关还是首次工具调用获得权限，
 *   均集中完成存储权限开通与 [PhoneUseManager] 特权控制器挂载，消除多入口重复编排。
 */
class HostPrivilegeManager(
    private val context: Context? = null,
) {
    private val _backendState = MutableStateFlow(HostBackendType.UNKNOWN)
    val backendState: StateFlow<HostBackendType> = _backendState.asStateFlow()

    private val authMutex = Mutex()
    private val listenersRegistered = AtomicBoolean(false)
    private val permissionReqSeq = AtomicInteger(20260)

    @Volatile
    var suBinaryPath: String? = null
        private set

    @Volatile
    var supportsSuUid2000: Boolean? = null
        private set

    @Volatile
    private var lifecycleObserver: ((HostBackendType) -> Unit)? = null

    private val suAuthorizedMarker: File?
        get() = context?.filesDir?.let { File(it, "host_bridge/su_authorized") }

    val canUseBinderFastInput: Boolean
        get() = _backendState.value.usesShizukuBinder && isShizukuBinderReadyAndGranted()

    /**
     * 绑定应用级响应式就绪钩子，并在已获授权时静默完成冷启动恢复（绝不在冷启动主动弹窗）。
     */
    suspend fun initializeLifecycle(
        appScope: CoroutineScope,
        phoneUseManager: PhoneUseManager,
        rootPhoneControllerProvider: () -> PhoneController,
        executor: HostShellExecutor,
    ) {
        if (BuildConfig.IS_PLAY_STORE) return
        val pkgName = context?.packageName
        val storageGrantedOnce = AtomicBoolean(false)

        lifecycleObserver = { backend ->
            if (backend.isPrivileged) {
                if (PhoneUseManager.isSupported) {
                    phoneUseManager.attachRoot(rootPhoneControllerProvider())
                }
                if (pkgName != null && storageGrantedOnce.compareAndSet(false, true)) {
                    appScope.launch(Dispatchers.IO) {
                        grantStorageAccessViaShell(pkgName, executor)
                    }
                }
                Log.i(TAG, "Privileged host backend active ($backend)")
            } else {
                storageGrantedOnce.set(false)
                if (PhoneUseManager.isSupported) {
                    phoneUseManager.detachRoot()
                }
            }
        }

        registerShizukuListenersOnce()

        if (pkgName != null && !isShizukuBinderAlive()) {
            runCatching { Sui.init(pkgName) }
        }

        // 1. 若 Sui / Shizuku 在线且之前已点过允许，直接 0 弹窗激活
        if (isShizukuBinderReadyAndGranted()) {
            updateBackendState(resolveGrantedShizukuType())
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
     * 确保特权通道就绪。一旦就绪，[lifecycleObserver] 会自动统一挂载 `RootPhoneController` 并开通存储权限。
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
                updateBackendState(resolveGrantedShizukuType())
                return@withLock true
            }
            if (!interactive) {
                return@withLock false
            }
            val granted = requestShizukuPermissionSuspend()
            return@withLock if (granted && isShizukuBinderReadyAndGranted()) {
                updateBackendState(resolveGrantedShizukuType())
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
        if (probeSuAccessInternal()) {
            updateBackendState(HostBackendType.SU_ROOT)
            return@withLock true
        }
        updateBackendState(HostBackendType.UNPRIVILEGED)
        return@withLock false
    }

    /**
     * 当当前后端为纯 ADB Shizuku (UID 2000) 且命令显式要求 `root=true` 时，按需探测本机是否有可用的 `su`。
     */
    internal suspend fun ensureSuForRootEscalation(): String? {
        suBinaryPath?.let { return it }
        if (!hasSuBinaryOnDevice()) return null
        return if (probeSuAccessInternal()) suBinaryPath else null
    }

    fun hasSuBinaryOnDevice(): Boolean = findSuBinary() != null

    private suspend fun grantStorageAccessViaShell(packageName: String, executor: HostShellExecutor) {
        if (!_backendState.value.isPrivileged) return
        runCatching {
            executor.execute(
                command = "appops set $packageName MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; " +
                    "pm grant $packageName android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; " +
                    "pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null",
                asRoot = false,
                timeoutMs = 5_000L,
                maxStdoutBytes = 512,
            )
        }
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
            lifecycleObserver?.invoke(newState)
        } else if (newState.isPrivileged) {
            // 确保首次连接或重复确认时挂载状态一致
            lifecycleObserver?.invoke(newState)
        }
    }

    private fun isShizukuBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    internal fun isShizukuBinderReadyAndGranted(): Boolean = runCatching {
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

    private suspend fun probeSuAccessInternal(): Boolean = withContext(Dispatchers.IO) {
        val candidate = findSuBinary() ?: return@withContext false
        val probe = runSimpleLocalProbe(listOf(candidate, "-c", "id -u"), 15_000L)
        val granted = probe == "0"
        if (granted) {
            suBinaryPath = candidate
            supportsSuUid2000 = runSimpleLocalProbe(listOf(candidate, "2000", "-c", "id -u"), 5_000L) == "2000"
            runCatching {
                suAuthorizedMarker?.apply {
                    parentFile?.mkdirs()
                    writeText("1")
                }
            }
        }
        granted
    }

    private fun runSimpleLocalProbe(args: List<String>, timeoutMs: Long): String? = runCatching {
        val proc = ProcessBuilder(args).redirectErrorStream(false).start()
        runCatching { proc.outputStream.close() }
        val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return@runCatching null
        }
        if (proc.exitValue() != 0) return@runCatching null
        proc.inputStream.bufferedReader().readText().trim()
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
}
