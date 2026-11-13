package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** [WorkspaceBackend] 的本地实现, 委托给 [WorkspaceManager] 与 PRoot */
internal class LocalWorkspaceBackend(
    private val manager: WorkspaceManager,
    private val root: String,
    private val shellCompatibilityMode: Boolean = false,
    private val hostAccess: Boolean = false,
) : WorkspaceBackend {
    override val layout: WorkspaceLayout = manager.layout(hostAccess)

    override fun listFiles(area: WorkspaceStorageArea, path: String): List<WorkspaceFileEntry> =
        manager.listFiles(root, area, path)

    override fun readText(path: String): String = manager.readText(root, path)

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        manager.writeText(root, path, text, overwrite)

    override fun fileSize(area: WorkspaceStorageArea, path: String): Long =
        manager.fileSize(root, area, path)

    override fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = manager.importFile(root, area, destinationPath, fileName, inputStream)

    override fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream) {
        manager.exportFile(root, area, path, outputStream)
    }

    override fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean =
        manager.deleteFile(root, area, path, recursive)

    override fun localFile(area: WorkspaceStorageArea, path: String): File =
        manager.resolveFile(root, area, path)

    override fun absoluteFileSize(path: String): Long {
        if (path == layout.workspaceDir || path.startsWith("${layout.workspaceDir}/")) {
            return manager.fileSize(root, WorkspaceStorageArea.FILES, layout.relativeToWorkspace(path))
        }
        return manager.rootfsFileSize(root, path, hostAccess)
    }

    override fun exportAbsoluteFile(path: String, outputStream: OutputStream) {
        if (path == layout.workspaceDir || path.startsWith("${layout.workspaceDir}/")) {
            manager.exportFile(root, WorkspaceStorageArea.FILES, layout.relativeToWorkspace(path), outputStream)
            return
        }
        manager.exportRootfsFile(root, path, outputStream, hostAccess)
    }

    override fun writeAbsoluteText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        val normalized = layout.normalizeAbsolutePath(path)
            ?: throw IllegalArgumentException("Path must be an absolute path inside Rootfs: $path")
        require(!layout.isReadOnly(normalized)) {
            "Path is mounted read-only: $path. Write inside /workspace instead, or use workspace_export_file to export artifacts to Download/RikkaHub."
        }
        if (normalized == layout.workspaceDir || normalized.startsWith("${layout.workspaceDir}/")) {
            return manager.writeText(root, layout.relativeToWorkspace(normalized), text, overwrite)
        }
        // Rootfs 内部路径经 shell 写入, 保留文件原有的权限位与符号链接行为
        val quoted = normalized.shellQuote()
        val guard = if (overwrite) "" else "if [ -e $quoted ]; then echo 'File already exists' >&2; exit 2; fi; "
        val result = executeCommand(
            command = "${guard}mkdir -p -- \"\$(dirname -- $quoted)\" && cat > $quoted",
            stdin = text.toByteArray(Charsets.UTF_8),
        )
        if (result.exitCode == 2 && !overwrite) {
            throw IllegalStateException("Target file already exists: $path")
        }
        if (result.timedOut) {
            throw IllegalStateException("Timed out writing $path")
        }
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.trim().ifEmpty { "Failed to write $path (exit ${result.exitCode})" })
        }
        return WorkspaceFileEntry(
            path = normalized,
            name = normalized.substringAfterLast('/'),
            isDirectory = false,
            sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    override fun localAbsoluteFile(path: String): File = manager.resolveRootfsFile(root, path, hostAccess)

    override fun shell(): String = manager.shell(root)

    override fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult =
        manager.executeCommand(
            root = root,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            shellCompatibilityMode = shellCompatibilityMode,
            hostAccess = hostAccess,
        )

    override fun startBackgroundCommand(command: String, cwd: String, id: String): WorkspaceBackgroundCommand =
        manager.startBackgroundCommand(root, command, cwd, id, shellCompatibilityMode, hostAccess)
}
