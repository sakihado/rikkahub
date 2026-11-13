package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ANDROID_SHELL_TOOL_NAME
import me.rerere.rikkahub.hostshell.ShellPolicyEngine
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.ui.richtext.DiffAddedColor

/**
 * Android 宿主 Shell 工具卡片渲染器：
 * - 等待审批 (`isPending`) 时直接内联展开显示：执行身份 (UID 2000 / Root)、触发审批的具体原因、完整待执行命令
 * - 执行完成后显示：退出状态码、执行身份与输出摘要
 */
object AndroidShellToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 38
    private const val SUMMARY_MAX_LINES = 8

    override val toolName: String = ANDROID_SHELL_TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command")
            ?: return stringResource(R.string.tool_ui_android_shell_default)
        val asRoot = context.arguments.jsonObjectOrNull?.get("root")?.jsonPrimitiveOrNull?.let {
            it.booleanOrNull ?: it.toString().toBooleanStrictOrNull()
        } ?: false
        val prefix = if (asRoot) "[Root] " else ""
        val preview = command.replace("\n", " ").trim()
        val truncated = if (preview.length > TITLE_MAX_CHARS) preview.take(TITLE_MAX_CHARS) + "…" else preview
        return stringResource(R.string.tool_ui_android_shell, prefix + truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.tool.isPending || context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val command = context.arguments.getStringContent("command").orEmpty()
        val asRoot = context.arguments.jsonObjectOrNull?.get("root")?.jsonPrimitiveOrNull?.let {
            it.booleanOrNull ?: it.toString().toBooleanStrictOrNull()
        } ?: false

        // 待审批状态：展示策略原因、身份标签与完整命令，方便用户无需点开详情即可安全判断
        if (context.tool.isPending) {
            val policy = remember(command, asRoot) {
                ShellPolicyEngine.evaluate(command, asRoot)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = buildString {
                        append(if (asRoot) "身份: Root (UID 0)" else "身份: Shell (UID 2000)")
                        append(" · ")
                        append(policy.reason)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (asRoot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                )
                if (command.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = command,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            return
        }

        val content = context.content ?: return
        val exitCode = content.jsonObjectOrNull?.get("exitCode")?.jsonPrimitiveOrNull?.intOrNull
        val timedOut = content.jsonObjectOrNull?.get("timedOut")?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        val blocked = content.jsonObjectOrNull?.get("blocked")?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        val identity = content.getStringContent("identity")
        val combined = remember(content) {
            listOf(content.getStringContent("stdout"), content.getStringContent("stderr"))
                .filterNot { it.isNullOrBlank() }
                .joinToString("\n")
                .trim()
        }
        val ok = !timedOut && !blocked && exitCode == 0

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = buildString {
                    when {
                        blocked -> append(stringResource(R.string.tool_ui_android_shell_blocked))
                        timedOut -> append(stringResource(R.string.tool_ui_shell_timeout))
                        else -> append(stringResource(R.string.tool_ui_shell_exit, exitCode?.toString() ?: "?"))
                    }
                    if (!identity.isNullOrBlank()) {
                        append(" · ")
                        append(identity)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) DiffAddedColor else MaterialTheme.colorScheme.error,
            )
            if (combined.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .shimmer(isLoading = context.loading),
                ) {
                    Text(
                        text = combined.lineSequence().take(SUMMARY_MAX_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val command = context.arguments.getStringContent("command").orEmpty()
        val stdout = content.getStringContent("stdout").orEmpty()
        val stderr = content.getStringContent("stderr").orEmpty()
        val identity = content.getStringContent("identity").orEmpty()
        val exitCode = content.jsonObjectOrNull?.get("exitCode")?.jsonPrimitiveOrNull?.intOrNull
        val timedOut = content.jsonObjectOrNull?.get("timedOut")?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        val ok = !timedOut && exitCode == 0

        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_android_shell_default) +
                        if (identity.isNotEmpty()) " ($identity)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (timedOut) {
                        stringResource(R.string.tool_ui_shell_timeout)
                    } else {
                        stringResource(R.string.tool_ui_shell_exit, exitCode?.toString() ?: "?")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ok) DiffAddedColor else MaterialTheme.colorScheme.error,
                )
            }
            HighlightCodeBlock(
                code = command,
                language = "bash",
                modifier = Modifier.fillMaxWidth(),
            )
            if (stdout.isNotEmpty()) {
                Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
                HighlightCodeBlock(
                    code = stdout,
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (stderr.isNotEmpty()) {
                Text(
                    text = "stderr",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HighlightCodeBlock(
                    code = stderr,
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
