package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole

/** Only offer recovery for an assistant reply that actually carries Google signatures. */
fun UIMessage.hasGoogleThoughtSignatures(): Boolean = role == MessageRole.ASSISTANT && parts.any { part ->
    part.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature != null ||
        part.metadataAs<GoogleInteractionsMetadata>()?.signature != null ||
        (part is UIMessagePart.ServerTool && part.metadataAs<ServerToolMetadata>()?.let { metadata ->
            metadata.protocol in setOf(
                ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                ServerToolProtocol.GOOGLE_INTERACTIONS,
            ) && listOfNotNull(metadata.call, metadata.result).any { raw ->
                raw.containsKey("thoughtSignature") || raw.containsKey("signature")
            }
        } == true)
}
