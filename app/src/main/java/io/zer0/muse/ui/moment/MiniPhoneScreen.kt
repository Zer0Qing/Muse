package io.zer0.muse.ui.moment

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v1.0.72: 小手机沉浸页 — 手机壳 + AI 桌面 + 应用图标网格。
 *
 * 沉浸感:全屏半透明背景 + 居中手机壳(大圆角/边框/投影),
 * 壳内是 AI 的\"手机桌面\"。第一版只有朋友圈可用,其余应用灰置占位。
 */
@Composable
fun MiniPhoneScreen(
    momentsCount: Int,
    unreadMoments: Boolean,
    onOpenMoments: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 进入动画:手机"拿起"效果(缩放 0.9 → 1 + 上浮)
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "miniPhoneAppear",
    )
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        // 手机壳
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 48.dp)
                .shadow(24.dp, RoundedCornerShape(32.dp))
                .graphicsLayer {
                    scaleX = if (appeared) appear else 0.9f
                    scaleY = if (appeared) appear else 0.9f
                    translationY = if (appeared) 0f else 40f
                    alpha = if (appeared) appear else 0f
                }
                .clickable(enabled = false) { },  // 壳内点击不穿透到背景
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 状态栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "9:41",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Muse OS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                Spacer(Modifier.height(28.dp))

                // AI 头像 + 问候
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "M",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Muse 的手机",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (momentsCount > 0) "已记录 $momentsCount 条生活动态" else "记录生活的每一个瞬间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(32.dp))

                // 桌面图标网格(2 行 x 3 列)
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        MiniAppIcon(
                            icon = Icons.Filled.Favorite,
                            label = "朋友圈",
                            enabled = true,
                            badge = unreadMoments,
                            onClick = onOpenMoments,
                        )
                        MiniAppIcon(
                            icon = Icons.Filled.PhotoLibrary,
                            label = "相册",
                            enabled = false,
                        )
                        MiniAppIcon(
                            icon = Icons.Filled.WbSunny,
                            label = "天气",
                            enabled = false,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        MiniAppIcon(
                            icon = Icons.Filled.Home,
                            label = "备忘录",
                            enabled = false,
                        )
                        MiniAppIcon(
                            icon = Icons.Filled.Settings,
                            label = "设置",
                            enabled = false,
                        )
                        MiniAppIcon(
                            icon = Icons.Filled.Favorite,
                            label = "敬请期待",
                            enabled = false,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // 底部提示
                Text(
                    text = "点击朋友圈查看 Muse 的动态",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** v1.0.72: 桌面应用图标(圆形 + 下方文字;未启用 = 灰置)。 */
@Composable
private fun MiniAppIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit = {},
    badge: Boolean = false,
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .clipClickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    shape = RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(24.dp),
            )
            // 未读角标
            if (badge && enabled) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Color(0xFFFF3B30), CircleShape)
                        .align(Alignment.TopEnd)
                        .padding(0.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            },
        )
    }
}

/** 灰置图标的点击处理(未启用时不可点)。 */
private fun Modifier.clipClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onClick) else this
