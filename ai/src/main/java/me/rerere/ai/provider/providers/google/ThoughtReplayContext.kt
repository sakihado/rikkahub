package me.rerere.ai.provider.providers.google

import me.rerere.ai.provider.GoogleCrossChannelThoughtPolicy
import me.rerere.ai.ui.UIMessage

/** Request-local policy; never mutates stored signatures or the original message parts. */
internal data class ThoughtReplayContext(
    val currentOrigin: String? = null,
    val policy: GoogleCrossChannelThoughtPolicy = GoogleCrossChannelThoughtPolicy.PASSTHROUGH,
    val ignoreSignatures: Boolean = false,
) {
    val effectivePolicy: GoogleCrossChannelThoughtPolicy
        get() = if (ignoreSignatures) {
            // Explicit single-reply recovery overrides passthrough, but keeps readable summaries.
            GoogleCrossChannelThoughtPolicy.STRIP_SIGNATURE_KEEP_THOUGHT_TEXT
        } else {
            policy
        }

    fun isAdaptationEnabled(): Boolean = ignoreSignatures ||
        (currentOrigin != null && policy != GoogleCrossChannelThoughtPolicy.PASSTHROUGH)

    fun isForeignOrUnknown(origin: String?): Boolean = ignoreSignatures ||
        (isAdaptationEnabled() && origin != currentOrigin)

    fun forMessage(message: UIMessage): ThoughtReplayContext =
        if (message.ignoreGoogleThoughtSignatures) copy(ignoreSignatures = true) else this
}
