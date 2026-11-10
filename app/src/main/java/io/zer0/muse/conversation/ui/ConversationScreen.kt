package io.zer0.muse.conversation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons

@Composable
fun ConversationComposer(
    input: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    isSubmitting: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_placeholder_simple)) },
            minLines = 1,
            maxLines = 5,
        )
        val sendDescription = stringResource(R.string.action_send)
        IconButton(
            onClick = onSend,
            enabled = input.isNotBlank() && !isSubmitting,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("conversation-send")
                .semantics { contentDescription = sendDescription },
        ) {
            Icon(
                imageVector = MuseIcons.send,
                contentDescription = null,
                tint =
                if (input.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
    }
}

@Composable
fun ConversationScreen(
    model: ConversationPageModel?,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isSubmitting: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    if (model == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.testTag("conversation-loading"),
                )
            } else {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                if (onRetry != null) {
                    androidx.compose.material3.TextButton(
                        onClick = onRetry,
                        modifier = Modifier.padding(top = 72.dp),
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
        return
    }
    val state by model.state.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (state.richTimeline.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_empty_guide_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                ConversationRichTimeline(rows = state.richTimeline)
            }
        }
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        ConversationComposer(
            input = state.input,
            onValueChange = model::updateInput,
            onSend = {
                val text = state.input
                if (text.isNotBlank()) {
                    onSend(text)
                }
            },
            isSubmitting = isSubmitting,
        )
    }
}
