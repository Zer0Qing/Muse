package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 通用**线性滚动条**（v2.5.7 统一）。
 *
 * 与旧的"点阵 / 按条目序号"跳转条的区别：
 *  - 滑块**位置** ∝ 已滚过的内容量（内容高度口径，不是条目序号），
 *    所以长条目与短条目不再占同样一格，拖动手感与视觉内容一致；
 *  - 滑块**长度** ∝ 视口高度 / 内容总高，一眼看出"还剩多少"；
 *  - 支持点击定位 + 拖动连续定位。
 *
 * LazyColumn 不暴露总高度，这里用"已测量项真实高度 + 未测项按平均高外推"估算
 * （见 [LinearScrollModel]）。尚未测量时退化为按条目序号比例，保证总能工作。
 *
 * 典型用法见 `MessageMapBar`（带消息密度标记）与 `SettingsTutorialPage`（纯滚动条）。
 */
internal val RAIL_TOUCH_WIDTH: Dp = 40.dp
internal val RAIL_TRACK_WIDTH: Dp = 22.dp

/**
 * 内容高度模型：把"滚动位置"从条目序号口径换算到内容量口径。
 *
 * LazyColumn 只暴露当前可见项，用两个来源估算总高：
 *  - 已测量项的真实高度（精确，来自 visibleItemsInfo）；
 *  - 未测量项用"已测平均高"外推（近似）。
 */
internal class LinearScrollModel {
    private val measured = HashMap<Int, Int>()
    private var dirty = true
    private var avgCache = 0f
    private var prefix: FloatArray = FloatArray(0)

    /** 用当前可见项高度刷新缓存（每次重组调用即可）。 */
    fun update(state: LazyListState) {
        state.layoutInfo.visibleItemsInfo.forEach { info ->
            if (info.size > 0 && measured[info.index] != info.size) {
                measured[info.index] = info.size
                dirty = true
            }
        }
    }

    private fun avgHeight(): Float {
        if (measured.isEmpty()) return 0f
        if (dirty) avgCache = measured.values.sum().toFloat() / measured.size
        return avgCache
    }

    /** 重建前缀和：prefix[i] = 前 i 条的累计高度（px）。 */
    private fun ensurePrefix(count: Int) {
        if (!dirty && prefix.size == count + 1) return
        val avg = avgHeight()
        val arr = FloatArray(count + 1)
        var sum = 0f
        for (i in 0 until count) {
            arr[i] = sum
            sum += measured[i]?.toFloat() ?: avg
        }
        arr[count] = sum
        prefix = arr
        dirty = false
    }

    /** 估算的内容总高（px）；无测量时返回 0。 */
    fun totalPx(state: LazyListState): Float {
        val avg = avgHeight()
        if (avg <= 0f) return 0f
        val count = state.layoutInfo.totalItemsCount
        ensurePrefix(count)
        return prefix.getOrElse(count) { 0f }
    }

    /** 第 [index] 条的起始偏移（px）。 */
    fun offsetPx(index: Int): Float {
        val safe = index.coerceAtLeast(0)
        return if (safe < prefix.size) prefix[safe] else 0f
    }

    /** 已滚过的内容偏移（px）。 */
    fun scrolledPx(state: LazyListState): Float {
        ensurePrefix(state.layoutInfo.totalItemsCount)
        return offsetPx(state.firstVisibleItemIndex) + state.firstVisibleItemScrollOffset
    }

    /** 拖动比例（0..1）→ 目标条目索引；模型不可用时退化为条目序号比例。 */
    fun targetIndex(state: LazyListState, fraction: Float, viewportPx: Float): Int {
        val count = state.layoutInfo.totalItemsCount
        if (count <= 0) return 0
        val clamped = fraction.coerceIn(0f, 1f)
        val totalPx = totalPx(state)
        return if (totalPx <= 0f || viewportPx <= 0f) {
            (clamped * (count - 1)).toInt().coerceIn(0, count - 1)
        } else {
            val target = clamped * (totalPx - viewportPx).coerceAtLeast(0f)
            var index = 0
            while (index < count - 1 && offsetPx(index + 1) <= target) index++
            index
        }
    }

    /** 拖动比例 → 条目内剩余偏移（px），用于亚条目微调。 */
    fun withinItemPx(state: LazyListState, fraction: Float, viewportPx: Float): Float {
        val totalPx = totalPx(state)
        if (totalPx <= 0f || viewportPx <= 0f) return 0f
        val target = fraction.coerceIn(0f, 1f) * (totalPx - viewportPx).coerceAtLeast(0f)
        return (target - offsetPx(targetIndex(state, fraction, viewportPx))).coerceAtLeast(0f)
    }
}

/** 滚动进度快照：滑块位置比例 + 滑块长度比例（均 0..1）。 */
internal data class RailProgress(val topFraction: Float, val lengthFraction: Float)

/** 从当前可见窗口计算滑块位置与长度（内容高度口径）。 */
internal fun railProgress(state: LazyListState, model: LinearScrollModel): RailProgress {
    val totalPx = model.totalPx(state)
    val viewportPx = state.layoutInfo.viewportSize.height.toFloat()
    return if (totalPx > 0f && viewportPx > 0f) {
        RailProgress(
            topFraction = (model.scrolledPx(state) / totalPx).coerceIn(0f, 1f),
            lengthFraction = (viewportPx / totalPx).coerceIn(0.05f, 1f),
        )
    } else {
        val count = state.layoutInfo.totalItemsCount.coerceAtLeast(1)
        val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        RailProgress(
            topFraction = (state.firstVisibleItemIndex.toFloat() / count).coerceIn(0f, 1f),
            lengthFraction = (visible.toFloat() / count).coerceIn(0.05f, 1f),
        )
    }
}

/**
 * 通用线性滚动条。
 *
 * @param listState 目标列表的滚动状态。
 * @param markerFractions 可选：密度标记的纵向比例列表（0..1），null 表示不画标记。
 * @param markerColors 与 [markerFractions] 一一对应的颜色。
 * @param onActiveIndexChange 拖动/点击命中的条目索引回调（null 表示离开）。
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun LinearScrollRail(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    markerFractions: List<Float>? = null,
    markerColors: List<Color>? = null,
    onActiveIndexChange: ((Int?) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var showRail by remember { mutableStateOf(false) }
    val scrollJob = remember { mutableStateOf<Job?>(null) }
    val model = remember { LinearScrollModel() }
    model.update(listState)

    // 松手后短暂停留再淡出。
    LaunchedEffect(isDragging, showRail) {
        if (!isDragging && showRail) {
            delay(900)
            showRail = false
            onActiveIndexChange?.invoke(null)
        }
    }

    val railAlpha by animateFloatAsState(
        targetValue = if (showRail) 1f else 0f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
        label = "rail-alpha",
    )

    fun jump(fraction: Float, viewportPx: Float) {
        scrollJob.value?.cancel()
        val index = model.targetIndex(listState, fraction, viewportPx)
        val within = model.withinItemPx(listState, fraction, viewportPx)
        scrollJob.value =
            scope.launch {
                listState.scrollToItem(index)
                if (within > 0.5f) listState.scrollBy(within)
            }
    }

    // 颜色在 composable 内解析（DrawScope 不可读 MaterialTheme）。
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.84f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val windowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)

    Box(
        modifier =
        modifier
            .width(RAIL_TOUCH_WIDTH)
            .fillMaxHeight()
            .pointerInput(listState) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        showRail = true
                        val vp = size.height.toFloat()
                        val fraction = (offset.y / size.height).coerceIn(0f, 1f)
                        onActiveIndexChange?.invoke(model.targetIndex(listState, fraction, vp))
                        jump(fraction, vp)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        isDragging = true
                        showRail = true
                        val vp = size.height.toFloat()
                        val fraction = (change.position.y / size.height).coerceIn(0f, 1f)
                        onActiveIndexChange?.invoke(model.targetIndex(listState, fraction, vp))
                        jump(fraction, vp)
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                )
            }
            .pointerInput(listState) {
                detectTapGestures { offset ->
                    showRail = true
                    val vp = size.height.toFloat()
                    val fraction = (offset.y / size.height).coerceIn(0f, 1f)
                    onActiveIndexChange?.invoke(model.targetIndex(listState, fraction, vp))
                    jump(fraction, vp)
                }
            },
    ) {
        val progress = railProgress(listState, model)
        Canvas(
            modifier =
            Modifier
                .width(RAIL_TRACK_WIDTH)
                .fillMaxHeight()
                .alpha(railAlpha),
        ) {
            drawRail(
                progress = progress,
                markerFractions = markerFractions,
                markerColors = markerColors,
                trackColor = trackColor,
                outlineColor = outlineColor,
                windowColor = windowColor,
            )
        }
    }
}

/** 绘制：胶囊轨道 + 可选密度标记 + 当前窗口滑块。 */
@Suppress("LongParameterList")
private fun DrawScope.drawRail(
    progress: RailProgress,
    markerFractions: List<Float>?,
    markerColors: List<Color>?,
    trackColor: Color,
    outlineColor: Color,
    windowColor: Color,
) {
    val w = size.width
    val h = size.height

    drawRoundRect(trackColor, Offset.Zero, Size(w, h), CornerRadius(w / 2f))
    drawRoundRect(
        color = outlineColor,
        topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
        size = Size(w - 1.dp.toPx(), h - 1.dp.toPx()),
        cornerRadius = CornerRadius((w - 1.dp.toPx()) / 2f),
        style = Stroke(width = 1.dp.toPx()),
    )

    // 条目密度标记（可选）。
    if (markerFractions != null && markerColors != null) {
        val markerW = 5.dp.toPx().coerceAtMost(w - 8.dp.toPx())
        val markerH = 2.dp.toPx()
        markerFractions.forEachIndexed { index, fraction ->
            val color = markerColors.getOrNull(index) ?: return@forEachIndexed
            val y = fraction.coerceIn(0f, 1f) * h
            drawRoundRect(
                color = color.copy(alpha = 0.86f),
                topLeft = Offset((w - markerW) / 2f, y - markerH / 2f),
                size = Size(markerW, markerH),
                cornerRadius = CornerRadius(markerH / 2f),
            )
        }
    }

    // 当前窗口滑块。
    val len = (progress.lengthFraction * h).coerceAtLeast(10.dp.toPx())
    val top = (progress.topFraction * h).coerceIn(0f, (h - len).coerceAtLeast(0f))
    val rr = CornerRadius((w - 2.dp.toPx()) / 2f)
    drawRoundRect(
        color = windowColor,
        topLeft = Offset(1.dp.toPx(), top),
        size = Size(w - 2.dp.toPx(), len),
        cornerRadius = rr,
    )
}
