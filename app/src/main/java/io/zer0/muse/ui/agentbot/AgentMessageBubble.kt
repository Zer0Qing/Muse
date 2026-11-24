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
import androidx.compose.foundation.background
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
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.util.ShareIntentHelper
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.ui.theme.LocalLiquidGlass
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpringTier
import io.zer0.muse.ui.theme.glassBorder
import io.zer0.muse.ui.theme.glassEdgeHighlight
import io.zer0.muse.ui.theme.glassFakeSurfaceColor
import io.zer0.muse.ui.theme.pill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_REACTION_EMOJIS = listOf("👌", "👍", "❤️", "👀", "🤔", "🎉")

/**
 * 沉浸式对话气泡，支持气泡底角压边悬浮 Emoji 反应贴纸、内联来源链接胶囊与长按快捷菜单。
 *
 * 与主对话气泡共用 [ChatPreferences]（圆角、字号缩放、字距、通栏、时间戳、模型名、思考展开）与假液态玻璃质感。
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
    onQuote: () -> Unit = {},
    onEdit: () -> Unit = {},
    onSaveAsRoutine: () -> Unit = {},
    modifier: Modifier = Modifier,
    chatPreferences: ChatPreferences = ChatPreferences(),
    modelLabel: String = "",
    onOpenMediaViewer: ((List<String>, Int) -> Unit)? = null,
) {
    val isUser = message.role == AgentBotUiState.Role.USER
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val glassConfig = LocalLiquidGlass.current
    var menuExpanded by remember(message.messageId) { mutableStateOf(false) }
    var showSelectTextDialog by remember(message.messageId) { mutableStateOf(false) }
    var reasoningExpanded by rememberSaveable(message.messageId) {
        mutableStateOf(chatPreferences.reasoningExpandedByDefault)
    }
    var moodExpanded by rememberSaveable(message.messageId) {
        mutableStateOf(chatPreferences.moodExpandedByDefault)
    }
    val visibleMood = remember(message.mood, message.text, message.reasoning, isUser) {
        if (isUser) {
            null
        } else {
            message.mood.takeIf { it.isNotBlank() }
                ?: io.zer0.muse.transformer.InternalMarkupSanitizer.extractMood(message.text)
                ?: io.zer0.muse.transformer.InternalMarkupSanitizer.extractMood(message.reasoning)
        }
    }
    val displayText = remember(message.text, isUser) {
        if (isUser) {
            message.text
        } else {
            io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(message.text)
        }
    }
    val inlineLinks = remember(displayText) { extractInlineSourceLinks(displayText) }
    val bubbleRadius = chatPreferences.bubbleRadius.coerceIn(0, 28).toFloat()
    val bubbleShape = if (isUser) {
        MuseBubbleStyles.userBubbleShape(bubbleRadius)
    } else {
        MuseBubbleStyles.assistantBubbleShape(bubbleRadius)
    }
    val baseSurfaceColor = if (isUser) {
        MuseBubbleStyles.userSurfaceColor()
    } else {
        MuseBubbleStyles.assistantSurfaceColor()
    }
    val contentColor = if (isUser) {
        MuseBubbleStyles.userContentColor()
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val useFakeGlass = glassConfig.enabled
    val monoFlatGlass = useFakeGlass && MuseBubbleStyles.isMonoBubbleTheme()
    val glassBubbleModifier = when {
        !useFakeGlass -> Modifier
        monoFlatGlass -> Modifier
            .clip(bubbleShape)
            .glassEdgeHighlight(bubbleShape, glassConfig)
            .glassBorder(bubbleShape, glassConfig)
        else -> Modifier
            .clip(bubbleShape)
            .background(glassFakeSurfaceColor(baseSurfaceColor, glassConfig))
            .glassEdgeHighlight(bubbleShape, glassConfig)
            .glassBorder(bubbleShape, glassConfig)
    }
    val bodyScale = chatPreferences.messageFontScale.coerceIn(0.85f, 1.3f)
    val baseBodyStyle = MaterialTheme.typography.bodyMedium
    val bodyStyle = remember(baseBodyStyle, bodyScale, chatPreferences.messageLetterSpacingEm) {
        baseBodyStyle.copy(
            fontSize = baseBodyStyle.fontSize * bodyScale,
            lineHeight = baseBodyStyle.lineHeight.let { if (it.isUnspecified) it else it * bodyScale },
            letterSpacing = chatPreferences.messageLetterSpacingEm.coerceIn(-0.02f, 0.1f).em,
        )
    }
    val openMenu = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        menuExpanded = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                bottom = if (reactions.isNotEmpty()) MusePaddings.auxGap else MusePaddings.tinyGap,
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        if (!isUser && chatPreferences.agentShowMood && !visibleMood.isNullOrBlank()) {
            io.zer0.muse.ui.common.chat.MuseMoodCard(
                mood = visibleMood,
                expanded = moodExpanded,
                onToggle = { moodExpanded = !moodExpanded },
                modifier = if (chatPreferences.bubbleFullWidth) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(max = MuseBubbleStyles.maxBubbleWidth())
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
        Box {
            Box(
                modifier = if (chatPreferences.bubbleFullWidth && !isUser) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(max = MuseBubbleStyles.maxBubbleWidth())
                },
            ) {
                Surface(
                    color = if (useFakeGlass && !monoFlatGlass) Color.Transparent else baseSurfaceColor,
                    contentColor = contentColor,
                    shape = bubbleShape,
                    border = if (useFakeGlass) {
                        null
                    } else {
                        BorderStroke(
                            width = MuseBorderWidth.hairline,
                            color = if (isUser) {
                                MuseBubbleStyles.userBorderColor()
                            } else {
                                MuseBubbleStyles.assistantBorderColor()
                            },
                        )
                    },
                    modifier = Modifier
                        .padding(
                            bottom = if (reactions.isNotEmpty()) MusePaddings.contentGap else MusePaddings.tinyGap,
                        )
                        .then(glassBubbleModifier)
                        .clip(bubbleShape)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = openMenu,
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(MusePaddings.bubbleInner),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        if (!isUser && chatPreferences.agentShowThinking && message.reasoning.isNotBlank()) {
                            io.zer0.muse.ui.common.chat.MuseThinkingPanel(
                                reasoning = message.reasoning,
                                expanded = reasoningExpanded,
                                onToggle = { reasoningExpanded = !reasoningExpanded },
                                isThinkingActive = false,
                                reasoningDurationMs = message.reasoningDurationMs,
                                cacheKey = message.messageId,
                                onLongPressOutside = { openMenu() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
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
                                ) { index, image ->
                                    AsyncImage(
                                        model = image,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(MusePaddings.previewThumb)
                                            .clip(MuseShapes.medium)
                                            .clickable(enabled = onOpenMediaViewer != null) {
                                                onOpenMediaViewer?.invoke(imageModels, index)
                                            },
                                    )
                                }
                            }
                        }
                        if (displayText.isNotBlank()) {
                            MarkdownText(
                                text = displayText,
                                style = bodyStyle,
                                color = contentColor,
                                citationUrls = message.citationUrls,
                                onLongPressOutside = { openMenu() },
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
                        val showTime = chatPreferences.showTimestamp && message.createdAt > 0L
                        val showModel = !isUser && chatPreferences.showModelName && modelLabel.isNotBlank()
                        if (showTime || showModel) {
                            val footerText = remember(message.createdAt, chatPreferences.use24Hour, showTime, showModel, modelLabel) {
                                val parts = mutableListOf<String>()
                                if (showModel) parts += modelLabel
                                if (showTime) {
                                    val pattern = if (chatPreferences.use24Hour) {
                                        MuseDateFormats.TIME_SHORT
                                    } else {
                                        MuseDateFormats.TIME_12H
                                    }
                                    parts += SimpleDateFormat(pattern, Locale.getDefault()).format(Date(message.createdAt))
                                }
                                parts.joinToString(" · ")
                            }
                            Text(
                                text = footerText,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = MuseAlpha.subtleBorder),
                            )
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
            MuseAnchoredMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                alignEnd = isUser,
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
                MuseListItem(
                    onClick = {
                        menuExpanded = false
                        onQuote()
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.reply,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.message_action_quote),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
                MuseListItem(
                    onClick = {
                        menuExpanded = false
                        onCopy()
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.copy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_screen_copy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
                if (message.text.isNotBlank()) {
                    MuseListItem(
                        onClick = {
                            menuExpanded = false
                            showSelectTextDialog = true
                        },
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.square,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.action_select_text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                }
                if (isUser && message.text.isNotBlank()) {
                    MuseListItem(
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.common_edit),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                }
                MuseListItem(
                    onClick = {
                        menuExpanded = false
                        onRetry()
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_screen_retry_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
                if (message.text.isNotBlank()) {
                    MuseListItem(
                        onClick = {
                            menuExpanded = false
                            onSaveAsRoutine()
                        },
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.agent_hub_mission_save_routine),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                    MuseListItem(
                        onClick = {
                            menuExpanded = false
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, displayText)
                            }
                            runCatching {
                                ShareIntentHelper.startChooserSafely(context, sendIntent)
                            }.onFailure {
                                Logger.w("AgentBubble", "分享消息失败: ${it.message}")
                            }
                        },
                        leadingContent = {
                            Icon(
                                imageVector = MuseIcons.share,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.chat_share_action),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                }
                MuseListItem(
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.trash,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_screen_delete_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
        }
    }

    if (showSelectTextDialog) {
        MuseDialog(
            onDismissRequest = { showSelectTextDialog = false },
            title = stringResource(R.string.action_select_text),
            content = {
                SelectionContainer {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { showSelectTextDialog = false },
            dismissText = stringResource(R.string.common_copy),
            onDismiss = {
                showSelectTextDialog = false
                onCopy()
            },
        )
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
    chatPreferences: ChatPreferences = ChatPreferences(),
) {
    if (!streaming.active) return
    var reasoningExpanded by rememberSaveable(streaming.reasoningStartedAtMs) {
        mutableStateOf(chatPreferences.reasoningExpandedByDefault)
    }
    val bubbleRadius = chatPreferences.bubbleRadius.coerceIn(0, 28).toFloat()
    val bubbleShape = MuseBubbleStyles.assistantBubbleShape(bubbleRadius)
    val glassConfig = LocalLiquidGlass.current
    val useFakeGlass = glassConfig.enabled
    val monoFlatGlass = useFakeGlass && MuseBubbleStyles.isMonoBubbleTheme()
    val baseSurfaceColor = MuseBubbleStyles.assistantSurfaceColor()
    val glassBubbleModifier = when {
        !useFakeGlass -> Modifier
        monoFlatGlass -> Modifier
            .clip(bubbleShape)
            .glassEdgeHighlight(bubbleShape, glassConfig)
            .glassBorder(bubbleShape, glassConfig)
        else -> Modifier
            .clip(bubbleShape)
            .background(glassFakeSurfaceColor(baseSurfaceColor, glassConfig))
            .glassEdgeHighlight(bubbleShape, glassConfig)
            .glassBorder(bubbleShape, glassConfig)
    }
    val bodyScale = chatPreferences.messageFontScale.coerceIn(0.85f, 1.3f)
    val baseBodyStyle = MaterialTheme.typography.bodyMedium
    val bodyStyle = remember(baseBodyStyle, bodyScale, chatPreferences.messageLetterSpacingEm) {
        baseBodyStyle.copy(
            fontSize = baseBodyStyle.fontSize * bodyScale,
            lineHeight = baseBodyStyle.lineHeight.let { if (it.isUnspecified) it else it * bodyScale },
            letterSpacing = chatPreferences.messageLetterSpacingEm.coerceIn(-0.02f, 0.1f).em,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        if (chatPreferences.agentShowMood && streaming.mood.isNotBlank()) {
            io.zer0.muse.ui.common.chat.MuseMoodCard(
                mood = streaming.mood,
                expanded = false,
                onToggle = {},
                isStreaming = true,
                modifier = if (chatPreferences.bubbleFullWidth) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(max = MuseBubbleStyles.maxBubbleWidth())
                },
            )
        }
        Surface(
            color = if (useFakeGlass && !monoFlatGlass) Color.Transparent else baseSurfaceColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = bubbleShape,
            border = if (useFakeGlass) {
                null
            } else {
                BorderStroke(
                    width = MuseBorderWidth.hairline,
                    color = MuseBubbleStyles.assistantBorderColor(),
                )
            },
            modifier = (
                if (chatPreferences.bubbleFullWidth) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(max = MuseBubbleStyles.maxBubbleWidth())
                }
                ).then(glassBubbleModifier),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.bubbleInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                if (chatPreferences.agentShowThinking && streaming.reasoning.isNotBlank()) {
                    io.zer0.muse.ui.common.chat.MuseThinkingPanel(
                        reasoning = streaming.reasoning,
                        expanded = reasoningExpanded,
                        onToggle = { reasoningExpanded = !reasoningExpanded },
                        isThinkingActive = streaming.isThinkingActive || streaming.text.isBlank(),
                        reasoningStartedAtMs = streaming.reasoningStartedAtMs,
                        reasoningDurationMs = streaming.reasoningDurationMs,
                        cacheKey = "agent-stream-${streaming.reasoningStartedAtMs ?: 0L}",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (streaming.text.isNotBlank()) {
                    MarkdownText(
                        text = streaming.text,
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        isStreaming = true,
                        citationUrls = streaming.citationUrls,
                    )
                } else if (!chatPreferences.agentShowThinking || streaming.reasoning.isBlank()) {
                    val waitingLabel = if (streaming.isThinkingActive || streaming.reasoning.isNotBlank()) {
                        stringResource(R.string.agent_waiting_thinking)
                    } else {
                        stringResource(R.string.agent_waiting_preparing)
                    }
                    Text(
                        text = waitingLabel,
                        style = MaterialTheme.typography.bodySmall,
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
