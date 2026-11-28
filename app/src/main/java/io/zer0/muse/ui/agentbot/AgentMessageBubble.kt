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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.BotAnnotationEntity
import io.zer0.muse.ui.chat.SmartImage
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.markdown.MarkdownText
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
import io.zer0.muse.util.ShareIntentHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_REACTION_EMOJIS = listOf("👌", "👍", "❤️", "👀", "🤔", "🎉")

private data class AgentMessageBubbleAppearance(
    val displayText: String,
    val inlineLinks: List<SourceLinkItem>,
    val bubbleShape: androidx.compose.ui.graphics.Shape,
    val baseSurfaceColor: Color,
    val contentColor: Color,
    val useFakeGlass: Boolean,
    val monoFlatGlass: Boolean,
    val glassBubbleModifier: Modifier,
    val bodyStyle: androidx.compose.ui.text.TextStyle,
)

@Composable
private fun rememberAgentMessageBubbleAppearance(
    message: AgentBotUiState.PublishedMessage,
    isUser: Boolean,
    chatPreferences: ChatPreferences,
): AgentMessageBubbleAppearance {
    val glassConfig = LocalLiquidGlass.current
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
        monoFlatGlass ->
            Modifier
                .clip(bubbleShape)
                .glassEdgeHighlight(bubbleShape, glassConfig)
                .glassBorder(bubbleShape, glassConfig)
        else ->
            Modifier
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
    return AgentMessageBubbleAppearance(
        displayText = displayText,
        inlineLinks = inlineLinks,
        bubbleShape = bubbleShape,
        baseSurfaceColor = baseSurfaceColor,
        contentColor = contentColor,
        useFakeGlass = useFakeGlass,
        monoFlatGlass = monoFlatGlass,
        glassBubbleModifier = glassBubbleModifier,
        bodyStyle = bodyStyle,
    )
}

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
    val appearance = rememberAgentMessageBubbleAppearance(message, isUser, chatPreferences)
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
        AgentMessageBubbleContent(
            layout = AgentMessageBubbleLayout(
                message = message,
                reactions = reactions,
                isUser = isUser,
                reasoningExpanded = reasoningExpanded,
                onToggleReasoning = { reasoningExpanded = !reasoningExpanded },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuExpanded = true
                },
                bubbleShape = appearance.bubbleShape,
                baseSurfaceColor = appearance.baseSurfaceColor,
                contentColor = appearance.contentColor,
                useFakeGlass = appearance.useFakeGlass,
                monoFlatGlass = appearance.monoFlatGlass,
                glassBubbleModifier = appearance.glassBubbleModifier,
                bodyStyle = appearance.bodyStyle,
                displayText = appearance.displayText,
                inlineLinks = appearance.inlineLinks,
                modelLabel = modelLabel,
                bubbleFullWidth = chatPreferences.bubbleFullWidth,
                showThinking = chatPreferences.agentShowThinking,
                showTimestamp = chatPreferences.showTimestamp,
                use24Hour = chatPreferences.use24Hour,
                onOpenMediaViewer = onOpenMediaViewer,
                context = context,
            ),
            actions = AgentMessageBubbleActions(
                onCopy = onCopy,
                onRetry = onRetry,
                onDelete = onDelete,
                onReact = onReact,
                onRemoveReaction = onRemoveReaction,
                onQuote = onQuote,
                onEdit = onEdit,
                onSaveAsRoutine = onSaveAsRoutine,
            ),
            menuExpanded = menuExpanded,
            onMenuExpandedChange = { menuExpanded = it },
            showSelectTextDialog = showSelectTextDialog,
            onSelectTextDialogChange = { showSelectTextDialog = it },
        )
    }
}

private data class AgentMessageBubbleLayout(
    val message: AgentBotUiState.PublishedMessage,
    val reactions: List<AgentBotUiState.MessageReactionUi>,
    val isUser: Boolean,
    val reasoningExpanded: Boolean,
    val onToggleReasoning: () -> Unit,
    val onLongPress: () -> Unit,
    val bubbleShape: androidx.compose.ui.graphics.Shape,
    val baseSurfaceColor: Color,
    val contentColor: Color,
    val useFakeGlass: Boolean,
    val monoFlatGlass: Boolean,
    val glassBubbleModifier: Modifier,
    val bodyStyle: androidx.compose.ui.text.TextStyle,
    val displayText: String,
    val inlineLinks: List<SourceLinkItem>,
    val modelLabel: String,
    val bubbleFullWidth: Boolean,
    val showThinking: Boolean,
    val showTimestamp: Boolean,
    val use24Hour: Boolean,
    val onOpenMediaViewer: ((List<String>, Int) -> Unit)?,
    val context: android.content.Context,
)

private data class AgentMessageBubbleActions(
    val onCopy: () -> Unit,
    val onRetry: () -> Unit,
    val onDelete: () -> Unit,
    val onReact: (String) -> Unit,
    val onRemoveReaction: (String) -> Unit,
    val onQuote: () -> Unit,
    val onEdit: () -> Unit,
    val onSaveAsRoutine: () -> Unit,
)

@Composable
private fun AgentMessageBubbleContent(
    layout: AgentMessageBubbleLayout,
    actions: AgentMessageBubbleActions,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    showSelectTextDialog: Boolean,
    onSelectTextDialogChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (layout.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Box(
                modifier = if (layout.bubbleFullWidth && !layout.isUser) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(max = MuseBubbleStyles.maxBubbleWidth())
                },
            ) {
                AgentMessageBubbleSurface(
                    message = layout.message,
                    isUser = layout.isUser,
                    reasoningExpanded = layout.reasoningExpanded,
                    onToggleReasoning = layout.onToggleReasoning,
                    bubbleShape = layout.bubbleShape,
                    baseSurfaceColor = layout.baseSurfaceColor,
                    contentColor = layout.contentColor,
                    useFakeGlass = layout.useFakeGlass,
                    monoFlatGlass = layout.monoFlatGlass,
                    glassBubbleModifier = layout.glassBubbleModifier,
                    bodyStyle = layout.bodyStyle,
                    displayText = layout.displayText,
                    inlineLinks = layout.inlineLinks,
                    modelLabel = layout.modelLabel,
                    showThinking = layout.showThinking,
                    showTimestamp = layout.showTimestamp,
                    use24Hour = layout.use24Hour,
                    onLongPress = layout.onLongPress,
                    onOpenMediaViewer = layout.onOpenMediaViewer,
                    context = layout.context,
                    reactions = layout.reactions,
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = layout.reactions.isNotEmpty(),
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
                        reactions = layout.reactions,
                        onTapReaction = { reaction ->
                            if (reaction.actorType == BotAnnotationEntity.ACTOR_USER) {
                                actions.onRemoveReaction(reaction.id)
                            } else {
                                onMenuExpandedChange(true)
                            }
                        },
                    )
                }
            }
            AgentMessageActionMenu(
                expanded = menuExpanded,
                alignEnd = layout.isUser,
                hasText = layout.message.text.isNotBlank(),
                isUser = layout.isUser,
                onDismiss = { onMenuExpandedChange(false) },
                onReact = actions.onReact,
                onQuote = actions.onQuote,
                onCopy = actions.onCopy,
                onSelectText = { onSelectTextDialogChange(true) },
                onEdit = actions.onEdit,
                onRetry = actions.onRetry,
                onSaveAsRoutine = actions.onSaveAsRoutine,
                onShare = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, layout.displayText)
                    }
                    runCatching { ShareIntentHelper.startChooserSafely(layout.context, sendIntent) }
                        .onFailure { Logger.w("AgentBubble", "分享消息失败: ${it.message}") }
                },
                onDelete = actions.onDelete,
            )
        }
    }

    if (showSelectTextDialog) {
        MuseDialog(
            onDismissRequest = { onSelectTextDialogChange(false) },
            title = stringResource(R.string.action_select_text),
            content = {
                SelectionContainer {
                    Text(
                        text = layout.displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { onSelectTextDialogChange(false) },
            dismissText = stringResource(R.string.common_copy),
            onDismiss = {
                onSelectTextDialogChange(false)
                actions.onCopy()
            },
        )
    }
}

@Composable
private fun AgentMessageBubbleSurface(
    message: AgentBotUiState.PublishedMessage,
    isUser: Boolean,
    reasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    baseSurfaceColor: Color,
    contentColor: Color,
    useFakeGlass: Boolean,
    monoFlatGlass: Boolean,
    glassBubbleModifier: Modifier,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    displayText: String,
    inlineLinks: List<SourceLinkItem>,
    modelLabel: String,
    showThinking: Boolean,
    showTimestamp: Boolean,
    use24Hour: Boolean,
    onLongPress: () -> Unit,
    onOpenMediaViewer: ((List<String>, Int) -> Unit)?,
    context: android.content.Context,
    reactions: List<AgentBotUiState.MessageReactionUi>,
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
                color = if (isUser) MuseBubbleStyles.userBorderColor() else MuseBubbleStyles.assistantBorderColor(),
            )
        },
        modifier = Modifier
            .padding(bottom = if (reactions.isNotEmpty()) MusePaddings.contentGap else MusePaddings.tinyGap)
            .then(glassBubbleModifier)
            .clip(bubbleShape)
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        AgentMessageBubbleBody(
            message = message,
            isUser = isUser,
            reasoningExpanded = reasoningExpanded,
            onToggleReasoning = onToggleReasoning,
            contentColor = contentColor,
            bodyStyle = bodyStyle,
            displayText = displayText,
            inlineLinks = inlineLinks,
            modelLabel = modelLabel,
            showThinking = showThinking,
            showTimestamp = showTimestamp,
            use24Hour = use24Hour,
            onLongPress = onLongPress,
            onOpenMediaViewer = onOpenMediaViewer,
            context = context,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentMessageBubbleBody(
    message: AgentBotUiState.PublishedMessage,
    isUser: Boolean,
    reasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    contentColor: Color,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    displayText: String,
    inlineLinks: List<SourceLinkItem>,
    modelLabel: String,
    showThinking: Boolean,
    showTimestamp: Boolean,
    use24Hour: Boolean,
    onLongPress: () -> Unit,
    onOpenMediaViewer: ((List<String>, Int) -> Unit)?,
    context: android.content.Context,
) {
    Column(
        modifier = Modifier.padding(MusePaddings.bubbleInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        if (!isUser && showThinking && message.reasoning.isNotBlank()) {
            io.zer0.muse.ui.common.chat.MuseThinkingPanel(
                reasoning = message.reasoning,
                expanded = reasoningExpanded,
                onToggle = onToggleReasoning,
                isThinkingActive = false,
                reasoningDurationMs = message.reasoningDurationMs,
                cacheKey = message.messageId,
                onLongPressOutside = onLongPress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val imageModels = message.imageUrls + message.imageBase64List.map { "data:image/jpeg;base64,$it" }
        if (imageModels.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                itemsIndexed(items = imageModels, key = { index, img -> "$index:${img.hashCode()}" }) { index, image ->
                    SmartImage(
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
                cardId = message.messageId,
                onLongPressOutside = onLongPress,
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
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
                                .onFailure { Logger.w("AgentBubble", "打开来源链接失败: ${it.message}") }
                        },
                    )
                }
            }
        }
        MessageBubbleFooter(
            message = message,
            isUser = isUser,
            modelLabel = modelLabel,
            showTimestamp = showTimestamp,
            use24Hour = use24Hour,
            contentColor = contentColor,
        )
    }
}

@Composable
private fun MessageBubbleFooter(
    message: AgentBotUiState.PublishedMessage,
    isUser: Boolean,
    modelLabel: String,
    showTimestamp: Boolean,
    use24Hour: Boolean,
    contentColor: Color,
) {
    val showTime = showTimestamp && message.createdAt > 0L
    val showModel = !isUser && modelLabel.isNotBlank()
    if (!showTime && !showModel) return
    val footerText = remember(message.createdAt, modelLabel, showTime, showModel, use24Hour) {
        buildList {
            if (showModel) add(modelLabel)
            if (showTime) {
                val pattern = if (use24Hour) MuseDateFormats.TIME_SHORT else MuseDateFormats.TIME_12H
                add(SimpleDateFormat(pattern, Locale.getDefault()).format(Date(message.createdAt)))
            }
        }.joinToString(" · ")
    }
    Text(
        text = footerText,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor.copy(alpha = MuseAlpha.subtleBorder),
    )
}

@Composable
private fun AgentMessageActionMenu(
    expanded: Boolean,
    alignEnd: Boolean,
    hasText: Boolean,
    isUser: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onQuote: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onSaveAsRoutine: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    MuseAnchoredMenu(expanded = expanded, onDismissRequest = onDismiss, alignEnd = alignEnd) {
        Row(
            modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QUICK_REACTION_EMOJIS.forEach { emoji ->
                Surface(
                    onClick = { onDismiss(); onReact(emoji) },
                    shape = MuseShapes.pill,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                ) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = MusePaddings.tightGap))
        AgentMessageMenuItem(MuseIcons.reply, stringResource(R.string.message_action_quote), onDismiss, onQuote)
        AgentMessageMenuItem(MuseIcons.copy, stringResource(R.string.agent_screen_copy), onDismiss, onCopy)
        if (hasText) {
            AgentMessageMenuItem(MuseIcons.square, stringResource(R.string.action_select_text), onDismiss, onSelectText)
        }
        if (isUser && hasText) {
            AgentMessageMenuItem(MuseIcons.edit, stringResource(R.string.common_edit), onDismiss, onEdit)
        }
        AgentMessageMenuItem(MuseIcons.refresh, stringResource(R.string.agent_screen_retry_message), onDismiss, onRetry)
        if (hasText) {
            AgentMessageMenuItem(MuseIcons.bolt, stringResource(R.string.agent_hub_mission_save_routine), onDismiss, onSaveAsRoutine)
            AgentMessageMenuItem(MuseIcons.share, stringResource(R.string.chat_share_action), onDismiss, onShare)
        }
        AgentMessageMenuItem(
            icon = MuseIcons.trash,
            label = stringResource(R.string.agent_screen_delete_message),
            onDismiss = onDismiss,
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AgentMessageMenuItem(
    icon: ImageVector,
    label: String,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    MuseListItem(
        onClick = { onDismiss(); onClick() },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(MuseIconSizes.iconSmall))
        },
        headlineContent = {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = tint)
        },
    )
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
private fun InlineSourceChip(label: String, onClick: () -> Unit) {
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
        monoFlatGlass ->
            Modifier
                .clip(bubbleShape)
                .glassEdgeHighlight(bubbleShape, glassConfig)
                .glassBorder(bubbleShape, glassConfig)
        else ->
            Modifier
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
            AgentStreamingBubbleContent(
                streaming = streaming,
                reasoningExpanded = reasoningExpanded,
                onToggleReasoning = { reasoningExpanded = !reasoningExpanded },
                showThinking = chatPreferences.agentShowThinking,
                bodyStyle = bodyStyle,
            )
        }
    }
}

@Composable
private fun AgentStreamingBubbleContent(
    streaming: AgentBotUiState.StreamingBubble,
    reasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    showThinking: Boolean,
    bodyStyle: androidx.compose.ui.text.TextStyle,
) {
    Column(
        modifier = Modifier.padding(MusePaddings.bubbleInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        if (showThinking && streaming.reasoning.isNotBlank()) {
            io.zer0.muse.ui.common.chat.MuseThinkingPanel(
                reasoning = streaming.reasoning,
                expanded = reasoningExpanded,
                onToggle = onToggleReasoning,
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
        } else if (!showThinking || streaming.reasoning.isBlank()) {
            StreamingWaitingLabel(streaming = streaming)
        }
    }
}

@Composable
private fun StreamingWaitingLabel(streaming: AgentBotUiState.StreamingBubble) {
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
