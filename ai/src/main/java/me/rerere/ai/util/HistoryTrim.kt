package me.rerere.ai.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.HistoryTrimMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import kotlin.uuid.Uuid

/** 低于该字节数的工具输出本身已经足够短，裁剪收益微乎其微，原样保留 */
const val MIN_TRIMMABLE_TOOL_OUTPUT_BYTES = 512L

private const val ASK_USER_TOOL_NAME = "ask_user"

/** 单个可裁剪工具/搜索步骤的体积信息，供「裁剪历史」统计与预览使用 */
data class TrimmableStepSize(
    val messageId: Uuid,
    val messageIndex: Int,
    val partIndex: Int,
    /** 工具调用 id：自动裁剪按它精确定位要标记的部件，不依赖下标 */
    val toolCallId: String,
    val toolName: String,
    /** 工具输出原始体积（字节） */
    val bytes: Long,
    /** 裁剪替换为占位文本后可节省的净体积（字节） */
    val savedBytes: Long,
    val trimmed: Boolean,
)

/** 当前上下文窗口内工具/搜索输出的体积汇总 */
data class HistoryTrimStats(
    /** 未裁剪的工具/搜索输出总字节（加上已裁剪项的占位开销） */
    val activeBytes: Long,
    val steps: List<TrimmableStepSize>,
) {
    val totalCount: Int get() = steps.size
    val trimmedCount: Int get() = steps.count { it.trimmed }

    companion object {
        val Empty = HistoryTrimStats(0L, emptyList())
    }
}

fun UIMessagePart.isHistoryTrimmed(): Boolean =
    metadataAs<HistoryTrimMetadata>()?.trimmed == true

/**
 * 执行后可能留下副作用（改动文件、执行命令、写入记忆、发送通知等）的工具。
 *
 * 这类步骤的占位文本不能提示模型“重新调用工具读取”，否则模型为了确认输出会把命令再跑一遍。
 */
private val SIDE_EFFECT_TOOL_NAMES = setOf(
    "workspace_shell",
    "workspace_shell_kill",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_export_file",
    "android_shell",
    "phone_use",
    "eval_javascript",
    "clipboard_tool",
    "text_to_speech",
    "calendar_create",
    "memory_write",
    "memory_str_replace",
    "memory_append",
    "memory_delete",
)

/** 该步骤对应的工具执行后是否可能留下副作用 */
fun UIMessagePart.hasSideEffectTool(): Boolean {
    val name = when (this) {
        is UIMessagePart.Tool -> toolName
        is UIMessagePart.ServerTool -> toolName
        else -> ""
    }
    // MCP 工具无法按名字判断，一律按有副作用处理
    return name in SIDE_EFFECT_TOOL_NAMES || name.startsWith("mcp__")
}

/**
 * 列出这些消息中体积达到裁剪门槛的工具调用与服务端搜索步骤（按旧 → 新排序）。
 */
suspend fun List<UIMessage>.inspectHistoryTrim(provider: ProviderSetting?): HistoryTrimStats =
    withContext(Dispatchers.IO) {
        val steps = mutableListOf<TrimmableStepSize>()
        var activeBytes = 0L
        val messages = this@inspectHistoryTrim

        for (messageIndex in messages.indices) {
            val message = messages[messageIndex]
            for (partIndex in message.parts.indices) {
                val part = message.parts[partIndex]
                when (part) {
                    is UIMessagePart.Tool -> {
                        if (!part.canTrimClientTool()) continue
                        val rawBytes = part.estimateToolOutputBytes(provider)
                        val placeholder = part.trimmedPlaceholderBytes()
                        val trimmed = part.isHistoryTrimmed()
                        if (!trimmed && rawBytes < MIN_TRIMMABLE_TOOL_OUTPUT_BYTES) {
                            activeBytes += rawBytes
                            continue
                        }
                        val effectiveRaw = if (trimmed) {
                            part.metadataAs<HistoryTrimMetadata>()?.trimmedBytes?.takeIf { it > 0 } ?: rawBytes
                        } else {
                            rawBytes
                        }
                        val saved = (effectiveRaw - placeholder).coerceAtLeast(0L)
                        activeBytes += if (trimmed) placeholder else rawBytes
                        steps += TrimmableStepSize(
                            messageId = message.id,
                            messageIndex = messageIndex,
                            partIndex = partIndex,
                            toolCallId = part.toolCallId,
                            toolName = part.toolName,
                            bytes = effectiveRaw,
                            savedBytes = saved,
                            trimmed = trimmed,
                        )
                    }

                    is UIMessagePart.ServerTool -> {
                        if (!part.isFinished || !part.canTrimServerTool()) continue
                        val rawBytes = part.estimateServerToolBytes()
                        val placeholder = part.trimmedPlaceholderBytes()
                        val trimmed = part.isHistoryTrimmed()
                        if (!trimmed && rawBytes < MIN_TRIMMABLE_TOOL_OUTPUT_BYTES) {
                            activeBytes += rawBytes
                            continue
                        }
                        val effectiveRaw = if (trimmed) {
                            part.metadataAs<HistoryTrimMetadata>()?.trimmedBytes?.takeIf { it > 0 } ?: rawBytes
                        } else {
                            rawBytes
                        }
                        val saved = (effectiveRaw - placeholder).coerceAtLeast(0L)
                        activeBytes += if (trimmed) placeholder else rawBytes
                        steps += TrimmableStepSize(
                            messageId = message.id,
                            messageIndex = messageIndex,
                            partIndex = partIndex,
                            toolCallId = part.toolCallId,
                            toolName = part.toolName.ifBlank { "server_tool" },
                            bytes = effectiveRaw,
                            savedBytes = saved,
                            trimmed = trimmed,
                        )
                    }

                    else -> Unit
                }
            }
        }

        HistoryTrimStats(activeBytes = activeBytes, steps = steps)
    }

/**
 * 判断客户端工具调用输出是否允许被「裁剪历史」折叠。
 *
 * 仅对真正执行过的常规工具调用（[ToolApprovalState.Auto] / [ToolApprovalState.Approved]）生效：
 * - 排除 `ask_user` 及 [ToolApprovalState.Answered]：其输出是用户填写的回答，属于不可重新读取的对话输入，
 *   且界面上走独立问答卡片，没有恢复入口；
 * - 排除 [ToolApprovalState.Denied]：其输出是用户拒绝执行及附带的指导原因，替换成“请重新调用工具”会误导模型再次触发被拒操作。
 */
fun UIMessagePart.Tool.canTrimClientTool(): Boolean {
    if (!isExecuted) return false
    if (toolName == ASK_USER_TOOL_NAME) return false
    return when (approvalState) {
        ToolApprovalState.Auto,
        ToolApprovalState.Approved -> true
        ToolApprovalState.Pending,
        is ToolApprovalState.Answered,
        is ToolApprovalState.Denied -> false
    }
}

/**
 * 判断服务端工具是否允许在跨轮次时整块替换为文本占位。
 *
 * - Claude 的 `web_search` 等服务端工具结果常携带大段 `encrypted_content`，不能局部篡改，
 *   但历史已完成轮次整块替换为普通文本在协议上是合法的。
 * - Google Interactions / GenerateContent 的服务端工具可能参与签名链或体积很小，跳过处理。
 */
fun UIMessagePart.ServerTool.canTrimServerTool(): Boolean {
    if (!isFinished) return false
    val protocol = metadataAs<ServerToolMetadata>()?.protocol
    return protocol == null ||
        protocol == ServerToolProtocol.ANTHROPIC_MESSAGES ||
        protocol == ServerToolProtocol.OPENAI_RESPONSES
}

suspend fun UIMessagePart.Tool.estimateToolOutputBytes(provider: ProviderSetting?): Long =
    output.sumOf { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.toByteArray().size.toLong()
            is UIMessagePart.Image,
            is UIMessagePart.Video,
            is UIMessagePart.Audio,
            is UIMessagePart.Document -> {
                if (part.isOmitted()) placeholderBytes(part)
                else part.estimateAttachmentBytes(provider)
            }

            else -> 0L
        }
    }

fun UIMessagePart.ServerTool.estimateServerToolBytes(): Long {
    val meta = metadataAs<ServerToolMetadata>()
    var total = 0L
    val callBytes = meta?.call?.toString()?.toByteArray()?.size?.toLong()
        ?: input?.toString()?.toByteArray()?.size?.toLong()
        ?: 0L
    val resultBytes = meta?.result?.toString()?.toByteArray()?.size?.toLong()
        ?: output?.toString()?.toByteArray()?.size?.toLong()
        ?: 0L
    total += callBytes + resultBytes
    return total
}

/** 提取工具入参里的核心标识（如 path / command / query），让占位文本保留操作线索 */
fun UIMessagePart.toolBriefHint(): String {
    val obj: JsonObject = when (this) {
        is UIMessagePart.Tool -> runCatching { inputAsJson().jsonObject }.getOrNull()
        is UIMessagePart.ServerTool -> (input as? JsonObject)
        else -> null
    } ?: return ""

    val candidate = listOf("path", "query", "url", "command", "pattern", "q", "file_path")
        .firstNotNullOfOrNull { key ->
            obj[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
        } ?: return ""

    val singleLine = candidate.replace('\n', ' ').trim()
    return if (singleLine.length > 80) singleLine.take(77) + "..." else singleLine
}

internal fun UIMessagePart.trimmedPlaceholderBytes(): Long {
    val name = when (this) {
        is UIMessagePart.Tool -> toolName
        is UIMessagePart.ServerTool -> toolName
        else -> ""
    }
    return name.toByteArray().size.toLong() + toolBriefHint().toByteArray().size.toLong() + 96L
}
