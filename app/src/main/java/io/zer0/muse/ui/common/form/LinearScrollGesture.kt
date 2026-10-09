package io.zer0.muse.ui.common.form

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 长会话**线性滚动手势**（v2.5.9 起取代可视轨道）。
 *
 * 交互：在屏幕右侧热区内**长按**激活（轻震动提示），随后上下滑动即可线性滚动内容。
 *
 * ## 为什么不做可视轨道
 *
 * LazyColumn 不暴露总高度，只能用"已测项真实高度 + 未测项按平均高外推"估算
 * （见 [LinearScrollModel]）。长条目（图片/代码块）的真实高度远超平均高，估算误差
 * 极大。任何"手指绝对位置 → 目标内容位置"的映射（可视轨道的本质）都会因误差而
 * 落错位置 —— 表现为拖动"乱飘"。增量映射绕开了这个根本矛盾：按下瞬间快照基准，
 * 拖动中只把**手指位移**换算成**内容位移**，基准不变 → 连续、不可能跳。
 *
 * ## 映射
 *
 * `内容位移 = 手指位移 / 热区高度 × 可达滚动范围`。语义等同滚动条：拇指从当前位置
 * 滑过整个热区高度，内容正好滚完整个可达范围。总高估算的误差只影响"灵敏度"，不再
 * 影响"落点正确性"。
 *
 * 尚未测量（模型不可用）时不响应，保证不误伤。
 */
internal val RAIL_TOUCH_WIDTH: Dp = 40.dp

/** 拖到底时补滚的像素量（滚到真实末尾，绕开总高估算误差；滚动会自行钳到边界）。 */
private const val BOTTOM_OVERSCROLL_PX = 1_000_000f

/** 列表可视高度（px）。优先用视口尺寸；尚未布局时回退到视口末端偏移。 */
internal fun listViewportPx(state: LazyListState): Float {
    val h = state.layoutInfo.viewportSize.height.toFloat()
    return if (h > 0f) h else state.layoutInfo.viewportEndOffset.toFloat()
}

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

    /** 内容偏移（px）落在哪个条目（用当前前缀反查）。 */
    fun indexAtOffset(offset: Float): Int {
        if (prefix.size <= 1) return 0
        var i = 0
        val last = prefix.size - 2
        while (i < last && prefix[i + 1] <= offset) i++
        return i
    }
}

/**
 * 右侧长按线性滚动热区。
 *
 * @param listState 目标列表。
 * @param onActiveIndexChange 滑动命中条目的**列表绝对索引**回调（null 表示结束）。
 */
@Suppress("FunctionNaming")
@Composable
internal fun LinearScrollGesture(listState: LazyListState, modifier: Modifier = Modifier, onActiveIndexChange: ((Int?) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var active by remember { mutableStateOf(false) }
    val scrollJob = remember { mutableStateOf<Job?>(null) }
    val model = remember { LinearScrollModel() }
    // 激活期间**冻结**高度模型：基准在长按成立时快照，滑动中不接受新测量，
    // 保证"手指位移 → 内容位移"的换算系数稳定。
    if (!active) model.update(listState)

    LaunchedEffect(active) {
        if (!active) onActiveIndexChange?.invoke(null)
    }

    Box(
        modifier =
        modifier
            .width(RAIL_TOUCH_WIDTH)
            .fillMaxHeight()
            .pointerInput(listState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 长按才激活；短按/快速滑动不拦截，让事件自然结束。
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    active = true
                    runLinearDrag(down, longPress.position.y, model, listState, scrollJob, scope, onActiveIndexChange)
                    active = false
                }
            },
    )
}

/**
 * 长按成立后的拖动循环：把手指位移换算成内容位移（增量映射）。
 *
 * `内容位移 = 手指位移 / 热区高 × 可达滚动范围`；基准在长按成立时快照，全程不变。
 */
@Suppress("LongParameterList")
private suspend fun AwaitPointerEventScope.runLinearDrag(
    down: PointerInputChange,
    startY: Float,
    model: LinearScrollModel,
    listState: LazyListState,
    scrollJob: MutableState<Job?>,
    scope: CoroutineScope,
    onActiveIndexChange: ((Int?) -> Unit)?,
) {
    val railHeight = size.height.toFloat().coerceAtLeast(1f)
    val totalPx = model.totalPx(listState)
    val viewportPx = listViewportPx(listState)
    val range = (totalPx - viewportPx).coerceAtLeast(0f)
    val startScroll = model.scrolledPx(listState)
    val count = listState.layoutInfo.totalItemsCount
    // 滑过整个热区高度 = 滚完整个可达范围。
    val sensitivity = if (railHeight > 0f) range / railHeight else 0f

    fun seek(targetPx: Float) {
        onActiveIndexChange?.invoke(model.indexAtOffset(targetPx.coerceIn(0f, range)))
        scrollJob.value?.cancel()
        scrollJob.value =
            scope.launch {
                seekList(listState, targetPx, range, count, model)
            }
    }

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return
        if (!change.pressed) return
        change.consume()
        if (range > 0f) seek(startScroll + (change.position.y - startY) * sensitivity)
    }
}

/** 按目标内容偏移定位列表：两端直接落到真首/真末，中间按条目 + 条目内偏移。 */
private suspend fun seekList(listState: LazyListState, targetPx: Float, range: Float, count: Int, model: LinearScrollModel) {
    when {
        targetPx <= 0f -> listState.scrollToItem(0)
        count > 0 && range > 0f && targetPx >= range -> {
            listState.scrollToItem(count - 1)
            listState.scrollBy(BOTTOM_OVERSCROLL_PX)
        }
        else -> {
            val t = targetPx.coerceIn(0f, range)
            val index = model.indexAtOffset(t)
            val within = (t - model.offsetPx(index)).coerceAtLeast(0f)
            listState.scrollToItem(index, within.toInt())
        }
    }
}
