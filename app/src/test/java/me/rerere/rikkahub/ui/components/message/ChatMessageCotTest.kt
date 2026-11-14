package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

class ChatMessageCotTest {
    @Test
    fun `groupMessageParts skips empty google signature carrier parts`() {
        val now = Clock.System.now()
        val parts = listOf(
            UIMessagePart.Reasoning(
                reasoning = "",
                createdAt = now,
                finishedAt = now,
                metadata = GoogleThoughtMetadata(thoughtSignature = "sig-thought", isThought = true).toMetadata(),
            ),
            UIMessagePart.Text(
                text = "Visible answer",
            ),
            UIMessagePart.Text(
                text = "",
                metadata = GoogleThoughtMetadata(thoughtSignature = "sig-tail").toMetadata(),
            ),
        )

        val blocks = parts.groupMessageParts()
        assertEquals(1, blocks.size)
        val contentBlock = blocks.single() as MessagePartBlock.ContentBlock
        assertTrue(contentBlock.part is UIMessagePart.Text)
        assertEquals("Visible answer", (contentBlock.part as UIMessagePart.Text).text)
    }

    @Test
    fun `groupMessageParts keeps active loading reasoning even when text is initially empty`() {
        val now = Clock.System.now()
        val parts = listOf(
            UIMessagePart.Reasoning(
                reasoning = "",
                createdAt = now,
                finishedAt = null,
            ),
            UIMessagePart.Text("Answer"),
        )

        val blocks = parts.groupMessageParts()
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MessagePartBlock.ThinkingBlock)
        assertTrue(blocks[1] is MessagePartBlock.ContentBlock)
    }
}
