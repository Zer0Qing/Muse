package io.zer0.muse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Search
import io.zer0.muse.R
import io.zer0.muse.ui.common.MuseTooltip
import io.zer0.muse.ui.theme.MusePaddings

// A1: 会话内查找条 — 聊天页顶层悬浮条:查询框 + 命中计数 + 上一条/下一条 + 关闭。
// 滚动定位与文本高亮由调用方复用 setTargetMessage 管线完成(highlightedMessageId +
// searchHighlightQuery + targetMessageId),本组件只负责输入、计数与跳转按钮。
// 8 个参数:查询状态 + 计数 + 三个跳转回调 + modifier;聚合进 data class 会割裂调用点,
// 按 ChatSelectionBar 先例豁免 LongParameterList。
@Suppress("LongParameterList")
@Composable
internal fun InChatFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasMatches = matchCount > 0
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.tinyGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TablerIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(MusePaddings.tightGap))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_find_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(MusePaddings.tightGap))
            FindStatusLabel(query = query, matchCount = matchCount, currentIndex = currentIndex)
            // E4 (H8): 纯图标导航按钮加 hover 提示(桌面);移动端无 hover 事件不显示
            MuseTooltip(text = stringResource(R.string.chat_find_prev)) {
                FindNavIconButton(
                    icon = Icons.Filled.KeyboardArrowUp,
                    description = stringResource(R.string.chat_find_prev),
                    enabled = hasMatches,
                    onClick = onPrev,
                )
            }
            MuseTooltip(text = stringResource(R.string.chat_find_next)) {
                FindNavIconButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    description = stringResource(R.string.chat_find_next),
                    enabled = hasMatches,
                    onClick = onNext,
                )
            }
            MuseTooltip(text = stringResource(R.string.chat_find_close)) {
                FindNavIconButton(
                    icon = Icons.Filled.Close,
                    description = stringResource(R.string.chat_find_close),
                    enabled = true,
                    onClick = onClose,
                )
            }
        }
    }
}

// A1: 查找状态标签 — 无命中显示"无匹配"(红色),有命中显示"第 x / y 条"
@Composable
private fun FindStatusLabel(query: String, matchCount: Int, currentIndex: Int) {
    val hasMatches = matchCount > 0
    when {
        query.isNotBlank() && !hasMatches -> {
            Text(
                text = stringResource(R.string.chat_find_no_match),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        hasMatches -> {
            Text(
                text = stringResource(R.string.chat_find_count, currentIndex, matchCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// A1: 查找条导航按钮 — 上一条/下一条/关闭共用样式;disabled 时半透明且点击无效
@Composable
private fun FindNavIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
        )
    }
}
