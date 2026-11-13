package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream

class WorkspaceManager(
    private val baseDir: File,
    private val fileSystem: WorkspaceFileSystem = WorkspaceFileSystem(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val patcher: RootfsPatcher = RootfsPatcher(),
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    /**
     * 按前缀从长到短匹配, 避免 `/skills` 抢先吃掉 `/skills/builtin` 这类更具体的子挂载。
     */
    private val sortedBindMounts: List<WorkspaceBindMount> =
        bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    private fun activeBindMounts(hostAccess: Boolean, sorted: Boolean = false): List<WorkspaceBindMount> {
        val source = if (sorted) sortedBindMounts else bindMounts
        return if (hostAccess) source else source.filterNot { it.sessionHostMount }
    }

    fun layout(hostAccess: Boolean = false): WorkspaceLayout {
        val active = activeBindMounts(hostAccess)
        return WorkspaceLayout(
            workspaceDir = ROOTFS_WORKSPACE_DIR,
            writableRoots = listOf(ROOTFS_WORKSPACE_DIR, "/tmp") +
                active.filter { it.access == MountAccess.WRITABLE_AUTO }.map { it.target.trimEnd('/') },
            readOnlyRoots = active.filter { it.access == MountAccess.READ_ONLY }.map { it.target.trimEnd('/') },
        )
    }

    val layout = layout(hostAccess = false)

    /** 构造指向本地工作区 [root] 的统一后端 */
    fun backend(
        root: String,
        shellCompatibilityMode: Boolean = false,
        hostAccess: Boolean = false,
    ): WorkspaceBackend =
        LocalWorkspaceBackend(this, root, shellCompatibilityMode, hostAccess)

    fun workspaceDir(root: String): File = File(baseDir, root)

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    /**
     * 清理所有工作区遗留的运行期临时目录 (`tmp`).
     *
     * 正常退出时进程会自行清理临时文件, 但应用进程被系统杀死或崩溃时可能残留
     * PRoot 临时文件、命令输出缓冲或构建中间物. 仅在应用启动早期、尚无任何会话运行时调用.
     *
     * 注意这里只清宿主机侧的 PRoot 临时目录, 不动 Rootfs 内的 `linux/tmp`, 那是容器自己的 `/tmp`.
     */
    fun cleanupTempDirs() {
        val workspaces = baseDir.listFiles() ?: return
        for (workspace in workspaces) {
            if (!workspace.isDirectory) continue
            val tmp = File(workspace, TEMP_DIR)
            if (tmp.isDirectory) {
                tmp.listFiles()?.forEach { child ->
                    runCatching { child.deleteRecursively() }
                }
            }
        }
    }

    fun hasRootfs(root: String): Boolean = isUsableRootfs(linuxDir(root))

    /** 执行命令实际使用的 shell 路径 (如 `/bin/bash` 或 `/bin/sh`) */
    fun shell(root: String): String = rootfsShell(linuxDir(root))

    fun ensureWorkspace(root: String) {
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
    }

    fun deleteWorkspace(root: String): Boolean {
        val dir = workspaceDir(root)
        if (!dir.exists()) return false
        // 解压出来的 Rootfs 里可能有只读目录 (mode 0555/0500), 不先加写权限 unlink 子项会 EACCES
        makeTreeWritable(dir)
        return dir.deleteRecursively()
    }

    fun areaRoot(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun listFiles(root: String, area: WorkspaceStorageArea, path: String = ""): List<WorkspaceFileEntry> {
        ensureWorkspace(root)
        return fileSystem.list(areaRoot(root, area), path)
    }

    fun readText(root: String, path: String): String {
        ensureWorkspace(root)
        return fileSystem.readText(filesDir(root), path)
    }

    fun writeText(root: String, path: String, text: String, overwrite: Boolean = true): WorkspaceFileEntry {
        ensureWorkspace(root)
        return fileSystem.writeText(filesDir(root), path, text, overwrite)
    }

    fun importFile(
        root: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        ensureWorkspace(root)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot(root, area), targetPath, inputStream)
    }

    fun exportFile(
        root: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) {
        ensureWorkspace(root)
        val file = fileSystem.resolve(areaRoot(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun fileSize(root: String, area: WorkspaceStorageArea, path: String): Long {
        ensureWorkspace(root)
        val file = fileSystem.resolve(areaRoot(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    /** Rootfs 内绝对路径在宿主机上的落点: 对应的宿主目录和目录内的相对路径 */
    data class RootfsLocation(val rootDir: File, val relativePath: String)

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机目录:
     * 优先匹配 `/workspace` 和当前生效的 [bindMounts], 其余落回 `linuxDir`。
     * 内核伪文件系统 (如 `/proc`) 直接报错引导用 `workspace_shell` 读取。
     */
    fun resolveRootfsPath(root: String, path: String, hostAccess: Boolean = false): RootfsLocation {
        val trimmed = path.trim()
        require(trimmed.startsWith("/")) { "Path must be an absolute path inside Rootfs: $path" }
        trimmed.matchingMount(ROOTFS_WORKSPACE_DIR)?.let { return RootfsLocation(filesDir(root), it) }
        activeBindMounts(hostAccess, sorted = true).forEach { mount ->
            trimmed.matchingMount(mount.target)?.let { return RootfsLocation(mount.source, it) }
        }
        KERNEL_FS_MOUNTS.firstOrNull { trimmed.matchingMount(it) != null }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }
        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    /** 命中挂载点时返回挂载点内的相对路径, 未命中返回 null; 按路径段边界匹配 */
    private fun String.matchingMount(mountPoint: String): String? {
        val mp = mountPoint.trimEnd('/')
        return when {
            this == mp -> ""
            startsWith("$mp/") -> removePrefix("$mp/").trimStart('/')
            else -> null
        }
    }

    fun rootfsFileSize(root: String, path: String, hostAccess: Boolean = false): Long =
        resolveRootfsFile(root, path, hostAccess).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(
        root: String,
        path: String,
        outputStream: OutputStream,
        hostAccess: Boolean = false,
    ) {
        val file = resolveRootfsFile(root, path, hostAccess)
        file.requireReadableFile(path)
        file.inputStream().use { it.copyTo(outputStream) }
    }

    /** 解析 Rootfs 内绝对路径到宿主机上的真实 [File] (会校验不越出所属的挂载根) */
    fun resolveRootfsFile(root: String, path: String, hostAccess: Boolean = false): File {
        val location = resolveRootfsPath(root, path, hostAccess)
        ensureWorkspace(root)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(displayPath: String) {
        require(exists()) { "File does not exist: $displayPath" }
        require(isFile) { "Path is not a file: $displayPath" }
    }

    fun resolveFile(root: String, area: WorkspaceStorageArea, path: String): File {
        ensureWorkspace(root)
        return fileSystem.resolve(areaRoot(root, area), path)
    }

    fun deleteFile(
        root: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean = false,
    ): Boolean {
        ensureWorkspace(root)
        return fileSystem.delete(areaRoot(root, area), path, recursive)
    }

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        shellCompatibilityMode: Boolean = false,
        hostAccess: Boolean = false,
    ): WorkspaceCommandResult =
        shellRunner.execute(shellContext(root, command, cwd, timeoutMillis, stdin, shellCompatibilityMode, hostAccess))

    fun startBackgroundCommand(
        root: String,
        command: String,
        cwd: String,
        id: String,
        shellCompatibilityMode: Boolean = false,
        hostAccess: Boolean = false,
    ): WorkspaceBackgroundCommand =
        shellRunner.startBackground(shellContext(root, command, cwd, 0, null, shellCompatibilityMode, hostAccess), id)

    private fun shellContext(
        root: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        shellCompatibilityMode: Boolean,
        hostAccess: Boolean,
    ): WorkspaceShellContext {
        ensureWorkspace(root)
        val files = filesDir(root)
        val workingDir = if (cwd.isBlank()) files else fileSystem.resolve(files, cwd)
        require(workingDir.exists() && workingDir.isDirectory) {
            "Working directory does not exist: $cwd"
        }
        return WorkspaceShellContext(
            root = root,
            command = command,
            cwd = cwd,
            filesDir = files,
            linuxDir = linuxDir(root),
            tempDir = tempDir(root),
            workingDir = workingDir,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            bindMounts = activeBindMounts(hostAccess),
            shellCompatibilityMode = shellCompatibilityMode,
        )
    }

    /** 交互式终端启动前的磁盘准备, 避免放到主线程做 I/O */
    fun prepareTerminal(root: String, nameservers: List<String>, hostAccess: Boolean = false) {
        ensureWorkspace(root)
        activeBindMounts(hostAccess).forEach { mount ->
            if (!mount.sessionHostMount) mount.source.mkdirs()
        }
        tempDir(root).mkdirs()
        patcher.patch(linuxDir(root), nameservers)
    }

    /** 构造交互式终端的启动参数, 当前 runner 不支持时返回 null */
    fun terminalLaunch(
        root: String,
        shellCompatibilityMode: Boolean,
        hostAccess: Boolean = false,
    ): WorkspaceTerminalLaunch? =
        shellRunner.terminalLaunch(
            WorkspaceTerminalContext(
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                bindMounts = activeBindMounts(hostAccess),
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )

    companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** 工作区文件区在 Rootfs 内的挂载路径 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** Rootfs 内默认用户的 HOME 目录 */
        const val ROOTFS_HOME_DIR = "/root"

        /** 工作区在宿主机应用私有目录下的子目录名 */
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"

        /** 宿主机侧的 PRoot 临时目录名, 只用于 PROOT_TMP_DIR 与安装解压, 不是容器内的 /tmp */
        private const val TEMP_DIR = "tmp"

        /** PRoot 运行时绑定进 Rootfs 的宿主机内核/设备伪文件系统 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        /**
         * 判断 [linuxDir] 里装的是不是一个能启动的 Rootfs:
         * 兼容 usr-merge (`bin` 是指向 `usr/bin` 的符号链接) 与传统目录布局。
         */
        fun isUsableRootfs(linuxDir: File): Boolean =
            ROOTFS_SHELLS.any { File(linuxDir, it.trimStart('/')).exists() }

        /** 优先用 bash (Ubuntu/Debian), 没有就退回 POSIX sh (Alpine 等精简镜像) */
        fun rootfsShell(linuxDir: File): String =
            ROOTFS_SHELLS.firstOrNull { File(linuxDir, it.trimStart('/')).exists() } ?: "/bin/sh"

        private val ROOTFS_SHELLS = listOf("/bin/bash", "/usr/bin/bash", "/bin/sh", "/usr/bin/sh")

        private fun makeTreeWritable(file: File) {
            // 跳过符号链接: Java 的 setWritable 会跟随链接改到目标上, 且死链接会失败
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) return
            file.setWritable(true)
            if (file.isDirectory) {
                file.listFiles()?.forEach(::makeTreeWritable)
            }
        }
    }
}
