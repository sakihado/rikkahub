package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.estimateRequestBytes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.utils.formatBytes

/**
 * 发送前的请求体积校验。
 *
 * 模型上没配「请求体积上限」时完全不介入；配了就在超出 95% 时直接报错，
 * 引导用户去点「压缩附件」，而不是静默丢内容。
 */
object RequestSizeGuardTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val limitMb = ctx.model.requestSizeLimitMb
        if (limitMb <= 0) return messages

        val provider = ctx.model.findProvider(ctx.settings.providers)
        // exact = true：顺便把图片编码结果预热进缓存，发送时直接命中，不做两次
        val estimate = messages.estimateRequestBytes(provider, exact = true)
        val limitBytes = limitMb.toLong() * 1024 * 1024
        // 留 5% 给 JSON 结构、系统提示词等
        if (estimate.bytes > limitBytes * 95 / 100) {
            throw IllegalStateException(
                ctx.context.getString(
                    R.string.chat_attachment_request_too_large,
                    estimate.bytes.formatBytes(),
                    limitMb,
                )
            )
        }
        return messages
    }
}
