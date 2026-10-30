package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.AttachmentBudgetMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.estimateAttachmentBytes
import me.rerere.ai.util.isOmitted

private const val OMITTED_KEY = "omitted"
private const val OMITTED_BYTES_KEY = "omitted_bytes"

/** 这条消息里还有没有被省略的附件 */
fun UIMessage.hasRestorableAttachments(): Boolean =
    parts.any { it.isAttachmentPart() && !it.isOmitted() }

fun UIMessage.hasOmittedAttachments(): Boolean =
    parts.any { it.isAttachmentPart() && it.isOmitted() }

/**
 * 把这这条消息里的所有附件标记为「已省略」，记录各自估算体积用于界面展示。
 *
 * 原文件不动，只是之后构建请求时会被替换成占位文本。
 */
suspend fun UIMessage.omitAttachments(provider: ProviderSetting?): UIMessage {
    var changed = false
    val newParts = parts.map { part ->
        if (!part.isAttachmentPart() || part.isOmitted()) {
            part
        } else {
            changed = true
            part.withOmission(bytes = part.estimateAttachmentBytes(provider))
        }
    }
    return if (changed) copy(parts = newParts) else this
}

/** 清除某个部件上的省略标记（点单张恢复） */
fun UIMessagePart.restoreAttachment(): UIMessagePart {
    val meta = metadata ?: return this
    val cleaned = JsonObject(meta.filterKeys { it != OMITTED_KEY && it != OMITTED_BYTES_KEY })
    if (cleaned == meta) return this
    return when (this) {
        is UIMessagePart.Image -> copy(metadata = cleaned)
        is UIMessagePart.Video -> copy(metadata = cleaned)
        is UIMessagePart.Audio -> copy(metadata = cleaned)
        is UIMessagePart.Document -> copy(metadata = cleaned)
        else -> this
    }
}

private fun UIMessagePart.isAttachmentPart(): Boolean =
    this is UIMessagePart.Image ||
        this is UIMessagePart.Video ||
        this is UIMessagePart.Audio ||
        this is UIMessagePart.Document

private fun UIMessagePart.withOmission(bytes: Long): UIMessagePart {
    val extra = AttachmentBudgetMetadata(omitted = true, omittedBytes = bytes).toMetadata()
    val merged = JsonObject(metadata.orEmpty() + extra)
    return when (this) {
        is UIMessagePart.Image -> copy(metadata = merged)
        is UIMessagePart.Video -> copy(metadata = merged)
        is UIMessagePart.Audio -> copy(metadata = merged)
        is UIMessagePart.Document -> copy(metadata = merged)
        else -> this
    }
}
