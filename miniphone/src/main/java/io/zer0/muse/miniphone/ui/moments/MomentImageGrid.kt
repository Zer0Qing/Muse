package io.zer0.muse.miniphone.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.data.moment.MOMENT_MAX_IMAGES
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 一条动态的图片区。
 *
 * 列数由 [momentGridColumns] 定:一张独占、两张与四张两列、其余三列。
 * 每格切成正方形(等比裁切),一行没排满时剩下的位置留空 —— 图不会因为张数少就被拉宽。
 *
 * @param maxWidth 可用宽度,由调用方从 `BoxWithConstraints` 里量出来
 * @param onOpen 点某张图时的回调,参数是它在组里的下标(零基) —— 图集查看器从这一张开始
 */
@Composable
internal fun MomentImageGrid(momentId: String, images: List<String>, maxWidth: Dp, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shown = remember(images) { images.take(MOMENT_MAX_IMAGES) }
    if (shown.isEmpty()) return
    if (shown.size == 1) {
        MomentImageCell(
            path = shown[0],
            size = minOf(maxWidth, SingleImageMaxEdge),
            tag = MiniPhoneMomentsTags.image(momentId, 0),
            onOpen = { onOpen(0) },
            modifier = modifier,
        )
        return
    }
    val columns = momentGridColumns(shown.size)
    val cell = ((maxWidth - CellGap * (columns - 1)) / columns).coerceAtMost(CellMaxEdge)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CellGap)) {
        shown.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CellGap)) {
                row.forEachIndexed { columnIndex, path ->
                    val index = rowIndex * columns + columnIndex
                    MomentImageCell(
                        path = path,
                        size = cell,
                        tag = MiniPhoneMomentsTags.image(momentId, index),
                        onOpen = { onOpen(index) },
                    )
                }
            }
        }
    }
}

/**
 * 九宫格列数。
 *
 * 一张给一张大图,两张和四张按两列摆(四张正好 2×2),其余三列 ——
 * 三张排一行、五到九张填满九宫格,张数与留白的关系最直观。
 */
internal fun momentGridColumns(count: Int): Int = when {
    count <= SINGLE_COLUMN_MAX -> 1
    count == TWO_COLUMN_EXACT || count == TWO_COLUMN_SQUARE -> 2
    else -> 3
}

/** 一格图:固定正方形,加载中先压一块底色,不让行高从 0 跳开。 */
@Composable
private fun MomentImageCell(path: String, size: Dp, tag: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(ImageShape)
            .background(colors.divider)
            .clickable(onClick = onOpen)
            .testTag(tag),
    ) {
        AsyncImage(
            model = path,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
        )
    }
}

/** 一张图时的边长上限:再大就把正文挤到屏幕外了。 */
private val SingleImageMaxEdge = 220.dp

/** 多图时一格的边长上限。 */
private val CellMaxEdge = 96.dp

private val CellGap = 4.dp
private val ImageShape = RoundedCornerShape(6.dp)

private const val SINGLE_COLUMN_MAX: Int = 1
private const val TWO_COLUMN_EXACT: Int = 2
private const val TWO_COLUMN_SQUARE: Int = 4
