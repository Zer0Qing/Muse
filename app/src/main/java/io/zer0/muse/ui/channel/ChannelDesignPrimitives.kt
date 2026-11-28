package io.zer0.muse.ui.channel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConnectionState
import io.zer0.muse.channel.ChannelPlatform
import io.zer0.muse.channel.ChannelRuntimeStatus
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseChannelBrandPair
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.channelBrandColors
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.statusColors

/** 取各平台成对品牌色（全部走主题令牌，跟随深浅主题自动切换）。 */
@Composable
internal fun ChannelPlatform.brandColors(): MuseChannelBrandPair = when (this) {
    ChannelPlatform.WECLAW -> MaterialTheme.channelBrandColors.weclaw
    ChannelPlatform.FEISHU -> MaterialTheme.channelBrandColors.feishu
    ChannelPlatform.TELEGRAM -> MaterialTheme.channelBrandColors.telegram
    ChannelPlatform.DINGTALK -> MaterialTheme.channelBrandColors.dingtalk
    ChannelPlatform.QQ -> MaterialTheme.channelBrandColors.qq
}

/** 平台语义图标。 */
internal fun ChannelPlatform.icon(): ImageVector = when (this) {
    ChannelPlatform.WECLAW -> MuseIcons.chat
    ChannelPlatform.TELEGRAM -> MuseIcons.send
    ChannelPlatform.FEISHU -> MuseIcons.globe
    ChannelPlatform.DINGTALK -> MuseIcons.bell
    ChannelPlatform.QQ -> MuseIcons.users
}

@StringRes
internal fun channelPlatformNameRes(platform: ChannelPlatform): Int = when (platform) {
    ChannelPlatform.FEISHU -> R.string.channel_platform_feishu
    ChannelPlatform.QQ -> R.string.channel_platform_qq
    ChannelPlatform.WECLAW -> R.string.channel_platform_weclaw
    ChannelPlatform.TELEGRAM -> R.string.channel_platform_telegram
    ChannelPlatform.DINGTALK -> R.string.channel_platform_dingtalk
}

@StringRes
internal fun channelPlatformDescRes(platform: ChannelPlatform): Int = when (platform) {
    ChannelPlatform.WECLAW -> R.string.channel_platform_desc_weclaw
    ChannelPlatform.TELEGRAM -> R.string.channel_platform_desc_telegram
    ChannelPlatform.FEISHU -> R.string.channel_platform_desc_feishu
    ChannelPlatform.DINGTALK -> R.string.channel_platform_desc_dingtalk
    ChannelPlatform.QQ -> R.string.channel_platform_desc_qq
}

internal fun channelPlatformPortalUrl(platform: ChannelPlatform): String = when (platform) {
    ChannelPlatform.FEISHU -> "https://open.feishu.cn/app"
    ChannelPlatform.TELEGRAM -> "https://t.me/BotFather"
    ChannelPlatform.DINGTALK -> "https://open-dev.dingtalk.com/"
    ChannelPlatform.QQ -> "https://q.qq.com/qqbot"
    ChannelPlatform.WECLAW -> ""
}

/** 平台圆角徽标块。 */
@Composable
internal fun PlatformBrandBadge(
    platform: ChannelPlatform,
    modifier: Modifier = Modifier,
    badgeSize: androidx.compose.ui.unit.Dp = MuseAvatarSize.list,
) {
    val brand = platform.brandColors()
    Surface(
        modifier = modifier.size(badgeSize),
        shape = MuseShapes.medium,
        color = brand.container,
        border = BorderStroke(
            width = MuseBorderWidth.hairline,
            color = brand.accent.copy(alpha = MuseAlpha.divider),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = platform.icon(),
                contentDescription = null,
                tint = brand.accent,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/** 实时连接状态胶囊（在线呼吸圆点 / 重连微转圈 / 故障告警 / 停用灰胶囊）。 */
@Composable
internal fun LiveStatusPill(enabled: Boolean, status: ChannelRuntimeStatus?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.statusColors
    val state = if (!enabled) ChannelConnectionState.STOPPED else (status?.state ?: ChannelConnectionState.IDLE)
    val (bg, fg, labelRes) = when {
        !enabled || state == ChannelConnectionState.STOPPED ->
            Triple(colors.neutralContainer, colors.neutral, R.string.channel_status_stopped)
        state == ChannelConnectionState.CONNECTED ->
            Triple(colors.successContainer, colors.success, R.string.channel_status_connected)
        state == ChannelConnectionState.CONNECTING ->
            Triple(colors.infoContainer, colors.info, R.string.channel_status_connecting)
        state == ChannelConnectionState.RECONNECTING || state == ChannelConnectionState.IDLE ->
            Triple(colors.warningContainer, colors.warning, R.string.channel_status_reconnecting)
        state == ChannelConnectionState.AUTH_ERROR ->
            Triple(colors.errorContainer, colors.error, R.string.channel_status_auth_error)
        else ->
            Triple(colors.errorContainer, colors.error, R.string.channel_status_error)
    }

    val reducedMotion = MuseMotion.isReducedMotion()
    val pulseAlpha = if (state == ChannelConnectionState.CONNECTED && enabled && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "channel_live_dot")
        val animated by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "channel_live_dot_alpha",
        )
        animated
    } else {
        1f
    }

    Surface(
        modifier = modifier,
        shape = MuseShapes.pill,
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.chipInnerLoose),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            when {
                enabled && (
                    state == ChannelConnectionState.CONNECTING ||
                        state == ChannelConnectionState.RECONNECTING
                    ) -> {
                    MuseSpinner(size = MuseIconSizes.iconTiny, color = fg)
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(MuseSpacing.smTight)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(fg),
                    )
                }
            }
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 联系人圆形头像 + 右下角平台小角标。 */
@Composable
internal fun ContactAvatarWithPlatform(displayName: String, platform: ChannelPlatform, isGroup: Boolean, modifier: Modifier = Modifier) {
    val brand = platform.brandColors()
    val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty().ifBlank { "?" }
    Box(modifier = modifier.size(MuseAvatarSize.detail)) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isGroup) {
                    Icon(
                        imageVector = MuseIcons.users,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                } else {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = MuseSpacing.xxs, y = MuseSpacing.xxs)
                .size(MuseIconSizes.iconSmall)
                .border(MuseBorderWidth.hairline, MaterialTheme.colorScheme.surface, CircleShape),
            shape = CircleShape,
            color = brand.container,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = platform.icon(),
                    contentDescription = null,
                    tint = brand.accent,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
        }
    }
}

/** 权限/事件名一键复制芯片，省去手抄出错。 */
@Composable
internal fun CopyableTokenChip(value: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.channel_wizard_copied, value)
    Surface(
        modifier = modifier
            .clip(MuseShapes.pill)
            .clickable {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(value, value))
                MuseToast.show(copiedMsg)
            },
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.chipInnerLoose),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            Icon(
                imageVector = MuseIcons.copy,
                contentDescription = stringResource(R.string.channel_wizard_copy_chip, value),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 小型轻量语义标签胶囊（用于辅助标明状态、能力或消息来源）。 */
@Composable
internal fun ChannelTagPill(text: String, containerColor: Color, contentColor: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MuseShapes.pill,
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(MusePaddings.chipInnerLoose),
        )
    }
}

/** 相对时间格式化。 */
internal fun formatChannelRelativeTime(timestamp: Long, resources: Resources, nowMillis: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L) return ""
    val diff = (nowMillis - timestamp).coerceAtLeast(0L)
    val minuteMs = 60_000L
    val hourMs = 60 * minuteMs
    val dayMs = 24 * hourMs
    return when {
        diff < minuteMs -> resources.getString(R.string.channel_time_just_now)
        diff < hourMs -> resources.getString(R.string.channel_time_minutes_ago, (diff / minuteMs).toInt())
        diff < dayMs -> resources.getString(R.string.channel_time_hours_ago, (diff / hourMs).toInt())
        else -> resources.getString(R.string.channel_time_days_ago, (diff / dayMs).toInt())
    }
}
