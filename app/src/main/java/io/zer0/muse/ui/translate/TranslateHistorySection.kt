@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.translate

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseIconContainer
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.favoriteStarTint
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 翻译历史区。
 *
 * [history] 已由调用方根据 [favoritesOnly] 取好（完整历史 / 仅收藏），
 * [totalHistoryCount] 传入未筛选的条数，仅用于决定收藏开关是否显示 ——
 * 否则收藏为空时开关也跟着消失，用户就卡在空列表回不去了。
 */
@Suppress("LongParameterList")
@Composable
internal fun TranslateHistorySection(
    history: List<TranslateViewModel.TranslateHistoryItem>,
    totalHistoryCount: Int,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onItemClick: (TranslateViewModel.TranslateHistoryItem) -> Unit,
    onClearClick: () -> Unit,
    onToggleFavorite: (TranslateViewModel.TranslateHistoryItem) -> Unit,
) {
    val context = LocalContext.current
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
                    imageVector = MuseIcons.history,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                // §11-6: 翻译收藏以前是完整断路 —— 星标能点、能落库、能进 state.favorites，
                // 但 UI 从不渲染 favorites。这里加一个"仅看收藏"筛选，口径与朋友圈的
                // favoritesOnly 同型（同一个开关决定列表源），不另开页面。
                // 开关按「完整历史」而非当前列表判断是否显示，否则收藏为空时开关也会消失，
                // 用户就卡在空列表里回不去了。
                if (totalHistoryCount > 0) {
                    MuseTactileButton(
                        icon = MuseIcons.star,
                        onClick = { onFavoritesOnlyChange(!favoritesOnly) },
                        contentDescription = stringResource(
                            if (favoritesOnly) {
                                R.string.translate_page_history_show_all
                            } else {
                                R.string.translate_page_history_favorites_only
                            },
                        ),
                        tint = favoriteStarTint(favoritesOnly),
                        container = if (favoritesOnly) MuseIconContainer.Tonal else MuseIconContainer.None,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.trash,
                        onClick = onClearClick,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (history.isEmpty()) {
            // ST-03: 空态统一 MuseEmptyState。收藏视图的空态要与"完全没有历史"区分。
            MuseEmptyState(
                title = stringResource(
                    if (favoritesOnly) {
                        R.string.translate_page_history_no_favorites
                    } else {
                        R.string.translate_page_history_empty
                    },
                ),
                modifier = Modifier.padding(vertical = MusePaddings.itemGap),
            )
            return
        }

        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
            history.forEach { item ->
                TranslateHistoryItemCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onToggleFavorite = { onToggleFavorite(item) },
                    onLongPressCopy = {
                        // v2.4.6: 长按复制"原文 + 译文"(两者都带上,便于直接黏贴使用)
                        val ctx = context
                        copyToClipboard(
                            ctx,
                            ctx.getString(
                                R.string.translate_history_copy_format,
                                item.sourceText,
                                item.translatedText,
                            ),
                        )
                        MuseToast.show(ctx.getString(R.string.translate_history_copied))
                    },
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranslateHistoryItemCard(
    item: TranslateViewModel.TranslateHistoryItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongPressCopy: () -> Unit,
) {
    val timeText = remember(item.timestamp) { formatHistoryTime(item.timestamp) }

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPressCopy,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.auxGap),
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
                        // LAYOUT-01: 卡片内语言胶囊是行内短文本(行宽由内容决定,不取宽),限 1 行防逐字换行。
                        Text(
                            text = item.sourceLanguage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = MuseIcons.swapHorizontal,
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
                    // LAYOUT-01: 时间标注是行尾定宽短文本,限 1 行,防止被收藏按钮挤成一列。
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                    MuseTactileButton(
                        // §11-8: 单枚 star 图标 + tint + 底色三档表达选中态（无 starOutline 可用）。
                        icon = MuseIcons.star,
                        onClick = onToggleFavorite,
                        contentDescription = stringResource(
                            if (item.favorite) {
                                R.string.translate_page_favorite_remove
                            } else {
                                R.string.translate_page_favorite_add
                            },
                        ),
                        tint = favoriteStarTint(item.favorite),
                        // 已收藏给实心圆底，未收藏无底，色块差异比颜色差异更抗深浅主题漂移。
                        container = if (item.favorite) MuseIconContainer.Tonal else MuseIconContainer.None,
                        size = MuseIconSizes.touchTarget,
                        iconSize = MuseIconSizes.iconSmall,
                    )
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
        // LAYOUT-01: 「原文/译文」标签是行首定宽短词(剩余宽度归正文的 weight),限 1 行。
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
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
