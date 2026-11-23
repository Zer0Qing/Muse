package io.zer0.muse.ui.agentbot

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.BotAnnotationEntity
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpringTier
import io.zer0.muse.ui.theme.pill

private val QUICK_REACTION_EMOJIS = listOf("👌", "👍", "❤️", "👀", "🤔", "🎉")

/**
 * 沉浸式对话气泡，支持气泡底角压边悬浮 Emoji 反应贴纸、内联来源链接胶囊与长按快捷菜单。
 *
 * 移除了旧版每条消息旁的常驻三点按钮，释放横向正文阅读宽度并降低视觉噪音。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AgentMessageBubble(
    message: AgentBotUiState.PublishedMessage,
    reactions: List<AgentBotUiState.MessageReactionUi>,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onRemoveReaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == AgentBotUiState.Role.USER
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var menuExpanded by rememberSaveable(message.messageId) { mutableStateOf(false) }
    val inlineLinks = remember(message.text) { extractInlineSourceLinks(message.text) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                bottom = if (reactions.isNotEmpty()) MusePaddings.auxGap else MusePaddings.tinyGap,
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Box(
                modifier = Modifier.widthIn(max = MuseBubbleStyles.maxBubbleWidth()),
            ) {
                Surface(
                    color = if (isUser) {
                        MuseBubbleStyles.userSurfaceColor()
                    } else {
                        MuseBubbleStyles.assistantSurfaceColor()
                    },
                    contentColor = if (isUser) {
                        MuseBubbleStyles.userContentColor()
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    shape = if (isUser) {
                        MuseBubbleStyles.userBubbleShape()
                    } else {
                        MuseBubbleStyles.assistantBubbleShape()
                    },
                    border = BorderStroke(
                        width = MuseBorderWidth.hairline,
                        color = if (isUser) {
                            MuseBubbleStyles.userBorderColor()
                        } else {
                            MuseBubbleStyles.assistantBorderColor()
                        },
                    ),
                    modifier = Modifier
                        .padding(
                            bottom = if (reactions.isNotEmpty()) MusePaddings.contentGap else MusePaddings.tinyGap,
                        )
                        .clip(
                            if (isUser) {
                                MuseBubbleStyles.userBubbleShape()
                            } else {
                                MuseBubbleStyles.assistantBubbleShape()
                            },
                        )
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuExpanded = true
                            },
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(MusePaddings.bubbleInner),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        val imageModels = message.imageUrls + message.imageBase64List.map {
                            "data:image/jpeg;base64,$it"
                        }
                        if (imageModels.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            ) {
                                itemsIndexed(
                                    items = imageModels,
                                    key = { index, img -> "$index:${img.hashCode()}" },
                                ) { _, image ->
                                    AsyncImage(
                                        model = image,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(MusePaddings.previewThumb)
                                            .clip(MuseShapes.medium),
                                    )
                                }
                            }
                        }
                        if (message.text.isNotBlank()) {
                            MarkdownText(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isUser) {
                                    MuseBubbleStyles.userContentColor()
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        if (!isUser && inlineLinks.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.labelVerticalGap),
                                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                            ) {
                                inlineLinks.forEach { link ->
                                    InlineSourceChip(
                                        label = link.label,
                                        onClick = {
                                            runCatching {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                                                context.startActivity(intent)
                                            }.onFailure {
                                                Logger.w("AgentBubble", "打开来源链接失败: ${it.message}")
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 气泡底角压边悬浮 Emoji 反应贴纸
                androidx.compose.animation.AnimatedVisibility(
                    visible = reactions.isNotEmpty(),
                    enter = fadeIn() + scaleIn(
                        animationSpec = MuseMotion.springSpec(MuseSpringTier.BOUNCY),
                        initialScale = 0.6f,
                    ),
                    exit = fadeOut() + scaleOut(targetScale = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = MusePaddings.tightGap),
                ) {
                    ReactionCornerBadge(
                        reactions = reactions,
                        onTapReaction = { reaction ->
                            if (reaction.actorType == BotAnnotationEntity.ACTOR_USER) {
                                onRemoveReaction(reaction.id)
                            } else {
                                menuExpanded = true
                            }
                        },
                    )
                }
            }

            // 长按操作菜单：顶部快捷 Emoji 反应条 + 复制 / 重试 / 删除
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = MusePaddings.contentGap,
                        vertical = MusePaddings.tightGap,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QUICK_REACTION_EMOJIS.forEach { emoji ->
                        Surface(
                            onClick = {
                                menuExpanded = false
                                onReact(emoji)
                            },
                            shape = MuseShapes.pill,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        ) {
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(
                                    horizontal = MusePaddings.contentGap,
                                    vertical = MusePaddings.tightGap,
                                ),
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = MusePaddings.tightGap))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.agent_screen_copy)) },
                    leadingIcon = { Icon(MuseIcons.copy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onCopy()
                    },
                )
                if (!isUser) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_screen_retry_message)) },
                        leadingIcon = { Icon(MuseIcons.refresh, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRetry()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.agent_screen_delete_message)) },
                    leadingIcon = { Icon(MuseIcons.trash, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun ReactionCornerBadge(
    reactions: List<AgentBotUiState.MessageReactionUi>,
    onTapReaction: (AgentBotUiState.MessageReactionUi) -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.medium,
        border = BorderStroke(
            width = MuseBorderWidth.thin,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder),
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.tinyGap,
            ),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            reactions.take(3).forEach { item ->
                Text(
                    text = item.emoji,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { onTapReaction(item) },
                )
            }
        }
    }
}

@Composable
private fun InlineSourceChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(
            width = MuseBorderWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.tightGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Icon(
                imageVector = MuseIcons.globe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = MusePaddings.maxInlineWidth),
            )
        }
    }
}

/** 正在流式输出的回复气泡（含可折叠思考过程）。 */
@Composable
fun AgentStreamingBubble(
    streaming: AgentBotUiState.StreamingBubble,
    modifier: Modifier = Modifier,
) {
    if (!streaming.active) return
    var reasoningExpanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MuseBubbleStyles.assistantSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MuseBubbleStyles.assistantBubbleShape(),
            border = BorderStroke(
                width = MuseBorderWidth.hairline,
                color = MuseBubbleStyles.assistantBorderColor(),
            ),
            modifier = Modifier.widthIn(max = MuseBubbleStyles.maxBubbleWidth()),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.bubbleInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                if (streaming.reasoning.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reasoningExpanded = !reasoningExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        Icon(
                            imageVector = MuseIcons.sparkle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                        Text(
                            text = stringResource(R.string.agent_hub_thinking),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (reasoningExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                    }
                    if (reasoningExpanded) {
                        Text(
                            text = streaming.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (streaming.text.isNotBlank()) {
                    MarkdownText(
                        text = streaming.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class SourceLinkItem(val label: String, val url: String)

private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]{1,40})]\\((https?://[^)\\s]+)\\)")

private fun extractInlineSourceLinks(text: String): List<SourceLinkItem> {
    if (!text.contains("http")) return emptyList()
    return MARKDOWN_LINK_REGEX.findAll(text)
        .map { match ->
            SourceLinkItem(
                label = match.groupValues[1].trim(),
                url = match.groupValues[2].trim(),
            )
        }
        .distinctBy { it.url }
        .take(3)
        .toList()
}
