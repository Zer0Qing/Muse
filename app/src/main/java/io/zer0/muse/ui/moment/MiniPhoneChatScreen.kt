package io.zer0.muse.ui.moment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Send
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MuseIconSizes
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小手机内的微信风格聊天页。
 *
 * 行为：进入时定位到与 assistantId 对应的非归档会话（有则 switch，无则 create + retry + switch）。
 * 消息列表跟随 vm.messages（已过滤 SYSTEM），流式等待时最后一条助手消息末尾加主题色指示器。
 */
@Composable
fun MiniPhoneChatScreen(
    assistantId: String,
    assistantName: String,
    assistantAvatar: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ChatViewModel = koinViewModel()
    val sessionRepo: SessionRepository = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    var inputText by remember { mutableStateOf("") }

    // 进入页面时定位会话（异步，不阻塞 UI）
    LaunchedEffect(assistantId) {
        val existing = state.sessions.firstOrNull { it.assistantId == assistantId && !it.archived }
        if (existing != null) {
            vm.switchSession(existing.id)
        } else {
            val id = sessionRepo.createSession(assistantId)
            vm.retryLoadSessions()
            vm.switchSession(id)
        }
    }

    // 新消息到达或发送后自动滚底
    LaunchedEffect(messages.lastOrNull()?.id, state.isStreaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .fillMaxSize(0.94f)
                .padding(horizontal = 14.dp)
                .shadow(24.dp, RoundedCornerShape(30.dp)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 状态栏 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 20.dp, end = 18.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Filled.BatteryFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp),
                    )
                }

                // ── 标题栏 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = TablerIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = assistantName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // ⋮ 占位（不实现功能）
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.touchTarget)
                            .padding(12.dp),
                    )
                }

                // ── 消息区 ──
                if (messages.isEmpty() && !state.isSessionsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseEmptyState(
                            title = stringResource(R.string.miniphone_chat_empty_title),
                            subtitle = null,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        reverseLayout = false,
                    ) {
                        items(
                            items = messages.filter { it.role != MessageRole.SYSTEM },
                            key = { it.id.toString() },
                        ) { msg ->
                            MessageBubble(
                                message = msg,
                                avatarUrl = assistantAvatar,
                                avatarSeed = assistantName,
                                isStreaming = state.isStreaming,
                                isLastAssistant = msg.role == MessageRole.ASSISTANT &&
                                    messages.filter { it.role != MessageRole.SYSTEM }.lastOrNull { it.role == MessageRole.ASSISTANT }?.id == msg.id,
                            )
                        }
                    }
                }

                // ── 底部输入栏 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                    ) {
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            decorationBox = { innerTextField ->
                                if (inputText.isBlank()) {
                                    Text(
                                        text = stringResource(R.string.miniphone_chat_input_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                innerTextField()
                            },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                vm.updateInput(inputText)
                                vm.send()
                                inputText = ""
                                keyboard?.hide()
                            }
                        },
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = TablerIcons.Send,
                            contentDescription = stringResource(R.string.miniphone_chat_send_cd),
                            tint = if (inputText.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条消息气泡。
 *
 * - ASSISTANT：靠左，白底 surface 色，左侧带头像
 * - USER：靠右，主题 primary 底 + onPrimary 字
 * - 流式中最后一条助手消息末尾追加 CircularProgressIndicator
 */
@Composable
private fun MessageBubble(
    message: UIMessage,
    avatarUrl: String?,
    avatarSeed: String,
    isStreaming: Boolean,
    isLastAssistant: Boolean,
) {
    val isUser = message.role == MessageRole.USER
    val content = if (isUser) {
        message.content
    } else {
        message.content.ifBlank { message.reasoning ?: "" }
    }
    if (content.isBlank() && !isUser) return

    val cornerRadii = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isUser) {
            AvatarBubble(
                avatarUrl = avatarUrl,
                seed = avatarSeed,
                size = 36.dp,
                modifier = Modifier
                    .align(Alignment.Bottom)
                    .padding(end = 6.dp),
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(cornerRadii)
                .background(if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                if (isLastAssistant && isStreaming) {
                    // 流式等待指示器：主题色小圆圈，无新字符串
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (isUser) {
            Spacer(Modifier.width(6.dp))
        }
    }
}

/**
 * 头像：有图用图，没图用名字首字（私有版本，与 MiniPhoneScreen 里的同名函数隔离）。
 */
@Composable
private fun AvatarBubble(
    avatarUrl: String?,
    seed: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            io.zer0.muse.ui.SmartImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = seed.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
