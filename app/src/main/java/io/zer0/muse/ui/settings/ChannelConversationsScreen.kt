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
import io.zer0.muse.channel.ChannelInbox
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
    onOpenConversation: (String) -> Unit,
) {
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val entries = remember(conversations, channelId) {
        conversations
            .filterKeys { key -> key.startsWith("$channelId|") }
            .map { (key, conversation) -> key.substringAfter("|") to conversation }
            .sortedByDescending { (_, conversation) -> conversation.updatedAt }
    }
    var resetTarget by remember { mutableStateOf<String?>(null) }
    // 最近一次失败的派发：错误码此前只落库不展示，用户只能猜“它为什么不回”。
    // 群聊里「不在群里」和「被动回复超时」的处置完全不同，必须显出来。
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle()
    val lastFailure = remember(inbox, channelId) {
        inbox
            .filter { item -> item.sourceChannelId == channelId && item.dispatchErrorCode.isNotBlank() }
            .maxByOrNull { item -> item.timestamp }
            ?.dispatchErrorCode
            .orEmpty()
    }

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
                if (lastFailure.isNotBlank()) {
                    item(key = "last_failure") { FailureBanner(lastFailure) }
                }
                if (entries.isEmpty()) {
                    item(key = "empty") { EmptyHint() }
                }
                items(entries, key = { (from, _) -> from }) { (from, conversation) ->
                    ConversationCard(
                        from = from,
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

/** 重启上下文确认。抽出只为控制主函数长度，逻辑本身没分支。 */
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
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * 单个联系人的入口卡。
 *
 * 只回答三件事：谁、最后说了什么、什么时候。正文详情点进去看 ——
 * 摊在列表里既读不出上下文，也把列表变成一堵字墙。
 */
@Composable
private fun ConversationCard(
    from: String,
    conversation: ChannelConversationStore.Conversation,
    onOpen: () -> Unit,
    onReset: () -> Unit,
) {
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(MusePaddings.cardInner),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = prettifyContactLabel(from),
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
                        // LAYOUT-01: 右侧动作是定宽短标注(剩余宽度已归左侧联系人名的 weight),限 1 行兜底。
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                LastTurnPreview(lastTurn)
                if (conversation.summary.isNotBlank()) {
                    SummaryLine(conversation.summary)
                }
            }
        }
    }
}

/**
 * 联系人 id 展示美化。
 *
 * 群目标存成 `group:<openid>`，直接显示会带前缀且超长，
 * 这里把前缀转成可读标签并对长 id 保留首尾。
 */
internal fun prettifyContactLabel(raw: String): String {
    val isGroup = raw.startsWith("group:")
    val core = raw.removePrefix("group:")
    val shown = if (core.length <= 20) core else core.take(10) + "…" + core.takeLast(6)
    return if (isGroup) "群 $shown" else shown
}

/** 最近一次发送失败提示。放列表最上方，因为它是"为什么它不回"的直接答案。 */
@Composable
private fun FailureBanner(message: String) {
    Text(
        text = stringResource(R.string.channel_last_failure, message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 空态。单独成函数只是为了不让主函数继续变长。 */
@Composable
private fun EmptyHint() {
    Text(
        text = stringResource(R.string.channel_conversations_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 32.dp),
    )
}

/** 最后一条消息的预览。 */
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
            current.text.ifBlank { "[附件]" },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** 更早对话的滚动摘要。 */
@Composable
private fun SummaryLine(summary: String) {
    Text(
        text = stringResource(R.string.channel_conversation_summary, summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}
