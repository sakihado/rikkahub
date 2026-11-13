package me.rerere.rikkahub.data.ai.tools

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.rikkahub.service.BackgroundShellTask
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCapabilities
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
const val WORKSPACE_EXPORT_FILE_TOOL_NAME = "workspace_export_file"

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    WORKSPACE_EXPORT_FILE_TOOL_NAME to true,
)

/** 直接写文件的工具, 只在支持 [WorkspaceCapabilities.fileWriteTools] 的工作区提供 */
val WorkspaceFileWriteToolNames: Set<String> = setOf("workspace_write_file", "workspace_edit_file")

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    conversationId: Uuid? = null,
    backgroundShellManager: BackgroundShellManager? = null,
    hostAccess: Boolean = false,
    appContext: Context? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val layout = workspaceRepository.layout(workspaceId, hostAccess) ?: return emptyList()
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.let(layout::relativeToWorkspace)
    // 后台命令按会话归属, 没有会话时不提供
    val background = backgroundShellManager
        ?.takeIf { workspace.capabilities().backgroundShell }
        ?.let { manager -> conversationId?.let { BackgroundShell(manager, it) } }

    return buildList {
        add(createReadFileTool(workspaceId, layout, ::needsApproval, workspaceRepository, hostAccess))
        if (workspace.capabilities().fileWriteTools) {
            add(createWriteFileTool(workspaceId, layout, ::needsApproval, workspaceRepository, hostAccess))
            add(createEditFileTool(workspaceId, layout, ::needsApproval, workspaceRepository, hostAccess))
        }
        add(createShellTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd, background, hostAccess))
        if (background != null) {
            add(createShellOutputTool(background))
            add(createShellKillTool(background))
        }
        if (appContext != null) {
            add(createExportFileTool(appContext, workspaceId, layout, ::needsApproval, workspaceRepository, hostAccess))
        }
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    hostAccess: Boolean,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path, hostAccess)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path, hostAccess)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    hostAccess: Boolean,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { input ->
        // 只读挂载目录无需白弹一次确认框，直接进 execute 立即报错引导改用 workspace_export_file
        if (input.isReadOnlyTarget(layout, "path")) {
            false
        } else {
            needsApproval("workspace_write_file") || input.pathOutsideWritableRoots(layout, "path")
        }
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        rejectIfReadOnlyMount(layout, path)
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, text, overwrite, hostAccess)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    hostAccess: Boolean,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { input ->
        if (input.isReadOnlyTarget(layout, "path")) {
            false
        } else {
            needsApproval("workspace_edit_file") || input.pathOutsideWritableRoots(layout, "path")
        }
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        rejectIfReadOnlyMount(layout, path)
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path, hostAccess)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, result.updated, overwrite = true, hostAccess)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

/**
 * 在不向容器开放 `/sdcard` 写权限的前提下，允许 AI 申请将工作区内生成的产物文件（APK / PDF / ZIP 等）
 * 经用户弹窗确认后由宿主安全流式导出到手机系统的 `Download/RikkaHub` 目录（无论当前会话是否开启 `/sdcard` 只读挂载均可用）。
 */
private fun createExportFileTool(
    appContext: Context,
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    hostAccess: Boolean,
) = Tool(
    name = WORKSPACE_EXPORT_FILE_TOOL_NAME,
    description = """
        Export a file from the workspace to the user's phone Downloads folder (`Download/RikkaHub`).
        Works even when phone storage is not mounted or mounted strictly read-only.
        Requires user approval in the chat UI before writing to device storage.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path of the source file inside the workspace (e.g. ${layout.workspaceDir}/dist/app.apk)")
                })
                put("file_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file name under Download/RikkaHub (plain file name without '/'). Defaults to source file's base name.")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Overwrite if a file with the same name already exists in Download/RikkaHub. Defaults to false (auto-renames with suffix).")
                })
            },
            required = listOf("source_path"),
        )
    },
    needsApproval = { needsApproval(WORKSPACE_EXPORT_FILE_TOOL_NAME) },
    execute = { input ->
        val params = input.jsonObject
        val sourcePath = params.absolutePath("source_path")
        val rawName = params.string("file_name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: sourcePath.substringAfterLast('/')
        val cleanName = sanitizeExportFileName(rawName)
        val overwrite = params["overwrite"]?.jsonPrimitive?.let {
            it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull()
        } ?: false

        // 先校验源文件存在且为普通文件
        val sizeBytes = workspaceRepository.absoluteFileSize(workspaceId, sourcePath, hostAccess)
        val exported = exportWorkspaceStreamToDownloads(
            context = appContext,
            fileName = cleanName,
            overwrite = overwrite,
        ) { outputStream ->
            workspaceRepository.exportAbsoluteFile(workspaceId, sourcePath, outputStream, hostAccess)
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("sourcePath", sourcePath)
                    put("exportedPath", exported.displayPath)
                    put("fileName", exported.finalName)
                    put("sizeBytes", sizeBytes)
                }.toString()
            )
        )
    },
)

private data class ExportedDownloadResult(
    val finalName: String,
    val displayPath: String,
)

internal fun sanitizeExportFileName(raw: String): String {
    require('/' !in raw && '\\' !in raw && '\u0000' !in raw) {
        "file_name must be a plain file name without directory separators: $raw"
    }
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty() && trimmed != "." && trimmed != "..") {
        "Invalid export file_name: $raw"
    }
    return trimmed
}

private suspend fun exportWorkspaceStreamToDownloads(
    context: Context,
    fileName: String,
    overwrite: Boolean,
    writeContent: suspend (java.io.OutputStream) -> Unit,
): ExportedDownloadResult = withContext(Dispatchers.IO) {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

    // 优先在拥有所有文件访问权限（Sui/Root 已开通或用户在系统设置授权）时直接原子写入目标文件，精确可控重名行为
    val canWriteFileDirectly = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Environment.isExternalStorageManager()
    if (canWriteFileDirectly) {
        val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val rikkaDir = File(downloadsRoot, "RikkaHub").apply { mkdirs() }
        if (rikkaDir.isDirectory && rikkaDir.canWrite()) {
            val target = resolveNonCollidingFile(rikkaDir, fileName, overwrite)
            val tmpFile = File(rikkaDir, ".export_${UUID.randomUUID()}.tmp")
            try {
                tmpFile.outputStream().use { out -> writeContent(out) }
                if (overwrite && target.exists()) {
                    target.delete()
                }
                if (!tmpFile.renameTo(target)) {
                    tmpFile.copyTo(target, overwrite = overwrite)
                    tmpFile.delete()
                }
            } finally {
                if (tmpFile.exists()) tmpFile.delete()
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            return@withContext ExportedDownloadResult(
                finalName = target.name,
                displayPath = "/sdcard/Download/RikkaHub/${target.name}",
            )
        }
    }

    // 无全盘存储权限时（例如纯未授权孤岛设备），在 Android 10+ 走免权限的 MediaStore.Downloads
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/RikkaHub/"
        if (overwrite) {
            runCatching {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(relativeDir, fileName),
                )
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create entry in Download/RikkaHub via MediaStore")
        try {
            val out = resolver.openOutputStream(uri)
                ?: error("Failed to open output stream for Download/RikkaHub/$fileName")
            out.use { writeContent(it) }
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
            val resolvedName = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: fileName
            return@withContext ExportedDownloadResult(
                finalName = resolvedName,
                displayPath = "/sdcard/Download/RikkaHub/$resolvedName",
            )
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    error("Storage permission is required to export files to Download/RikkaHub on Android 9 and below.")
}

private fun resolveNonCollidingFile(dir: File, fileName: String, overwrite: Boolean): File {
    val direct = File(dir, fileName)
    if (overwrite || !direct.exists()) return direct
    val dotIdx = fileName.lastIndexOf('.')
    val stem = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
    val suffix = if (dotIdx > 0) fileName.substring(dotIdx) else ""
    return generateSequence(1) { it + 1 }
        .map { File(dir, "$stem ($it)$suffix") }
        .first { !it.exists() }
}

private class BackgroundShell(val manager: BackgroundShellManager, val conversationId: Uuid)

private fun createShellTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    background: BackgroundShell? = null,
    hostAccess: Boolean = false,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append(
            "Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at ${layout.workspaceDir}. "
        )
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        if (background != null) {
            append(
                "Set run_in_background for long-running commands such as dev servers, watchers or long builds; " +
                    "processes left running with `&` are killed when the command returns. "
            )
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        buildString {
                            append("Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS.")
                            if (background != null) append(" Ignored when running in the background.")
                        }
                    )
                })
                if (background != null) {
                    put("run_in_background", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "Start the command in the background and return a task id immediately instead of waiting for it. " +
                                "You will be notified with its exit code and the last lines of its output when it finishes, so do not poll it. " +
                                "Use workspace_shell_output to check on it earlier and workspace_shell_kill to stop it. Defaults to false."
                        )
                    })
                }
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = layout.relativeToWorkspace(params.string("cwd") ?: defaultCwd.orEmpty())
        val runInBackground = params.string("run_in_background")?.toBooleanStrictOrNull() ?: false
        val output = if (runInBackground && background != null) {
            background.manager.start(background.conversationId, workspaceId, command, cwd, hostAccess).toJson()
        } else {
            val timeoutMillis = params.string("timeout")?.toLongOrNull()
                ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
                ?.times(1_000L)
                ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
            val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis, hostAccess = hostAccess)
            buildJsonObject {
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("timedOut", result.timedOut)
                if (result.truncated) put("truncated", true)
            }
        }
        listOf(UIMessagePart.Text(output.toString()))
    },
)

private fun createShellOutputTool(background: BackgroundShell) = Tool(
    name = "workspace_shell_output",
    description = """
        Read the output of a background command started by workspace_shell with run_in_background.
        Returns the combined stdout and stderr produced since the previous read, the command status,
        and the exit code once it has finished. You are notified automatically when a background command
        finishes, so only use this when you need its output before then, such as checking that a server started.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell")
                })
                put("wait", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Seconds to wait for the command to finish before returning. Defaults to 0 (return immediately), max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("task_id"),
        )
    },
    execute = {
        val params = it.jsonObject
        val taskId = params.string("task_id") ?: error("task_id is required")
        val waitMillis = (params.string("wait")?.toLongOrNull() ?: 0L)
            .coerceIn(0L, SHELL_TIMEOUT_MAX_SECONDS) * 1_000L
        val result = background.manager.readOutput(background.conversationId, taskId, waitMillis)
        listOf(
            UIMessagePart.Text(
                result.task.toJson {
                    put("output", result.output)
                    if (result.skippedBytes > 0) {
                        put("skippedBytes", result.skippedBytes)
                        put("note", "Earlier output was omitted. The full log is at ${result.task.logPath}.")
                    }
                }.toString()
            )
        )
    },
)

private fun createShellKillTool(background: BackgroundShell) = Tool(
    name = "workspace_shell_kill",
    description = "Stop a background command started by workspace_shell with run_in_background, together with every process it started.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell")
                })
            },
            required = listOf("task_id"),
        )
    },
    execute = {
        val taskId = it.jsonObject.string("task_id") ?: error("task_id is required")
        val task = background.manager.kill(background.conversationId, taskId)
        listOf(UIMessagePart.Text(task.toJson().toString()))
    },
)

private fun BackgroundShellTask.toJson(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("taskId", id)
    put("status", status.name.lowercase())
    exitCode?.let { put("exitCode", it) }
    put("logPath", logPath)
    extra()
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
    hostAccess: Boolean,
): String = readRootfsBuffer(workspaceId, path, hostAccess).toString(Charsets.UTF_8.name())

/**
 * 按环境内绝对路径读入内存。路径映射交给工作区后端, 本地工作区由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
    hostAccess: Boolean,
): ByteArrayOutputStream {
    val size = absoluteFileSize(workspaceId, path, hostAccess)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportAbsoluteFile(workspaceId, path, it, hostAccess) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
    hostAccess: Boolean,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path, hostAccess).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

private fun rejectIfReadOnlyMount(layout: WorkspaceLayout, path: String) {
    require(!layout.isReadOnly(path)) {
        "Path is mounted strictly read-only: $path. Write files inside ${layout.workspaceDir} instead, and call $WORKSPACE_EXPORT_FILE_TOOL_NAME if you want to export a file to the user's Download/RikkaHub folder."
    }
}

private fun kotlinx.serialization.json.JsonElement.isReadOnlyTarget(
    layout: WorkspaceLayout,
    name: String,
): Boolean = runCatching {
    layout.isReadOnly(jsonObject.absolutePath(name))
}.getOrDefault(false)

// 免强制审批的可写安全区由工作区后端给出, 本地为工作区文件目录、临时目录和技能目录
private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    layout: WorkspaceLayout,
    name: String,
): Boolean =
    runCatching {
        !layout.isWritable(jsonObject.absolutePath(name))
    }.getOrDefault(true)

private fun JsonObjectBuilder.putPathProperty(layout: WorkspaceLayout, required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
