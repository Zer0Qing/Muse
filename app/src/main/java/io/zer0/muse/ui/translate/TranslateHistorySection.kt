@file:Suppress("FunctionNaming", "LongMethod")
package io.zer0.muse.ui.translate


import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TranslateHistorySection(
    history: List<TranslateViewModel.TranslateHistoryItem>,
    onItemClick: (TranslateViewModel.TranslateHistoryItem) -> Unit,
    onClearClick: () -> Unit,
    onToggleFavorite: (TranslateViewModel.TranslateHistoryItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = stringResource(R.string.translate_page_history_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (history.isNotEmpty()) {
                TextButton(
                    onClick = onClearClick,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = stringResource(R.string.translate_page_history_clear_short),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (history.isEmpty()) {
            Surface(
                shape = MuseShapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.translate_page_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
            return
        }

        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
            history.forEach { item ->
                TranslateHistoryItemCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onToggleFavorite = { onToggleFavorite(item) },
                )
            }
        }
    }
}

/**
 * 单条翻译历史卡片 — 设计图风格。
 *
 * 顶部:语言流向 chip + 相对时间 + 收藏星标
 * 中部:原 + 原文(单行省略)
 * 底部:译 + 译文(单行省略)
 */
@Composable
private fun TranslateHistoryItemCard(
    item: TranslateViewModel.TranslateHistoryItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val timeText = remember(item.timestamp) { formatHistoryTime(item.timestamp) }

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInnerMedium),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 源 → 目标语言 chip
                Surface(
                    shape = MuseShapes.pill,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = item.sourceLanguage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.Outlined.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                        Text(
                            text = item.targetLanguage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(
                                if (item.favorite) R.string.translate_page_favorite_remove
                                else R.string.translate_page_favorite_add
                            ),
                            tint = if (item.favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
            }
            // 原 + 原文
            HistoryTextLine(
                label = stringResource(R.string.translate_page_history_source_short),
                text = item.sourceText,
            )
            // 译 + 译文
            HistoryTextLine(
                label = stringResource(R.string.translate_page_history_translated_short),
                text = item.translatedText,
            )
        }
    }
}

/** 历史卡片内的"标签 + 内容"单行。 */
@Composable
private fun HistoryTextLine(label: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 格式化历史时间戳为相对时间。 */
private fun formatHistoryTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 60 * 60_000 -> "${diff / 60_000} 分钟前"
        diff < 24 * 60 * 60_000 -> "${diff / (60 * 60_000)} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
