package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputStateTest {
    private fun editing(parts: List<UIMessagePart>) = ChatInputState().apply {
        editingMessage = Uuid.random()
        setContents(parts)
    }

    @Test
    fun `reasoning and text message allows editing reasoning in place`() {
        val reasoning = UIMessagePart.Reasoning("old thought")
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNotNull(state.editingReasoning)
        assertEquals(emptyList<UIMessagePart>(), state.messageContent)

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("new thought")
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(
            listOf(reasoning.copy(reasoning = "new thought"), UIMessagePart.Text("new answer")),
            state.getContents(),
        )
    }

    @Test
    fun `blank reasoning is dropped`() {
        val state = editing(listOf(UIMessagePart.Reasoning("old thought"), UIMessagePart.Text("answer")))

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("  ")

        assertEquals(listOf(UIMessagePart.Text("answer")), state.getContents())
    }

    @Test
    fun `signed reasoning is not editable and kept as is`() {
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "thought",
            metadata = buildJsonObject { put("signature", JsonPrimitive("sig")) },
        )
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNull(state.editingReasoning)
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(listOf(reasoning, UIMessagePart.Text("new answer")), state.getContents())
    }

    @Test
    fun `message with other parts is not editable`() {
        val state = editing(
            listOf(
                UIMessagePart.Reasoning("thought"),
                UIMessagePart.Text("answer"),
                UIMessagePart.Image("file:///image.png"),
            )
        )

        assertNull(state.editingReasoning)
    }

    @Test
    fun `clear input resets reasoning editing`() {
        val state = editing(listOf(UIMessagePart.Reasoning("thought"), UIMessagePart.Text("answer")))

        state.clearInput()

        assertNull(state.editingReasoning)
    }

    @Test
    fun `draft round-trip preserves compose text and attachments`() {
        val image = UIMessagePart.Image("file:///upload/a.png")
        val doc = UIMessagePart.Document("file:///upload/b.txt", "b.txt", "text/plain")
        val source = ChatInputState().apply {
            setMessageText("unsent question")
            messageContent = listOf(image, doc)
        }

        val draft = source.toDraft()
        val restored = ChatInputState().apply {
            restoreDraft(draft)
        }

        assertEquals("unsent question", restored.textContent.text.toString())
        assertEquals(listOf(image, doc), restored.messageContent)
        assertEquals(false, restored.isEditing())
        assertEquals(true, restored.shouldDeleteFileOnRemove(image))
    }

    @Test
    fun `draft round-trip preserves editing context reasoning and attachment deletion rules`() {
        val messageId = Uuid.random()
        val origReasoning = UIMessagePart.Reasoning("old thought")
        val origImage = UIMessagePart.Image("file:///upload/orig.png")
        val addedImage = UIMessagePart.Image("file:///upload/added.png")
        val reasoningState = editing(listOf(origReasoning, UIMessagePart.Text("old answer")))
        reasoningState.editingReasoning!!.setTextAndPlaceCursorAtEnd("edited thought")
        reasoningState.setMessageText("edited answer")

        val restoredReasoning = ChatInputState().apply {
            restoreDraft(reasoningState.toDraft())
        }
        assertEquals(
            listOf(origReasoning.copy(reasoning = "edited thought"), UIMessagePart.Text("edited answer")),
            restoredReasoning.getContents(),
        )

        val attachmentEditState = ChatInputState().apply {
            editingMessage = messageId
            setContents(listOf(origImage, UIMessagePart.Text("caption")))
            setMessageText("updated caption")
            messageContent = listOf(origImage, addedImage)
        }
        val restoredAttachmentEdit = ChatInputState().apply {
            restoreDraft(attachmentEditState.toDraft())
        }
        assertEquals(messageId, restoredAttachmentEdit.editingMessage)
        assertEquals(false, restoredAttachmentEdit.shouldDeleteFileOnRemove(origImage))
        assertEquals(true, restoredAttachmentEdit.shouldDeleteFileOnRemove(addedImage))
        assertEquals(
            listOf(origImage, UIMessagePart.Text("updated caption"), addedImage),
            restoredAttachmentEdit.getContents(),
        )
    }

    @Test
    fun `restoreDraft clears input when edited message no longer exists`() {
        val state = editing(listOf(UIMessagePart.Text("deleted message")))
        val draft = state.toDraft()

        val target = ChatInputState().apply {
            restoreDraft(draft, canRestoreEditing = { false })
        }

        assertEquals(false, target.isEditing())
        assertEquals(true, target.isEmpty())
    }
}
