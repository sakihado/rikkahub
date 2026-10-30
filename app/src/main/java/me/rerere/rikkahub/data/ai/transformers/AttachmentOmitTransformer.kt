package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.AttachmentBudgetMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.attachmentLabel
import me.rerere.ai.util.isOmitted
import me.rerere.rikkahub.utils.formatBytes

/**
 * 把「压缩附件」标记过的部件替换成一行占位文本。
 *
 * 必须排在 [DocumentAsPromptTransformer] 与 [OcrTransformer] 之前，否则：
 * - 已省略的 PDF 每轮都会被重新解析一遍（几百页很贵）
 * - 已省略的图片仍会触发一次 OCR 请求，而且图片会被换成文本，后面就再也找不到它
 */
object AttachmentOmitTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.map { message ->
        if (message.parts.none { it.hasOmittedAttachment() }) {
            message
        } else {
            message.copy(parts = message.parts.flatMap { omit(it) })
        }
    }

    private fun omit(part: UIMessagePart): List<UIMessagePart> {
        if (part is UIMessagePart.Tool) {
            if (part.output.none { it.hasOmittedAttachment() }) return listOf(part)
            return listOf(part.copy(output = part.output.flatMap { omit(it) }))
        }
        if (!part.isOmitted()) return listOf(part)

        val size = part.metadataAs<AttachmentBudgetMetadata>()?.omittedBytes
        val sizeText = size?.takeIf { it > 0 }?.let { "，约 ${it.formatBytes()}" }.orEmpty()
        return listOf(
            UIMessagePart.Text(
                text = "[附件已省略: ${part.attachmentLabel()}$sizeText]",
                // 保留标记，界面据此渲染「可恢复」的占位
                metadata = part.metadata,
            )
        )
    }
}

private fun UIMessagePart.hasOmittedAttachment(): Boolean = when (this) {
    is UIMessagePart.Tool -> output.any { it.hasOmittedAttachment() }
    else -> isOmitted()
}
