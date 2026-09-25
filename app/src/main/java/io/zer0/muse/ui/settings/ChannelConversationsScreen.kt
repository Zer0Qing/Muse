package io.zer0.muse.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConversationStore
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.0.1: 渠道对话页 — 按渠道查看各联系人的对话记录与上下文状态。
 *
 * 数据源:[ChannelConversationStore](与自动回复同一份存储);
 * 每个联系人一段对话,支持展开查看最近消息,可"重启上下文"(清空该对话历史与摘要)。
 */
@Composable
fun ChannelConversationsScreen(
    channelId: String,
    onBack: () -> Unit,
) {
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val entries = remember(conversations, channelId) {
        conversations
            .filterKeys { key -> key.startsWith("$channelId|") }
            .map { (key, conversation) -> key.substringAfter("|") to conversation }
            .sortedByDescending { (_, conversation) -> conversation.updatedAt }
    }
    var resetTarget by remember { mutableStateOf<String?>(null) }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.channel_conversations_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v2.0.1: 沿用设置域既有防抖模式(BoxWithConstraints 收口有限高度)。
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
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    top = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.channel_conversations_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 32.dp),
                        )
                    }
                }
                items(entries, key = { (from, _) -> from }) { (from, conversation) ->
                    ConversationCard(
                        from = from,
                        conversation = conversation,
                        onReset = { resetTarget = from },
                    )
                }
            }
        }
    }

    resetTarget?.let { from ->
        MuseDialog(
            onDismissRequest = { resetTarget = null },
            title = stringResource(R.string.channel_conversation_reset_confirm_title),
            content = { Text(stringResource(R.string.channel_conversation_reset_confirm_text)) },
            confirmText = stringResource(R.string.channel_conversation_reset),
            onConfirm = {
                ChannelConversationStore.clear(channelId, from)
                resetTarget = null
            },
            destructive = true,
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { resetTarget = null },
        )
    }
}

/** 单个联系人的对话卡:头部 + 摘要/预览 + 展开明细 + 重启上下文。 */
@Composable
private fun ConversationCard(
    from: String,
    conversation: ChannelConversationStore.Conversation,
    onReset: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val turns = conversation.turns
    val lastTurn = turns.lastOrNull()
    val timeText = remember(conversation.updatedAt) {
        if (conversation.updatedAt <= 0L) {
            ""
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(conversation.updatedAt))
        }
    }

    CardGroup(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = prettifyContact(from),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.channel_conversation_reset),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable(onClick = onReset)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.channel_conversation_meta, turns.size, timeText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (conversation.summary.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.channel_conversation_summary, conversation.summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (expanded) {
                    turns.takeLast(EXPANDED_TURNS).forEach { turn ->
                        val roleLabel =
                            stringResource(
                                if (turn.role == "assistant") {
                                    R.string.channel_conversation_role_assistant
                                } else {
                                    R.string.channel_conversation_role_user
                                },
                            )
                        Text(
                            text = stringResource(R.string.channel_conversation_turn_line, roleLabel, turn.text),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (turn.role == "assistant") {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else if (lastTurn != null) {
                    val roleLabel =
                        stringResource(
                            if (lastTurn.role == "assistant") {
                                R.string.channel_conversation_role_assistant
                            } else {
                                R.string.channel_conversation_role_user
                            },
                        )
                    Text(
                        text = stringResource(R.string.channel_conversation_turn_line, roleLabel, lastTurn.text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (turns.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            if (expanded) {
                                R.string.channel_conversation_collapse
                            } else {
                                R.string.channel_conversation_expand
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clickable { expanded = !expanded }
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** 联系人 id 展示美化 — 过长时保留首尾。 */
private fun prettifyContact(raw: String): String =
    if (raw.length <= 24) raw else raw.take(12) + "…" + raw.takeLast(6)

/** v2.0.1: 展开时最多显示的轮次数。 */
private const val EXPANDED_TURNS = 30
