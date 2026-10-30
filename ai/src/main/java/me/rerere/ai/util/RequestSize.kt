package me.rerere.ai.util

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.AttachmentBudgetMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import java.io.File
import kotlin.uuid.Uuid

/**
 * 一次请求体的体积估算。
 *
 * 只做估算，不生成真实 JSON：文本按 UTF-8 字节、图片按压缩后的 Base64 长度、
 * 走 Gemini File API 的附件按 URI 的固定开销计。
 */
data class RequestSizeEstimate(
    val bytes: Long,
    val attachmentCount: Int,
    val omittedCount: Int,
    // 是否包含未编码图片的经验估算（界面展示时加 "≈" 前缀）
    val approximate: Boolean,
)

/** 单个附件的体积信息，供「压缩附件」预览使用 */
data class AttachmentSize(
    val messageId: Uuid,
    val messageIndex: Int,
    val partIndex: Int,
    val label: String,
    val bytes: Long,
    val omitted: Boolean,
)

// File API 只传一个 URI，加上 mimeType 之类的字段大约这么多
private const val FILE_DATA_PART_BYTES = 256L

/**
 * 估算这些消息作为一次请求发出时的 body 体积。
 *
 * @param provider 当前会话的提供商；用于判断附件是否会走 Gemini File API（走 fileData 时几乎不占体积）
 * @param exact true 时会对图片做一次真实编码（结果进缓存，发送时直接复用），否则用经验值估算
 */
suspend fun List<UIMessage>.estimateRequestBytes(
    provider: ProviderSetting?,
    exact: Boolean = false,
): RequestSizeEstimate = withContext(Dispatchers.IO) {
    var bytes = 0L
    var attachments = 0
    var omitted = 0
    var approximate = false

    suspend fun walk(parts: List<UIMessagePart>) {
        for (part in parts) {
            when (part) {
                is UIMessagePart.Text -> bytes += part.text.toByteArray().size.toLong()
                is UIMessagePart.Reasoning -> bytes += part.reasoning.toByteArray().size.toLong()
                is UIMessagePart.Image,
                is UIMessagePart.Video,
                is UIMessagePart.Audio,
                is UIMessagePart.Document -> {
                    attachments++
                    if (part.isOmitted()) {
                        omitted++
                        bytes += placeholderBytes(part)
                        continue
                    }
                    val (size, isApproximate) = part.estimateBytes(provider, exact)
                    bytes += size
                    approximate = approximate || isApproximate
                }

                is UIMessagePart.Tool -> {
                    // 工具调用的入参与输出都会被回传
                    bytes += part.input.toByteArray().size.toLong()
                    walk(part.output)
                }

                else -> Unit
            }
        }
    }

    forEach { message -> walk(message.parts) }

    RequestSizeEstimate(
        bytes = bytes,
        attachmentCount = attachments,
        omittedCount = omitted,
        approximate = approximate,
    )
}

/**
 * 列出这些消息里的附件（按出现顺序，即从旧到新），供「压缩附件」选择省略范围。
 */
suspend fun List<UIMessage>.listAttachments(provider: ProviderSetting?): List<AttachmentSize> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<AttachmentSize>()
        val messages = this@listAttachments
        for (messageIndex in messages.indices) {
            val message = messages[messageIndex]
            for (partIndex in message.parts.indices) {
                val part = message.parts[partIndex]
                if (!part.isAttachmentPart()) continue
                result += AttachmentSize(
                    messageId = message.id,
                    messageIndex = messageIndex,
                    partIndex = partIndex,
                    label = part.attachmentLabel(),
                    bytes = part.estimateBytes(provider, exact = false).first,
                    omitted = part.isOmitted(),
                )
            }
        }
        result
    }

private fun UIMessagePart.isAttachmentPart(): Boolean =
    this is UIMessagePart.Image ||
        this is UIMessagePart.Video ||
        this is UIMessagePart.Audio ||
        this is UIMessagePart.Document

fun UIMessagePart.isOmitted(): Boolean =
    metadataAs<AttachmentBudgetMetadata>()?.omitted == true

fun UIMessagePart.attachmentLabel(): String = when (this) {
    is UIMessagePart.Image -> url.substringAfterLast('/').ifBlank { "image" }
    is UIMessagePart.Video -> url.substringAfterLast('/').ifBlank { "video" }
    is UIMessagePart.Audio -> url.substringAfterLast('/').ifBlank { "audio" }
    is UIMessagePart.Document -> fileName.ifBlank { "document" }
    else -> ""
}

internal fun placeholderBytes(part: UIMessagePart): Long =
    part.attachmentLabel().toByteArray().size.toLong() + 64

/** 该附件是否会以 Gemini File API 的 fileData 形式发送（body 里只有一个 URI） */
private fun usesFileData(provider: ProviderSetting?, part: UIMessagePart): Boolean {
    if (provider !is ProviderSetting.Google || provider.vertexAI) return false
    if (!provider.useNativeFileUpload) return false
    return when (part) {
        is UIMessagePart.Video, is UIMessagePart.Audio -> true
        is UIMessagePart.Document -> part.mime.equals("application/pdf", true) ||
            part.fileName.endsWith(".pdf", true)
        // 图片目前始终走 inlineData
        else -> false
    }
}

private fun UIMessagePart.estimateBytes(
    provider: ProviderSetting?,
    exact: Boolean,
): Pair<Long, Boolean> {
    if (usesFileData(provider, this)) {
        return FILE_DATA_PART_BYTES to false
    }
    return when (val part = this) {
        is UIMessagePart.Image -> {
            if (!part.url.startsWith("file://")) {
                // data URL 已经是 base64，直接按字符串长度算
                part.url.length.toLong() to false
            } else {
                val file = File(part.url.toUri().path ?: return 0L to false)
                val mime = file.guessImageMimeTypeOrNull() ?: "image/jpeg"
                estimateImageBase64Bytes(file, mime, exact) to !exact
            }
        }

        is UIMessagePart.Video -> rawFileBytes(part.url)

        is UIMessagePart.Audio -> rawFileBytes(part.url)

        // 非 PDF 文档会走本地解析成文本，体积取决于解析结果，这里按原文件大小估算
        is UIMessagePart.Document -> rawFileBytes(part.url)

        else -> 0L to false
    }
}

private fun rawFileBytes(url: String): Pair<Long, Boolean> {
    if (!url.startsWith("file://")) return 0L to false
    val file = File(url.toUri().path ?: return 0L to false)
    if (!file.exists()) return 0L to false
    // Base64 膨胀 4/3
    return (file.length() / 3 * 4) to false
}

/** 单个附件在请求体里大概占多少字节 */
suspend fun UIMessagePart.estimateAttachmentBytes(provider: ProviderSetting?): Long =
    withContext(Dispatchers.IO) { estimateBytes(provider, exact = false).first }
