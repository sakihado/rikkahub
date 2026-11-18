package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.AssistantStopReason
import me.rerere.rikkahub.R

@Composable
internal fun BreakpointCard(reason: AssistantStopReason?, onContinue: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(when (reason) {
                AssistantStopReason.STEP_LIMIT -> R.string.agent_step_limit
                AssistantStopReason.TRUNCATED -> R.string.agent_truncated
                else -> R.string.agent_interrupted
            }))
            TextButton(onClick = onContinue) { Text(stringResource(R.string.agent_continue)) }
        }
    }
}
