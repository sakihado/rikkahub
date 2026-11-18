package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole

@Serializable
enum class AssistantStopReason { RUNNING, COMPLETED, AWAITING_APPROVAL, STEP_LIMIT, TRUNCATED, CANCELLED, ERROR }

fun UIMessage.canContinueFromBreakpoint(): Boolean {
    if (role != MessageRole.ASSISTANT || stopReason !in setOf(
            AssistantStopReason.ERROR, AssistantStopReason.CANCELLED, AssistantStopReason.STEP_LIMIT,
            AssistantStopReason.TRUNCATED,
        )) return false
    val count = committedPartCount ?: return false
    if (count !in 1..parts.size) return false
    return parts.take(count).any { it is UIMessagePart.Tool || it is UIMessagePart.Notice }
}

/** Keep the committed prefix verbatim, except for interrupted local execution. Never infer a
 * checkpoint from legacy messages or from a parseable partial tool call. */
fun UIMessage.prepareForResume(): UIMessage? {
    if (!canContinueFromBreakpoint()) return null
    val prefix = parts.take(checkNotNull(committedPartCount)).map { part ->
        if (part is UIMessagePart.Tool && (part.isInterrupted || !part.isExecuted)) part.copy(
            output = emptyList(),
            isInterrupted = false,
            approvalState = if (part.outcomeUnknown) ToolApprovalState.Pending else part.approvalState,
        ) else part
    }
    val pending = prefix.any { it is UIMessagePart.Tool && it.isPending }
    return copy(parts = prefix, finishedAt = null, stopReason = if (pending) AssistantStopReason.AWAITING_APPROVAL else AssistantStopReason.RUNNING)
}

/** Editing/sending a new turn must not upload a half-received tool batch as history. Leave
 * partial first-turn plain text readable; this is a wire projection, not a UI mutation. */
fun UIMessage.withoutUncommittedPreview(): UIMessage {
    val count = committedPartCount ?: return this
    if (count !in 0 until parts.size || stopReason !in setOf(AssistantStopReason.ERROR,
            AssistantStopReason.CANCELLED, AssistantStopReason.TRUNCATED)) return this
    if (count == 0 && getTools().isEmpty()) return this
    return copy(parts = parts.take(count))
}
