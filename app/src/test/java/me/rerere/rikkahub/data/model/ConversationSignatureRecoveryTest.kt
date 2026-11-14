package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSignatureRecoveryTest {
    private fun reply(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text, GoogleThoughtMetadata("synthetic-signature", "vertex:test").toMetadata())),
    )

    @Test
    fun `recovery changes only the addressed reply and preserves branch selection and parts`() {
        val first = reply("first")
        val selected = reply("selected")
        val another = reply("another node")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(first, selected), selectIndex = 1), another.toMessageNode()),
        )
        val updated = conversation.withGoogleThoughtSignaturesIgnored(first.id, true)
        assertEquals(1, updated.messageNodes[0].selectIndex)
        assertTrue(updated.messageNodes[0].messages[0].ignoreGoogleThoughtSignatures)
        assertEquals(first.parts, updated.messageNodes[0].messages[0].parts)
        assertEquals(selected, updated.messageNodes[0].currentMessage)
        assertEquals(another, updated.messageNodes[1].currentMessage)
        assertEquals(conversation, updated.withGoogleThoughtSignaturesIgnored(first.id, false))
    }

    @Test
    fun `latest message updates are preserved instead of overwriting a stale snapshot`() {
        val reply = reply("answer")
        val original = Conversation(assistantId = Uuid.random(), messageNodes = listOf(reply.toMessageNode()))
        val latest = original.withTranslation(reply.id, "new translation")
        val updated = latest.withGoogleThoughtSignaturesIgnored(reply.id, true)
        assertEquals("new translation", updated.currentMessages.single().translation)
        assertTrue(updated.currentMessages.single().ignoreGoogleThoughtSignatures)
    }

    @Test
    fun `user messages and missing ids are not changed`() {
        val user = reply("user text").copy(role = MessageRole.USER)
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(user.toMessageNode()))
        assertEquals(conversation, conversation.withGoogleThoughtSignaturesIgnored(user.id, true))
        assertEquals(conversation, conversation.withGoogleThoughtSignaturesIgnored(Uuid.random(), true))
    }

    @Test
    fun `unsigned assistant reply cannot be marked by mistake`() {
        val reply = UIMessage.assistant("plain")
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(reply.toMessageNode()))
        assertFalse(conversation.withGoogleThoughtSignaturesIgnored(reply.id, true).currentMessages.single().ignoreGoogleThoughtSignatures)
    }
}
