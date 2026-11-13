package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.AutoTrimConfig
import me.rerere.ai.util.inspectHistoryTrim
import me.rerere.ai.util.isHistoryTrimmed
import me.rerere.ai.util.planAutoTrim
import me.rerere.rikkahub.data.model.applyAutoTrim
import me.rerere.rikkahub.data.model.trimHistoryOutputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHistoryTrimTest {

    private val config = AutoTrimConfig(
        keepRecentSteps = 2,
        minFreeBytes = 1024L,
        contextTokens = 100_000,
    )

    private fun toolMessage(vararg sizes: Int, prefix: String = "call"): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = sizes.mapIndexed { index, size ->
            UIMessagePart.Tool(
                toolCallId = "$prefix-$index",
                toolName = "workspace_read_file",
                input = """{"path":"/workspace/File$index.kt"}""",
                output = listOf(UIMessagePart.Text("x".repeat(size))),
            )
        },
    )

    @Test
    fun `disabled when any slider is at the far left`() = runTest {
        val messages = listOf(toolMessage(4096, 4096, 4096, 4096))
        val stats = messages.inspectHistoryTrim(provider = null)

        assertNull(stats.planAutoTrim(AutoTrimConfig(0, 2048L, 1000), contextTokens = 200_000))
        assertNull(stats.planAutoTrim(AutoTrimConfig(2, 0L, 1000), contextTokens = 200_000))
        assertNull(stats.planAutoTrim(AutoTrimConfig(2, 2048L, 0), contextTokens = 200_000))
        assertNull(stats.planAutoTrim(AutoTrimConfig.Disabled, contextTokens = 200_000))
    }

    @Test
    fun `does not trigger below the context watermark`() = runTest {
        val messages = listOf(toolMessage(4096, 4096, 4096, 4096))
        val stats = messages.inspectHistoryTrim(provider = null)

        assertNull(stats.planAutoTrim(config, contextTokens = 99_999))
        // 拿不到 usage 时同样不触发，避免在无从判断时乱裁
        assertNull(stats.planAutoTrim(config, contextTokens = null))
    }

    @Test
    fun `keeps the most recent steps and trims the rest`() = runTest {
        val message = toolMessage(4096, 4096, 4096, 4096, 4096)
        val stats = listOf(message).inspectHistoryTrim(provider = null)

        val plan = requireNotNull(stats.planAutoTrim(config, contextTokens = 200_000))
        assertEquals(3, plan.targets.size)
        assertEquals(listOf("call-0", "call-1", "call-2"), plan.targets.map { it.toolCallId })
        assertTrue(plan.freedBytes > 0)

        val trimmed = listOf(message).applyAutoTrim(plan).single()
        val tools = trimmed.parts.filterIsInstance<UIMessagePart.Tool>()
        assertTrue(tools[0].isHistoryTrimmed())
        assertTrue(tools[1].isHistoryTrimmed())
        assertTrue(tools[2].isHistoryTrimmed())
        assertFalse(tools[3].isHistoryTrimmed())
        assertFalse(tools[4].isHistoryTrimmed())
        // 本地原始输出保留，界面预览与引用不受影响
        assertEquals(4096L, (tools[0].output.single() as UIMessagePart.Text).text.length.toLong())
    }

    @Test
    fun `does not trim when fewer steps than the keep window`() = runTest {
        val messages = listOf(toolMessage(4096, 4096))
        val stats = messages.inspectHistoryTrim(provider = null)

        assertNull(stats.planAutoTrim(config, contextTokens = 200_000))
    }

    @Test
    fun `does not trigger when the batch would free too little`() = runTest {
        // 只多出一步且体积很小：释放量低于门槛，宁可不裁也不破坏提示词缓存
        val messages = listOf(toolMessage(600, 4096, 4096))
        val stats = messages.inspectHistoryTrim(provider = null)

        assertNull(
            stats.planAutoTrim(
                config.copy(minFreeBytes = 4096L),
                contextTokens = 200_000,
            )
        )
    }

    @Test
    fun `already trimmed steps are never counted again`() = runTest {
        val message = toolMessage(4096, 4096, 4096, 4096)
        val stats = listOf(message).inspectHistoryTrim(provider = null)
        val plan = requireNotNull(stats.planAutoTrim(config, contextTokens = 200_000))
        val once = listOf(message).applyAutoTrim(plan)

        val secondStats = once.inspectHistoryTrim(provider = null)
        // 已裁的 2 步被排除，剩余未裁步骤不超过保留窗口
        val secondPlan = secondStats.planAutoTrim(config, contextTokens = 200_000)
        assertNull(secondPlan)
        assertEquals(2, secondStats.trimmedCount)
    }

    @Test
    fun `side effect tools use a neutral placeholder`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-shell",
                    toolName = "workspace_shell",
                    input = """{"command":"git commit -m wip"}""",
                    output = listOf(UIMessagePart.Text("y".repeat(4096))),
                ),
                UIMessagePart.Tool(
                    toolCallId = "call-read",
                    toolName = "workspace_read_file",
                    input = """{"path":"/workspace/App.kt"}""",
                    output = listOf(UIMessagePart.Text("z".repeat(4096))),
                ),
            ),
        )
        val trimmed = message.trimHistoryOutputs(provider = null)
        val transformed = applyHistoryTrim(listOf(trimmed)).single()

        val shellText = (transformed.parts[0] as UIMessagePart.Tool)
            .output.single().let { it as UIMessagePart.Text }.text
        val readText = (transformed.parts[1] as UIMessagePart.Tool)
            .output.single().let { it as UIMessagePart.Text }.text

        // 有副作用的命令不能被提示“重新调用”，只读工具才允许
        assertFalse(shellText.contains("请重新调用"))
        assertTrue(readText.contains("请重新调用"))
    }
}
