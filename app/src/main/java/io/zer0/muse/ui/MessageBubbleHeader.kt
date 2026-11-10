package io.zer0.muse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.chat.ChatContextDivider
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MusePaddings

@Composable
internal fun MessageBubbleHeader(
    message: UIMessage,
    showAvatar: Boolean,
    showTimestamp: Boolean,
    chatPreferences: ChatPreferences,
    assistant: AssistantEntity?,
    showDateSeparator: Boolean,
    showContextDivider: Boolean,
    contextCheckpointTotalCovered: Int,
    contextDividerExpanded: Boolean,
    onToggleContextDivider: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxWidth(),
    ) {
        if (showDateSeparator) {
            DateSeparator(timestamp = message.createdAt)
        }
        if (showContextDivider) {
            ChatContextDivider(
                totalCovered = contextCheckpointTotalCovered,
                expanded = contextDividerExpanded,
                onToggle = onToggleContextDivider,
            )
        }
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .testTag(MessageBubbleHeader.IDENTITY_TEST_TAG)
                .padding(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    bottom = MusePaddings.tinyGap,
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAvatar && message.role != MessageRole.USER) {
                AssistantAvatar(
                    assistant = assistant ?: AssistantEntity(id = "default", name = "Muse"),
                    avatarSize = MuseAvatarSize.inline,
                )
                val showName = chatPreferences.showAssistantName
                val showTime = showTimestamp && chatPreferences.showTimestamp
                if (showName || showTime) {
                    Spacer(Modifier.width(MusePaddings.contentGap))
                    Text(
                        text =
                        when {
                            showName && showTime ->
                                "${assistant?.name?.takeIf { it.isNotBlank() } ?: "Muse"} · " +
                                    formatMessageTime(message.createdAt, use24Hour = chatPreferences.use24Hour)
                            showName -> assistant?.name?.takeIf { it.isNotBlank() } ?: "Muse"
                            else -> formatMessageTime(message.createdAt, use24Hour = chatPreferences.use24Hour)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal object MessageBubbleHeader {
    const val IDENTITY_TEST_TAG = "message-header-identity"
}
