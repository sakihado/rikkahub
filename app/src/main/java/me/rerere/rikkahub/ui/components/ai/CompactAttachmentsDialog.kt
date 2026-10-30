package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.util.AttachmentSize
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.formatBytes

/**
 * 「压缩附件」确认弹窗：显示省略后能释放多少体积，并允许保留最近若干条消息。
 */
@Composable
fun CompactAttachmentsDialog(
    // 按出现顺序（旧 → 新）
    attachments: List<AttachmentSize>,
    currentBytes: Long,
    limitMb: Int,
    onDismiss: () -> Unit,
    onConfirm: (keepRecentMessages: Int) -> Job,
) {
    var keepRecentMessages by remember { mutableIntStateOf(DEFAULT_KEEP_RECENT_MESSAGES) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    val messageIndexes = remember(attachments) { attachments.map { it.messageIndex }.distinct() }
    val keepSet = remember(messageIndexes, keepRecentMessages) {
        messageIndexes.takeLast(keepRecentMessages).toSet()
    }
    val targets = remember(attachments, keepSet) {
        attachments.filter { it.messageIndex !in keepSet && !it.omitted }
    }
    val freedBytes = remember(targets) { targets.sumOf { it.bytes } }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.chat_compact_attachments)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ContainedLoadingIndicator()
                        Text(stringResource(R.string.chat_compact_attachments))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.chat_compact_attachments_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (limitMb > 0) {
                                "${currentBytes.formatBytes()} / ${limitMb}MB"
                            } else {
                                currentBytes.formatBytes()
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (targets.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.chat_compact_attachments_preview,
                                    targets.size,
                                    freedBytes.formatBytes(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                    Text(stringResource(R.string.chat_compact_attachments_confirm))
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

private const val DEFAULT_KEEP_RECENT_MESSAGES = 6
