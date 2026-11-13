package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.HistoryTrimMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.estimateRequestBytes
import me.rerere.ai.util.inspectHistoryTrim
import me.rerere.ai.util.isHistoryTrimmed
import me.rerere.rikkahub.data.model.hasTrimmableHistory
import me.rerere.rikkahub.data.model.restoreTrimmedHistory
import me.rerere.rikkahub.data.model.selectTrimHistoryCandidates
import me.rerere.rikkahub.data.model.trimHistoryOutputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimTransformerTest {

    @Test
    fun `trimHistoryOutputs marks large tool outputs while skipping small ones and preserves signatures`() = runTest {
        val largeText = "x".repeat(4096)
        val smallText = "ok"
        val thoughtMeta = GoogleThoughtMetadata(thoughtSignature = "sig-gemini-123").toMetadata()
        val claudeReasoning = UIMessagePart.Reasoning(
            reasoning = "let me check",
            metadata = ClaudeReasoningMetadata(signature = "sig-claude-abc").toMetadata(),
        )

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                claudeReasoning,
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "workspace_read_file",
                    input = """{"path":"/workspace/App.kt"}""",
                    output = listOf(UIMessagePart.Text(largeText)),
                    metadata = thoughtMeta,
                ),
                UIMessagePart.Tool(
                    toolCallId = "call_2",
                    toolName = "workspace_edit_file",
                    input = """{"path":"/workspace/App.kt"}""",
                    output = listOf(UIMessagePart.Text(smallText)),
                ),
                UIMessagePart.Text("All set!"),
            ),
        )

        val trimmedMsg = message.trimHistoryOutputs(provider = null)
        val readTool = trimmedMsg.parts[1] as UIMessagePart.Tool
        val editTool = trimmedMsg.parts[2] as UIMessagePart.Tool

        assertTrue(readTool.isHistoryTrimmed())
        assertFalse(editTool.isHistoryTrimmed())
        // Local original output stays untouched for UI preview / citations
        assertEquals(largeText, (readTool.output.single() as UIMessagePart.Text).text)
        // Existing provider signature metadata must survive merging
        assertEquals("sig-gemini-123", readTool.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)

        val transformed = applyHistoryTrim(listOf(trimmedMsg)).single()
        val transformedRead = transformed.parts[1] as UIMessagePart.Tool
        val placeholder = (transformedRead.output.single() as UIMessagePart.Text).text

        assertTrue(placeholder.contains("workspace_read_file"))
        assertTrue(placeholder.contains("/workspace/App.kt"))
        assertTrue(placeholder.length < 200)
        assertEquals("sig-gemini-123", transformedRead.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals("sig-claude-abc", transformed.parts[0].metadataAs<ClaudeReasoningMetadata>()?.signature)
        assertEquals(smallText, ((transformed.parts[2] as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text)
        assertEquals("All set!", (transformed.parts[3] as UIMessagePart.Text).text)

        // Restoring clears trim flag while retaining provider signature metadata
        val restoredRead = readTool.restoreTrimmedHistory() as UIMessagePart.Tool
        assertFalse(restoredRead.isHistoryTrimmed())
        assertEquals("sig-gemini-123", restoredRead.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    @Test
    fun `trimmed Claude server web search is replaced as whole text block without mutating encrypted payload`() = runTest {
        val encryptedBlob = "E".repeat(8192)
        val serverTool = UIMessagePart.ServerTool(
            toolCallId = "srvtoolu_1",
            toolName = "web_search",
            input = buildJsonObject { put("query", "kotlin multiplatform") },
            status = ServerToolStatus.COMPLETED,
            metadata = ServerToolMetadata(
                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                call = buildJsonObject {
                    put("type", "server_tool_use")
                    put("id", "srvtoolu_1")
                    put("name", "web_search")
                },
                result = buildJsonObject {
                    put("type", "web_search_tool_result")
                    put("encrypted_content", encryptedBlob)
                },
            ).toMetadata(),
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(serverTool, UIMessagePart.Text("Search summary")),
        )

        val beforeEstimate = listOf(message).estimateRequestBytes(provider = null)
        val trimmed = message.trimHistoryOutputs(provider = null)
        val afterEstimate = listOf(trimmed).estimateRequestBytes(provider = null)
        assertTrue(afterEstimate.bytes < beforeEstimate.bytes - 7000)

        val stats = listOf(trimmed).inspectHistoryTrim(provider = null)
        assertEquals(1, stats.totalCount)
        assertEquals(1, stats.trimmedCount)

        val transformed = applyHistoryTrim(listOf(trimmed)).single()
        assertEquals(2, transformed.parts.size)
        assertTrue(transformed.parts[0] is UIMessagePart.Text)
        assertTrue((transformed.parts[0] as UIMessagePart.Text).text.contains("kotlin multiplatform"))
    }

    @Test
    fun `ask_user answers and user denied reasons are excluded from history trimming`() = runTest {
        val longAnswer = """{"answers":{"q1":"${"详细的业务说明与需求细节".repeat(40)}"}}"""
        val longDenyReason = "Tool execution denied by user. Reason: " + "不要改动数据库结构，请改用缓存层方案。".repeat(30)

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "ask_1",
                    toolName = "ask_user",
                    input = """{"questions":[{"id":"q1","question":"请确认方案"}]}""",
                    output = listOf(UIMessagePart.Text(longAnswer)),
                    approvalState = ToolApprovalState.Answered(longAnswer),
                ),
                UIMessagePart.Tool(
                    toolCallId = "deny_1",
                    toolName = "workspace_shell",
                    input = """{"command":"rm -rf build"}""",
                    output = listOf(UIMessagePart.Text(longDenyReason)),
                    approvalState = ToolApprovalState.Denied(longDenyReason),
                ),
            ),
        )

        assertFalse(message.hasTrimmableHistory(provider = null))
        val stats = listOf(message).inspectHistoryTrim(provider = null)
        assertEquals(0, stats.totalCount)
        assertEquals(0L, stats.activeBytes)

        val afterTrim = message.trimHistoryOutputs(provider = null)
        assertEquals(message, afterTrim)

        // Even if legacy metadata had trimmed=true on ask_user, transformer must keep user answer intact
        val forcedTrimMeta = HistoryTrimMetadata(trimmed = true, trimmedBytes = 1024L).toMetadata()
        val legacyMessage = message.copy(
            parts = listOf((message.parts[0] as UIMessagePart.Tool).copy(metadata = forcedTrimMeta)),
        )
        val transformed = applyHistoryTrim(listOf(legacyMessage)).single()
        val askOutput = ((transformed.parts[0] as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text
        assertEquals(longAnswer, askOutput)
    }

    @Test
    fun `selectTrimHistoryCandidates matches stepped limitContext window and preview`() = runTest {
        // 21 messages (1..21), contextMessageLimit = 20 -> stepped limitContext keeps messages 11..21 (indices 10..20)
        val messages = (1..21).map { number ->
            if (number % 2 == 1) {
                UIMessage.user("question $number")
            } else {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call_$number",
                            toolName = "workspace_read_file",
                            input = """{"path":"/workspace/file_$number.kt"}""",
                            output = listOf(UIMessagePart.Text("content_$number:".repeat(120))),
                        )
                    ),
                )
            }
        }

        val keepRecent = 4
        val window = messages.limitContext(20)
        val previewStats = window.inspectHistoryTrim(provider = null)
        val cutoffIndex = (window.size - keepRecent).coerceAtLeast(0)
        val previewTargetIds = previewStats.steps
            .filter { it.messageIndex < cutoffIndex && !it.trimmed }
            .map { it.messageId }

        val candidates = messages.selectTrimHistoryCandidates(
            contextMessageLimit = 20,
            keepRecentMessages = keepRecent,
        )
        val actualTrimmedIds = candidates
            .filter { it.hasTrimmableHistory(provider = null) }
            .map { it.id }

        // Window candidates are messages 11..17 (indices 10..16); messages 2..10 outside stepped window stay untouched
        assertEquals(messages.subList(10, 17).map { it.id }, candidates.map { it.id })
        assertEquals(listOf(messages[11].id, messages[13].id, messages[15].id), actualTrimmedIds)
        assertEquals(previewTargetIds, actualTrimmedIds)
    }
}
