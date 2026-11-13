package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.HistoryTrimMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.canTrimClientTool
import me.rerere.ai.util.canTrimServerTool
import me.rerere.ai.util.hasSideEffectTool
import me.rerere.ai.util.isHistoryTrimmed
import me.rerere.ai.util.toolBriefHint
import me.rerere.rikkahub.utils.formatBytes

/**
 * 把「裁剪历史」标记过的工具调用输出与服务端搜索结果替换成简短占位文本。
 *
 * - 对客户端 [UIMessagePart.Tool]：完整保留 `toolCallId`、`toolName`、`input` 以及消息上的签名 metadata
 *   （如 Claude thinking signature / Gemini thoughtSignature），仅将庞大的 `output` 换成一行说明。
 * - 对服务端 [UIMessagePart.ServerTool]（如 Claude 携带 `encrypted_content` 的 `web_search`）：
 *   绝不局部篡改加密块，而是整块替换为普通 [UIMessagePart.Text] 说明，避免触发服务端签名校验 400。
 * - 同时作用于正常聊天请求和 `generateCompressionSummary`（LLM 摘要压缩），避免压缩请求本身因上下文过大报 503。
 */
object HistoryTrimTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = applyHistoryTrim(messages)
}

internal fun applyHistoryTrim(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
    if (message.parts.none { it.isHistoryTrimmed() }) {
        message
    } else {
        message.copy(parts = message.parts.flatMap { trimHistoryPart(it) })
    }
}

private fun trimHistoryPart(part: UIMessagePart): List<UIMessagePart> {
        if (!part.isHistoryTrimmed()) return listOf(part)

        val bytes = part.metadataAs<HistoryTrimMetadata>()?.trimmedBytes
        val sizeText = bytes?.takeIf { it > 0 }?.let { "，原输出约 ${it.formatBytes()}" }.orEmpty()
        val hint = part.toolBriefHint().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()

        return when (part) {
            is UIMessagePart.Tool -> {
                if (!part.canTrimClientTool()) return listOf(part)
                // 有副作用的工具（执行命令、改文件、写记忆）不能提示模型重新调用，
                // 否则它为了确认输出会把命令再跑一遍
                val text = if (part.hasSideEffectTool()) {
                    "[历史工具输出已裁剪: ${part.toolName}$hint$sizeText。结果已过期，请勿重复执行同一操作]"
                } else {
                    "[历史工具输出已裁剪: ${part.toolName}$hint$sizeText。若需最新或完整内容，请重新调用工具读取]"
                }
                listOf(part.copy(output = listOf(UIMessagePart.Text(text = text))))
            }

            is UIMessagePart.ServerTool -> {
                if (!part.canTrimServerTool()) return listOf(part)
                val name = part.toolName.ifBlank { "server_tool" }
                listOf(
                    UIMessagePart.Text(
                        text = "[历史联网搜索结果已裁剪: $name$hint$sizeText]"
                    )
                )
            }

            else -> listOf(part)
        }
    }
