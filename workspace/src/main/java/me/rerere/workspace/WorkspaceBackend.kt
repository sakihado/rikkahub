package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths

/**
 * 工作区在执行环境内的目录约定, 本地与远程共用。
 *
 * 所有目录路径都是环境内的绝对路径, 不带末尾斜杠 (`/` 本身除外)。
 */
data class WorkspaceLayout(
    /** 工作区文件区在环境内的挂载点, 本地为 `/workspace`, 远程为用户填写的远端目录 */
    val workspaceDir: String,
    /** AI 调用写入/编辑工具时免强制审批的目录列表 */
    val writableRoots: List<String>,
    /** 环境中严格只读的挂载点列表, AI 写入/编辑工具在执行前直接硬拒绝 (不弹审批框) */
    val readOnlyRoots: List<String> = emptyList(),
) {
    /**
     * 先做纯词法路径归一化（折叠 `.` 与 `..`），消除 `/skills/../upload/x` 等目录穿越导致的权限误判。
     */
    fun normalizeAbsolutePath(path: String): String? {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/")) return null
        return runCatching {
            Paths.get(trimmed).normalize().toString().let { if (it.isEmpty()) "/" else it }
        }.getOrNull()
    }

    /** [path] 是否落在免强制审批的可写目录内（且未命中更具体的只读子挂载） */
    fun isWritable(path: String): Boolean {
        val normalized = normalizeAbsolutePath(path) ?: return false
        val matchedWritable = writableRoots
            .filter { normalized.matchesRootPrefix(it) }
            .maxByOrNull { it.length }
            ?: return false
        val matchedReadOnly = readOnlyRoots
            .filter { normalized.matchesRootPrefix(it) }
            .maxByOrNull { it.length }
        return matchedReadOnly == null || matchedWritable.length > matchedReadOnly.length
    }

    /** [path] 是否落在严格只读的挂载目录内（命中时文件写入/编辑工具直接报错拒绝） */
    fun isReadOnly(path: String): Boolean {
        val normalized = normalizeAbsolutePath(path) ?: return false
        val matchedReadOnly = readOnlyRoots
            .filter { normalized.matchesRootPrefix(it) }
            .maxByOrNull { it.length }
            ?: return false
        val matchedWritable = writableRoots
            .filter { normalized.matchesRootPrefix(it) }
            .maxByOrNull { it.length }
        return matchedWritable == null || matchedReadOnly.length >= matchedWritable.length
    }

    private fun String.matchesRootPrefix(root: String): Boolean {
        val cleanRoot = if (root == "/") "/" else root.trimEnd('/')
        if (cleanRoot == "/") return startsWith("/")
        return this == cleanRoot || startsWith("$cleanRoot/")
    }

    /** 存储区内的相对路径转成环境内的绝对路径 */
    fun absolutePath(area: WorkspaceStorageArea, relativePath: String): String {
        val rel = relativePath.trim().trim('/')
        return when (area) {
            WorkspaceStorageArea.FILES -> if (rel.isEmpty()) workspaceDir else "${workspaceDir.trimEnd('/')}/$rel"
            WorkspaceStorageArea.LINUX -> if (rel.isEmpty()) "/" else "/$rel"
        }
    }

    /** 把可能带 [workspaceDir] 前缀的路径转成相对于工作区文件区的路径 */
    fun relativeToWorkspace(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        val root = workspaceDir.trimEnd('/')
        return when {
            trimmed.isEmpty() || trimmed == root -> ""
            root.isNotEmpty() && trimmed.startsWith("$root/") -> trimmed.removePrefix("$root/")
            else -> trimmed.trimStart('/')
        }
    }
}

/**
 * 工作区文件与命令执行的统一后端, 屏蔽本地 PRoot 与远程 SSH 的差异。
 *
 * 所有方法均为同步阻塞调用, 由调用方切到 IO 调度器。
 */
interface WorkspaceBackend {
    val layout: WorkspaceLayout

    // ---- 按存储区与相对路径的文件操作 (界面文件浏览与导入导出使用) ----

    fun listFiles(area: WorkspaceStorageArea, path: String = ""): List<WorkspaceFileEntry>

    /** 读取工作区文件区 ([WorkspaceStorageArea.FILES]) 下的 UTF-8 文本 */
    fun readText(path: String): String

    /** 写入工作区文件区 ([WorkspaceStorageArea.FILES]) 下的 UTF-8 文本 */
    fun writeText(path: String, text: String, overwrite: Boolean = true): WorkspaceFileEntry

    fun fileSize(area: WorkspaceStorageArea, path: String): Long

    fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry

    fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream)

    fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean = false): Boolean

    /** 宿主机上可直接交给 Coil 等本地库读取的 File; 远程后端返回 null */
    fun localFile(area: WorkspaceStorageArea, path: String): File? = null

    // ---- 按环境内绝对路径的文件操作 (AI 工具使用) ----

    fun absoluteFileSize(path: String): Long

    fun exportAbsoluteFile(path: String, outputStream: OutputStream)

    fun writeAbsoluteText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry

    /** 宿主机上对应的 File; 远程后端返回 null */
    fun localAbsoluteFile(path: String): File? = null

    // ---- 命令执行 ----

    /** 执行命令实际使用的 shell 路径 (如 `/bin/bash`), 供提示词说明 */
    fun shell(): String

    /**
     * 执行单条命令。
     *
     * @param cwd 相对于 [WorkspaceLayout.workspaceDir] 的子目录, 空串表示工作区根目录
     */
    fun executeCommand(
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult

    /** 在后台启动命令并立即返回, 见 [WorkspaceBackgroundCommand] */
    fun startBackgroundCommand(command: String, cwd: String, id: String): WorkspaceBackgroundCommand
}
