package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths

/**
 * 单个工作区的执行环境, 屏蔽本地 PRoot 与远程等不同实现。
 *
 * 路径有两套寻址方式:
 * - 存储区 + 相对路径: 应用内文件浏览器使用, 见 [WorkspaceStorageArea]
 * - 环境内绝对路径: AI 工具与 shell 看到的路径, 如 /workspace/notes.md
 *
 * 方法都是阻塞 IO, 调用方负责切到 IO 线程; 取消时会中断调用线程。
 */
interface WorkspaceBackend {
    val layout: WorkspaceLayout

    /** 执行命令实际使用的 shell 路径 */
    fun shell(): String

    fun listFiles(area: WorkspaceStorageArea, path: String): List<WorkspaceFileEntry>

    /** 读取文件区内的文本, 自带大小保护 */
    fun readText(path: String): String

    /** 写入文件区内的文本 */
    fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry

    /** 导入文件, 目标已存在时自动改名 */
    fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry

    fun fileSize(area: WorkspaceStorageArea, path: String): Long

    fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream)

    fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean

    fun absoluteFileSize(path: String): Long

    fun exportAbsoluteFile(path: String, outputStream: OutputStream)

    /** 按环境内绝对路径写入 UTF-8 文本, 父目录不存在时自动创建; 返回的条目 path 为绝对路径 */
    fun writeAbsoluteText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry

    /** 宿主机上可直接访问的文件; 没有本地落点的环境返回 null */
    fun localFile(area: WorkspaceStorageArea, path: String): File?

    /** 按环境内绝对路径解析宿主机上的文件, 不检查是否存在; 没有本地落点的环境返回 null */
    fun localAbsoluteFile(path: String): File?

    /** 执行命令, [cwd] 为相对文件区的路径 */
    fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult

    /** 在后台启动命令并立即返回, [cwd] 为相对文件区的路径; 只在支持 [WorkspaceCapabilities.backgroundShell] 的环境可用 */
    fun startBackgroundCommand(command: String, cwd: String, id: String): WorkspaceBackgroundCommand =
        throw UnsupportedOperationException("Background commands are not supported in this workspace")
}

/** 工作区在执行环境内的目录约定 */
data class WorkspaceLayout(
    /** 文件区在环境内的绝对路径, 也是 shell 的默认工作目录 */
    val workspaceDir: String,
    /** AI 写入时免强制审批的目录 */
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

    /** 存储区内的相对路径转成环境内的绝对路径 */
    fun absolutePath(area: WorkspaceStorageArea, path: String): String {
        val relative = path.trim('/')
        return when (area) {
            WorkspaceStorageArea.FILES -> if (relative.isEmpty()) workspaceDir else "$workspaceDir/$relative"
            WorkspaceStorageArea.LINUX -> "/$relative"
        }
    }

    /** 文件区内的绝对路径转成相对文件区的路径, 不在文件区内时原样返回 */
    fun relativeToWorkspace(path: String): String =
        path.removePrefix("$workspaceDir/").removePrefix(workspaceDir)

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
}
