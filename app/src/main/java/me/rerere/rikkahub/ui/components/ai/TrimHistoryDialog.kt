package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HistoryTrimStats
import me.rerere.ai.util.inspectHistoryTrim
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.formatBytes

/**
 * 「裁剪历史」确认弹窗：显示折叠较早工具/搜索大输出后可节省多少体积，并允许保留最近若干条消息。
 *
 * 统计要遍历大文本、读附件长度，放到 IO 线程算。
 */
@Composable
fun TrimHistoryDialog(
    messages: List<UIMessage>,
    provider: ProviderSetting?,
    onDismiss: () -> Unit,
    onConfirm: (keepRecentMessages: Int) -> Job,
) {
    var keepRecentMessages by remember { mutableIntStateOf(DEFAULT_KEEP_RECENT_MESSAGES) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    val stats by produceState<HistoryTrimStats?>(initialValue = null, messages, provider) {
        value = withContext(Dispatchers.IO) { messages.inspectHistoryTrim(provider) }
    }

    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    val totalMessages = messages.size
    val cutoffIndex = (totalMessages - keepRecentMessages.coerceAtLeast(0)).coerceAtLeast(0)
    val targets = remember(stats, cutoffIndex) {
        stats?.steps.orEmpty().filter { it.messageIndex < cutoffIndex && !it.trimmed }
    }
    val freedBytes = remember(targets) { targets.sumOf { it.savedBytes } }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.chat_trim_history)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (isLoading || stats == null) {
                    Row(
                        modifier = Modifier.height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ContainedLoadingIndicator()
                        Text(stringResource(R.string.chat_trim_history))
                    }
                } else {
                    val trimStats = stats!!
                    Text(
                        text = stringResource(R.string.chat_trim_history_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = trimStats.activeBytes.formatBytes(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.chat_trim_history_count,
                                trimStats.totalCount,
                                trimStats.trimmedCount,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (targets.isNotEmpty()) {
                                stringResource(
                                    R.string.chat_trim_history_preview,
                                    targets.size,
                                    freedBytes.formatBytes(),
                                )
                            } else {
                                stringResource(R.string.chat_trim_history_nothing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_compact_attachments_keep, keepRecentMessages),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalIconButton(
                                onClick = { keepRecentMessages-- },
                                enabled = keepRecentMessages > 0,
                            ) {
                                Icon(HugeIcons.Remove01, contentDescription = "Decrease")
                            }
                            Text(
                                text = keepRecentMessages.toString(),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 40.dp),
                            )
                            FilledTonalIconButton(onClick = { keepRecentMessages++ }) {
                                Icon(HugeIcons.Add01, contentDescription = "Increase")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(
                    enabled = targets.isNotEmpty(),
                    onClick = { currentJob = onConfirm(keepRecentMessages) },
                ) {
                    Text(stringResource(R.string.chat_trim_history_confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

private const val DEFAULT_KEEP_RECENT_MESSAGES = 4
