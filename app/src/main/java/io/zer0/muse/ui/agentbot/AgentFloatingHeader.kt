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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.ui.common.icons.MuseIcons
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
 * 沉浸式单层浮层顶栏：中央悬浮圆形头像 + 下方压边胶囊名牌，右侧工作台入口带活跃徽章。
 *
 * 顶部采用柔和背景渐变蒙版，消息向上滚动时自然淡入顶栏下方，避免硬分割线与双层导航拥挤。
 */
@Composable
fun AgentFloatingHeader(
    assistantName: String,
    avatarUri: String?,
    modelLabel: String,
    activeBadgeCount: Int,
    hasPendingApproval: Boolean,
    onOpenIdentitySwitch: () -> Unit,
    onOpenWorkbench: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.background
    val switchCd = stringResource(R.string.agent_hub_switch_bot)
    val workbenchCd = stringResource(R.string.agent_hub_open_workbench)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to bgColor,
                        0.72f to bgColor.copy(alpha = 0.92f),
                        1.0f to Color.Transparent,
                    ),
                ),
            )
            .padding(
                horizontal = MusePaddings.screen,
                vertical = MusePaddings.contentGap,
            ),
    ) {
        // 中央：圆形头像 + 下方压边悬浮名牌胶囊
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .semantics { contentDescription = switchCd }
                .clickable(onClick = onOpenIdentitySwitch),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.BottomCenter) {
                AgentAvatarCircle(
                    name = assistantName,
                    avatarUri = avatarUri,
                    modifier = Modifier.padding(bottom = MusePaddings.auxGap),
                )
                Surface(
                    shape = MuseShapes.pill,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = MuseElevation.medium,
                    border = BorderStroke(
                        width = MuseBorderWidth.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = MusePaddings.auxGap,
                            vertical = MusePaddings.tinyGap,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                    ) {
                        Text(
                            text = assistantName.ifBlank { stringResource(R.string.agent_screen_title) },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = MusePaddings.maxInlineWidth),
                        )
                        Icon(
                            imageVector = MuseIcons.chevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                    }
                }
            }
        }

        // 右侧：圆形工作台按钮 + 活跃徽章
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            Surface(
                onClick = onOpenWorkbench,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = MuseElevation.medium,
                border = BorderStroke(
                    width = MuseBorderWidth.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder),
                ),
                modifier = Modifier
                    .size(MuseIconSizes.topBarSolid)
                    .semantics { contentDescription = workbenchCd },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = MuseIcons.computer,
                        contentDescription = null,
                        tint = if (hasPendingApproval) {
                            MaterialTheme.statusColors.warning
                        } else {
                            MaterialTheme.colorScheme.onSurface
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

@Composable
internal fun AgentAvatarCircle(
    name: String,
    avatarUri: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = MuseElevation.card,
        modifier = modifier.size(MuseAvatarSize.detail),
    ) {
        if (!avatarUri.isNullOrBlank()) {
            AsyncImage(
                model = avatarUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(MuseAvatarSize.detail)
                    .clip(CircleShape),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
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

        Surface(
            onClick = onClick,
            shape = MuseShapes.pill,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
            border = BorderStroke(
                width = MuseBorderWidth.hairline,
                color = statusColor.copy(alpha = MuseAlpha.disabled),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.size(MusePaddings.tinyGap))
                Icon(
                    imageVector = MuseIcons.chevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
                )
            }
        }
    }
}
