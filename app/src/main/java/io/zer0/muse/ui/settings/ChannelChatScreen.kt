package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConversationStore
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单个联系人的会话流。
 *
 * 为什么要有这一页：之前所有联系人在一个列表里平铺，每条消息是「角色：正文」一行，
 * 聊得久了根本看不出上下文是怎么接的。这里改成气泡流 —— 谁说的、先后顺序、
 * 隔了多久，一眼能看出来。
 *
 * 这一页只看不发：渠道回复由自动回复链路负责，用户在这里回消息
 * 会绕开派发台账，反而制造不一致。
 */
@Composable
fun ChannelChatScreen(channelId: String, from: String, onBack: () -> Unit) {
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val conversation = conversations["$channelId|$from"]
    val turns = conversation?.turns.orEmpty()

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = prettifyContactLabel(from),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MusePaddings.screen,
                end = MusePaddings.screen,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (turns.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.channel_chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 32.dp),
                    )
                }
            } else {
                // 这页没有输入框：说清楚是只看，否则用户会以为能在这儿回。
                item(key = "hint") {
                    Text(
                        text = stringResource(R.string.channel_chat_readonly_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
            itemsIndexed(
                items = turns,
                // 不用 at 做 key：同毫秒可能重复，重复 key 会让 LazyColumn 直接崩。
                key = { index, _ -> "turn_$index" },
            ) { index, turn ->
                val previous = turns.getOrNull(index - 1)
                // 隔了 10 分钟以上才插一个时间分隔，否则整屏都是时间戳
                if (previous == null || turn.at - previous.at > TIME_GAP_MS) {
                    Text(
                        text = formatTime(turn.at),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                ChannelBubble(turn = turn)
            }
        }
    }
}

/** 单条消息气泡。助手靠左、用户靠右，和主聊天界面同一套视觉直觉。 */
@Composable
private fun ChannelBubble(turn: ChannelConversationStore.Turn) {
    val isAssistant = turn.role == "assistant"
    val body = remember(turn) { turnBody(turn) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH_FRACTION),
            horizontalAlignment = if (isAssistant) Alignment.Start else Alignment.End,
        ) {
            Text(
                text = stringResource(
                    if (isAssistant) {
                        R.string.channel_conversation_role_assistant
                    } else {
                        R.string.channel_conversation_role_user
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            androidx.compose.material3.Surface(
                shape = MuseShapes.medium,
                color = if (isAssistant) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isAssistant) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 气泡正文。
 *
 * 媒体消息在渠道里存的是 base64 或本地路径，这里不还原图片 ——
 * 这一页是「看对话怎么接的」，不是图库；图片给一行占位说明就够。
 * 视觉降级的描述如果有，优先用它，那本来就是给模型看的文字版。
 */
private fun turnBody(turn: ChannelConversationStore.Turn): String = when {
    turn.mediaDescription.isNotBlank() -> turn.mediaDescription
    turn.mediaKind == "image" -> "[图片]"
    turn.mediaPath.isNotBlank() -> "[附件]"
    turn.text.isNotBlank() -> turn.text
    else -> "[空消息]"
}

private fun formatTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

/** 气泡最多占屏宽的多少。留出对侧空白，才看得出是「谁说的」。 */
private const val BUBBLE_WIDTH_FRACTION = 0.82f

/** 超过这个间隔才插时间分隔。 */
private const val TIME_GAP_MS = 10 * 60 * 1000L
