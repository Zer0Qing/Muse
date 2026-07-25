package io.zer0.muse.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Muse UI Kit — 列表项基元 [MuseListItem]。
 *
 * v1.0.26: 替代直接调用 Material3 [androidx.compose.material3.ListItem],
 * 统一所有设置页 / 表单 / 列表的行样式,解决:
 *  - ListItem 默认容器色/色调 elevation 在卡片内产生嵌套色块
 *  - 各页面 padding / icon 尺寸 / 字号不统一
 *  - Material3 ListItem 在不同 alpha 下颜色行为不稳定
 *
 * 规格(iOS Settings 风格):
 *  - 最小高度 56dp(对齐 Material3 ListItem 默认,确保触摸目标)
 *  - 水平 padding 16dp,垂直 padding 12dp
 *  - leading content 右侧间距 16dp,trailing content 左侧间距 12dp
 *  - headline: titleMedium + onSurface
 *  - supporting: bodySmall + onSurfaceVariant
 *  - 背景透明(由外层 [MuseSurface] / [CardGroup] 容器提供统一背景)
 *
 * 用法:
 * ```
 * MuseListItem(
 *     onClick = { ... },
 *     leadingContent = { Icon(Icons.Outlined.Palette, null) },
 *     headlineContent = { Text("外观") },
 *     supportingContent = { Text("主题、字号、深色模式") },
 *     trailingContent = { ChevronRight() },
 * )
 * ```
 *
 * @param onClick 点击回调(null 表示不可点击)
 * @param modifier 修饰符
 * @param headlineContent 主标题(必填)
 * @param supportingContent 副标题(可选,灰色小字)
 * @param leadingContent 左侧内容(通常是图标,可选)
 * @param trailingContent 右侧内容(箭头/Switch 等,可选)
 * @param minHeight 最小高度(默认 56dp)
 */
@Composable
fun MuseListItem(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    minHeight: Dp = 56.dp,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(horizontal = MusePaddings.screen, vertical = 12.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            MuseHaptics.light(haptic)
                            onClick()
                        },
                    )
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // leading content
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(MusePaddings.screen))
        }

        // headline + supporting(垂直排列,占满剩余空间)
        Column(
            modifier = Modifier.weight(1f),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                    headlineContent()
                }
            }
            if (supportingContent != null) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                        supportingContent()
                    }
                }
            }
        }

        // trailing content
        if (trailingContent != null) {
            Spacer(Modifier.width(MusePaddings.contentGap))
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                trailingContent()
            }
        }
    }
}
