package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.HistoryTrimMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.AutoTrimPlan
import me.rerere.ai.util.MIN_TRIMMABLE_TOOL_OUTPUT_BYTES
import me.rerere.ai.util.canTrimClientTool
import me.rerere.ai.util.canTrimServerTool
import me.rerere.ai.util.estimateServerToolBytes
import me.rerere.ai.util.estimateToolOutputBytes
import me.rerere.ai.util.isHistoryTrimmed

private const val TRIMMED_KEY = "trimmed"
private const val TRIMMED_BYTES_KEY = "trimmed_bytes"

/**
 * 选出「裁剪历史」实际要处理的候选消息列表。
 *
 * 与请求构建（[limitContext] 阶梯式窗口 + 工具边界对齐）及弹窗预览使用同一套上下文窗口，
 * 避免修改已经滑出当前上下文窗口、未计入预览的更早消息。
 */
internal fun List<UIMessage>.selectTrimHistoryCandidates(
    contextMessageLimit: Int,
    keepRecentMessages: Int,
): List<UIMessage> = limitContext(contextMessageLimit)
    .dropLast(keepRecentMessages.coerceAtLeast(0))
    .filterNot { it.isContextCheckpoint }

/** 该消息中是否包含尚未裁剪、且达到最小体积门槛的工具或服务端搜索输出 */
suspend fun UIMessage.hasTrimmableHistory(provider: ProviderSetting?): Boolean {
    for (part in parts) {
        if (part.isHistoryTrimmed()) continue
        when (part) {
            is UIMessagePart.Tool -> {
                if (part.canTrimClientTool() && part.estimateToolOutputBytes(provider) >= MIN_TRIMMABLE_TOOL_OUTPUT_BYTES) {
                    return true
                }
            }

            is UIMessagePart.ServerTool -> {
                if (part.canTrimServerTool() && part.estimateServerToolBytes() >= MIN_TRIMMABLE_TOOL_OUTPUT_BYTES) {
                    return true
                }
            }

            else -> Unit
        }
    }
    return false
}

/**
 * 把这条消息中超过体积门槛的工具调用结果与服务端搜索结果标记为「已裁剪」。
 *
 * 原始输出仍然保留在本地消息部件中，界面上的搜索引用角标、图表卡片与详情弹窗均不受影响；
 * 仅在构建模型请求时由 Transformer 替换成简短占位文本。
 */
suspend fun UIMessage.trimHistoryOutputs(provider: ProviderSetting?): UIMessage {
    var changed = false
    val newParts = parts.map { part ->
        if (part.isHistoryTrimmed()) return@map part
        when (part) {
            is UIMessagePart.Tool -> {
                if (!part.canTrimClientTool()) return@map part
                val bytes = part.estimateToolOutputBytes(provider)
                if (bytes < MIN_TRIMMABLE_TOOL_OUTPUT_BYTES) {
                    part
                } else {
                    changed = true
                    part.withHistoryTrim(bytes)
                }
            }

            is UIMessagePart.ServerTool -> {
                if (!part.canTrimServerTool()) return@map part
                val bytes = part.estimateServerToolBytes()
                if (bytes < MIN_TRIMMABLE_TOOL_OUTPUT_BYTES) {
                    part
                } else {
                    changed = true
                    part.withHistoryTrim(bytes)
                }
            }

            else -> part
        }
    }
    return if (changed) copy(parts = newParts) else this
}

/** 清除某个工具/服务端搜索步骤上的「已裁剪」标记 */
fun UIMessagePart.restoreTrimmedHistory(): UIMessagePart {
    val meta = metadata ?: return this
    val cleaned = JsonObject(meta.filterKeys { it != TRIMMED_KEY && it != TRIMMED_BYTES_KEY })
    if (cleaned == meta) return this
    return when (this) {
        is UIMessagePart.Tool -> copy(metadata = cleaned)
        is UIMessagePart.ServerTool -> copy(metadata = cleaned)
        else -> this
    }
}

/**
 * 按自动裁剪计划标记这些消息里对应的工具/搜索步骤。
 *
 * 只处理 [AutoTrimPlan] 点名的那几个步骤，标记写入消息 metadata 后随会话一起落库，
 * 因此水位线只前进不回退，重启后也不会重新裁一遍。
 */
fun List<UIMessage>.applyAutoTrim(plan: AutoTrimPlan): List<UIMessage> {
    if (plan.targets.isEmpty()) return this
    // 消息 id → (工具调用 id → 原始体积)，体积用统计时算好的值，这里不再重复估算
    val targets = plan.targets.groupBy { it.messageId }
        .mapValues { (_, steps) -> steps.associate { it.toolCallId to it.bytes } }
    return map { message ->
        val byToolCall = targets[message.id] ?: return@map message
        var changed = false
        val parts = message.parts.map { part ->
            val toolCallId = when (part) {
                is UIMessagePart.Tool -> part.toolCallId
                is UIMessagePart.ServerTool -> part.toolCallId
                else -> null
            }
            val bytes = toolCallId?.let { byToolCall[it] }
            if (bytes == null || part.isHistoryTrimmed()) return@map part
            changed = true
            when (part) {
                is UIMessagePart.Tool -> part.withHistoryTrim(bytes)
                is UIMessagePart.ServerTool -> part.withHistoryTrim(bytes)
                else -> part
            }
        }
        if (changed) message.copy(parts = parts) else message
    }
}

private fun UIMessagePart.Tool.withHistoryTrim(bytes: Long): UIMessagePart.Tool {
    val extra = HistoryTrimMetadata(trimmed = true, trimmedBytes = bytes).toMetadata()
    return copy(metadata = JsonObject(metadata.orEmpty() + extra))
}

private fun UIMessagePart.ServerTool.withHistoryTrim(bytes: Long): UIMessagePart.ServerTool {
    val extra = HistoryTrimMetadata(trimmed = true, trimmedBytes = bytes).toMetadata()
    return copy(metadata = JsonObject(metadata.orEmpty() + extra))
}
