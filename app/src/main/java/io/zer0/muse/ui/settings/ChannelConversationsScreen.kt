package io.zer0.muse.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelContactMetaStore
import io.zer0.muse.channel.ChannelConversationStore
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.channel.ChannelManager
import io.zer0.muse.channel.ChannelPlatform
import io.zer0.muse.channel.ChannelRuntimeRegistry
import io.zer0.muse.ui.channel.ChannelTagPill
import io.zer0.muse.ui.channel.ContactAvatarWithPlatform
import io.zer0.muse.ui.channel.LiveStatusPill
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.statusColors
import org.koin.compose.koinInject
import java.util.Date

/**
 * 渠道会话列表页 — 按单渠道查看联系人会话记录、实时连接状态、接管标志与失败重试。
 */
@Composable
fun ChannelConversationsScreen(channelId: String, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    val context = LocalContext.current
    val manager: ChannelManager = koinInject()
    val channels by manager.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val statuses by ChannelRuntimeRegistry.statuses.collectAsStateWithLifecycle()
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val metas by ChannelContactMetaStore.metas.collectAsStateWithLifecycle()
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        manager.refresh()
        ChannelInbox.attach(context)
        ChannelConversationStore.attach(context)
        ChannelContactMetaStore.attach(context)
    }

    val channelConfig = channels.firstOrNull { it.id == channelId }
    val platform = channelConfig?.platform ?: ChannelPlatform.FEISHU
    val groupLabel = stringResource(R.string.channel_contact_group_label)

    val entries = remember(conversations, channelId) {
        conversations
            .filterKeys { key -> key.startsWith("$channelId|") }
            .map { (key, conversation) -> key.substringAfter("|") to conversation }
            .sortedByDescending { (_, conversation) -> conversation.updatedAt }
    }
    var resetTarget by remember { mutableStateOf<String?>(null) }
    val blockedLatest = remember(inbox, channelId) {
        inbox
            .filter {
                it.sourceChannelId == channelId &&
                    it.dispatchState == ChannelInbox.DispatchState.BLOCKED &&
                    it.dispatchErrorCode.isNotBlank()
            }
            .maxByOrNull { it.timestamp }
    }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = channelConfig?.name?.ifBlank { null } ?: stringResource(R.string.channel_conversations_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val boundedHeight = boundedSettingsScrollHeight(
                parentMaxHeight = maxHeight,
                windowHeight = LocalConfiguration.current.screenHeightDp.dp,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = boundedHeight),
                contentPadding = PaddingValues(
                    start = MusePaddings.pageMargin,
                    end = MusePaddings.pageMargin,
                    top = MusePaddings.contentGap,
                    bottom = MusePaddings.largeGap,
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                if (channelConfig != null) {
                    item(key = "channel_status_bar") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        ) {
                            LiveStatusPill(
                                enabled = channelConfig.enabled,
                                status = statuses[channelId],
                            )
                            Text(
                                text = stringResource(R.string.channel_card_chats_count, entries.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (blockedLatest != null) {
                    item(key = "last_failure") {
                        FailureBanner(
                            message = blockedLatest.dispatchErrorCode,
                            onRetry = {
                                if (ChannelInbox.retryBlocked(blockedLatest.dispatchId)) {
                                    MuseToast.show(context.getString(R.string.channel_retry_enqueued))
                                }
                            },
                        )
                    }
                }
                if (entries.isEmpty()) {
                    item(key = "empty") { EmptyHint() }
                }
                items(entries, key = { (from, _) -> from }) { (from, conversation) ->
                    val meta = metas[ChannelContactMetaStore.key(channelId, from)]
                    val resolvedName = ChannelContactMetaStore.resolvedName(channelId, from, groupLabel)
                    val isGroup = meta?.isGroup == true || from.startsWith("group:")
                    ConversationCard(
                        displayName = resolvedName,
                        platform = platform,
                        isGroup = isGroup,
                        aiPaused = meta?.aiPaused == true,
                        conversation = conversation,
                        onOpen = { onOpenConversation(from) },
                        onReset = { resetTarget = from },
                    )
                }
            }
        }
    }

    ResetContextDialog(
        target = resetTarget,
        onConfirm = { from ->
            ChannelConversationStore.clear(channelId, from)
            resetTarget = null
        },
        onDismiss = { resetTarget = null },
    )
}

@Composable
private fun ResetContextDialog(target: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val from = target ?: return
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_conversation_reset_confirm_title),
        content = { Text(stringResource(R.string.channel_conversation_reset_confirm_text)) },
        confirmText = stringResource(R.string.channel_conversation_reset),
        onConfirm = { onConfirm(from) },
        destructive = true,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
private fun ConversationCard(
    displayName: String,
    platform: ChannelPlatform,
    isGroup: Boolean,
    aiPaused: Boolean,
    conversation: ChannelConversationStore.Conversation,
    onOpen: () -> Unit,
    onReset: () -> Unit,
) {
    val turns = conversation.turns
    val lastTurn = turns.lastOrNull()
    val timeText = remember(conversation.updatedAt) {
        if (conversation.updatedAt <= 0L) "" else MuseDateFormats.dateTimeShort().format(Date(conversation.updatedAt))
    }
    val colors = MaterialTheme.statusColors

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            MuseBorderWidth.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
        ),
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            ContactAvatarWithPlatform(
                displayName = displayName,
                platform = platform,
                isGroup = isGroup,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (aiPaused) {
                        ChannelTagPill(
                            text = stringResource(R.string.channel_badge_manual_paused),
                            containerColor = colors.warningContainer,
                            contentColor = colors.warning,
                        )
                    }
                    MuseCapsuleButton(
                        text = stringResource(R.string.channel_conversation_reset),
                        onClick = onReset,
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        size = MuseButtonSize.Compact,
                    )
                }
                Text(
                    text = stringResource(R.string.channel_conversation_meta, turns.size, timeText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LastTurnPreview(lastTurn)
                if (conversation.summary.isNotBlank()) {
                    SummaryLine(conversation.summary)
                }
            }
        }
    }
}

/** 联系人 id 展示美化。 */
internal fun prettifyContactLabel(raw: String, groupLabel: String = "Group"): String {
    val isGroup = raw.startsWith("group:")
    val core = raw.removePrefix("group:")
    val shown = if (core.length <= 20) core else core.take(10) + "…" + core.takeLast(6)
    return if (isGroup) groupLabel + " " + shown else shown
}

@Composable
private fun FailureBanner(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.statusColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        color = colors.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.channel_last_failure, message),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.channel_card_retry_now),
                onClick = onRetry,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = stringResource(R.string.channel_conversations_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(MusePaddings.largeGap),
    )
}

@Composable
private fun LastTurnPreview(turn: ChannelConversationStore.Turn?) {
    val current = turn ?: return
    val roleLabel = stringResource(
        if (current.role == "assistant") {
            R.string.channel_conversation_role_assistant
        } else {
            R.string.channel_conversation_role_user
        },
    )
    Text(
        text = stringResource(
            R.string.channel_conversation_turn_line,
            roleLabel,
            current.text.ifBlank { stringResource(R.string.channel_conversation_attachment_placeholder) },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SummaryLine(summary: String) {
    Text(
        text = stringResource(R.string.channel_conversation_summary, summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
