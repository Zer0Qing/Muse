package io.zer0.muse.ui.agentbot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.theme.GlassIsland
import io.zer0.muse.ui.theme.LocalLiquidGlass
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.statusColors

/**
 * 流内轻量身份与模型条：跟随消息流滚动，替代粘性顶栏第二排，不遮挡首条消息。
 */
@Composable
fun AgentInlineIdentityBar(
    assistantName: String,
    assistant: AssistantEntity?,
    avatarUri: String?,
    modelLabel: String,
    onOpenIdentitySwitch: () -> Unit,
    onOpenModel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val switchCd = stringResource(R.string.agent_hub_switch_bot)
    val glassConfig = LocalLiquidGlass.current
    val resolvedName = assistantName.ifBlank { stringResource(R.string.agent_screen_title) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tinyGap),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIsland(
            config = glassConfig,
            shape = MuseShapes.pill,
            solidColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.titleMuted),
            modifier = Modifier
                .clip(MuseShapes.pill)
                .clickable(onClick = onOpenIdentitySwitch)
                .semantics { contentDescription = switchCd },
        ) {
            Row(
                modifier = Modifier.padding(
                    start = MusePaddings.tightGap,
                    end = MusePaddings.auxGap,
                    top = MusePaddings.tinyGap,
                    bottom = MusePaddings.tinyGap,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                if (assistant != null) {
                    AssistantAvatar(
                        assistant = assistant,
                        avatarSize = MuseAvatarSize.inline,
                    )
                } else {
                    AgentAvatarCircle(
                        name = resolvedName,
                        avatarUri = avatarUri,
                        compact = true,
                    )
                }
                Text(
                    text = resolvedName,
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = MusePaddings.maxInlineWidth),
                )
                Icon(
                    imageVector = MuseIcons.chevronRight,
                    contentDescription = null,
                    tint = LocalContentColor.current.copy(alpha = MuseAlpha.subtleBorder),
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
        }
        if (onOpenModel != null && modelLabel.isNotBlank()) {
            GlassIsland(
                config = glassConfig,
                shape = MuseShapes.pill,
                solidColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.disabledContent),
                modifier = Modifier
                    .clip(MuseShapes.pill)
                    .clickable(onClick = onOpenModel),
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = MusePaddings.auxGap,
                        vertical = MusePaddings.contentGap,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                ) {
                    Icon(
                        imageVector = MuseIcons.memoryChip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = MusePaddings.maxInlineWidth),
                    )
                }
            }
        }
    }
}

/**
 * 沉浸式透明镂空悬浮顶栏：中央悬浮圆形头像 + 下方压边胶囊名牌，右侧工作台圆形按钮带活跃徽章。
 *
 * 头像两侧完全透明镂空，不铺背景色或渐变条，消息流可从顶栏后方自然滑过。
 */
@Composable
fun AgentFloatingHeader(
    assistantName: String,
    assistant: AssistantEntity? = null,
    avatarUri: String?,
    modelLabel: String,
    activeBadgeCount: Int,
    hasPendingApproval: Boolean,
    onOpenIdentitySwitch: () -> Unit,
    onOpenWorkbench: () -> Unit,
    showWorkbenchButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val switchCd = stringResource(R.string.agent_hub_switch_bot)
    val workbenchCd = stringResource(R.string.agent_hub_open_workbench)
    val glassConfig = LocalLiquidGlass.current
    val resolvedName = assistantName.ifBlank { stringResource(R.string.agent_screen_title) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen),
    ) {
        // 中央：紧凑圆形头像 + 底部压边白色名牌胶囊，点击直接切换助手，两侧完全透明镂空
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .clip(MuseShapes.medium)
                .clickable(onClick = onOpenIdentitySwitch)
                .semantics { contentDescription = switchCd },
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = MuseElevation.card,
                border = BorderStroke(
                    width = MuseBorderWidth.thin,
                    color = MaterialTheme.colorScheme.surface,
                ),
            ) {
                if (assistant != null) {
                    AssistantAvatar(
                        assistant = assistant,
                        avatarSize = MuseAvatarSize.list,
                    )
                } else {
                    AgentAvatarCircle(
                        name = resolvedName,
                        avatarUri = avatarUri,
                        compact = true,
                    )
                }
            }
            GlassIsland(
                config = glassConfig,
                shape = MuseShapes.pill,
                solidColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                modifier = Modifier.offset(y = -MusePaddings.contentGap),
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = MusePaddings.auxGap,
                        end = MusePaddings.contentGap,
                        top = MusePaddings.compactChipVertical,
                        bottom = MusePaddings.compactChipVertical,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                ) {
                    Text(
                        text = resolvedName,
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = MusePaddings.maxInlineWidth),
                    )
                    Icon(
                        imageVector = MuseIcons.chevronRight,
                        contentDescription = null,
                        tint = LocalContentColor.current.copy(alpha = MuseAlpha.subtleBorder),
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                    )
                }
            }
        }

        // 右侧：独立页时显示圆形工作台按钮 + 活跃徽章；嵌入首页 3 Tab 时由顶栏右上角承载
        if (showWorkbenchButton) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            GlassIsland(
                config = glassConfig,
                shape = CircleShape,
                solidColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                modifier = Modifier
                    .size(MuseIconSizes.topBarSolid)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenWorkbench)
                    .semantics { contentDescription = workbenchCd },
            ) {
                Box(
                    modifier = Modifier.size(MuseIconSizes.topBarSolid),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.computer,
                        contentDescription = null,
                        tint = if (hasPendingApproval) {
                            MaterialTheme.statusColors.warning
                        } else {
                            LocalContentColor.current
                        },
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
            if (activeBadgeCount > 0) {
                Surface(
                    shape = MuseShapes.pill,
                    color = if (hasPendingApproval) {
                        MaterialTheme.statusColors.warning
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = MusePaddings.tinyGap, y = -MusePaddings.tinyGap),
                ) {
                    Text(
                        text = if (activeBadgeCount > 9) "9+" else activeBadgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(
                            horizontal = MusePaddings.labelVerticalGap,
                            vertical = MusePaddings.compactChipVertical,
                        ),
                    )
                }
            }
        }
        }
    }
}

@Composable
internal fun AgentAvatarCircle(
    name: String,
    avatarUri: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val avatarSize = if (compact) MuseAvatarSize.inline else MuseAvatarSize.detail
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = if (compact) MuseElevation.none else MuseElevation.card,
        modifier = modifier.size(avatarSize),
    ) {
        if (!avatarUri.isNullOrBlank()) {
            AsyncImage(
                model = avatarUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
                Text(
                    text = initial,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * 顶栏下方按需浮现的灵动状态条。
 *
 * 平时完全隐藏以保持纯净对话画布；仅在有后台任务、活跃虚拟屏或待拍板审批时滑出。
 */
@Composable
fun AgentLiveActivityStrip(
    runningCount: Int,
    approvalCount: Int,
    latestRunningNote: String,
    activeVirtualDisplay: LiveMissionProgressBus.ActiveVirtualDisplay?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = runningCount > 0 || approvalCount > 0 || activeVirtualDisplay != null
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val statusColor = when {
            approvalCount > 0 -> MaterialTheme.statusColors.warning
            activeVirtualDisplay != null -> MaterialTheme.statusColors.info
            else -> MaterialTheme.statusColors.success
        }
        val summaryParts = buildList {
            if (activeVirtualDisplay != null) {
                add(
                    stringResource(
                        R.string.agent_hub_live_vdisplay,
                        activeVirtualDisplay.packageName.ifBlank { "#${activeVirtualDisplay.displayId}" },
                    ),
                )
            }
            if (runningCount > 0) {
                add(stringResource(R.string.agent_hub_live_running, runningCount))
            }
            if (approvalCount > 0) {
                add(stringResource(R.string.agent_hub_live_approvals, approvalCount))
            }
        }
        val primaryLine = summaryParts.joinToString(" · ")
        val glassConfig = LocalLiquidGlass.current

        GlassIsland(
            config = glassConfig,
            shape = MuseShapes.pill,
            solidColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.inputIslandHorizontal, vertical = MusePaddings.tinyGap)
                .clip(MuseShapes.pill)
                .clickable(onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = MusePaddings.inputPadding,
                    vertical = MusePaddings.contentGap,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Box(
                    modifier = Modifier
                        .size(MusePaddings.contentGap)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Text(
                    text = latestRunningNote.ifBlank { primaryLine },
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.size(MusePaddings.tinyGap))
                Icon(
                    imageVector = MuseIcons.chevronRight,
                    contentDescription = null,
                    tint = LocalContentColor.current.copy(alpha = MuseAlpha.subtleBorder),
                    modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                )
            }
        }
    }
}
