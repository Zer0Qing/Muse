package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P2-9（审计报告）: 让横向滚动/翻页表面豁免系统返回手势。
 *
 * 报告指出的问题：
 *  - 全仓 0 处 `systemGestureExclusionRects`
 *  - `MusePaddings.screen = 16.dp`，内容距屏幕边缘仅 16dp，
 *    而系统返回手势区通常是左右各 20dp → 从边缘起手的横滑会先触发返回
 *  - 受影响：首页 HorizontalPager、对话列表 SwipeToDismiss、快捷捕获边缘手柄、各类 LazyRow
 *
 * 用法（加在**横向**手势表面上）：
 * ```
 * LazyRow(modifier = Modifier.horizontalGestureExclusion())
 * ```
 *
 * 实现要点：`systemGestureExclusionRects` 是**整个 View 共享**的列表，
 * 多个组件各自赋值会互相覆盖。这里用累积器统一合并后写回。
 * 注意它接受的是 [android.graphics.Rect]，不是 Compose 的 Rect。
 */
@Composable
fun Modifier.horizontalGestureExclusion(
    width: Dp = 24.dp,
): Modifier {
    val density = LocalDensity.current
    val view = LocalView.current
    val stripPx: Int = with(density) { width.roundToPx() }

    val registry = remember(view) { GestureExclusionRegistry(view) }
    val token = remember(registry) { registry.acquire() }

    DisposableEffect(registry, token) {
        onDispose { registry.release(token) }
    }

    return this.onGloballyPositioned { coords ->
        val bounds = coords.boundsInWindow()
        if (bounds.width <= 0f || stripPx <= 0) return@onGloballyPositioned
        registry.update(
            token,
            listOf(
                android.graphics.Rect(
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    (bounds.left + stripPx).toInt(),
                    bounds.bottom.toInt(),
                ),
                android.graphics.Rect(
                    (bounds.right - stripPx).toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt(),
                ),
            ),
        )
    }
}

/**
 * 手势排除区累积器：多组件共享一个 View 上的排除列表。
 */
private class GestureExclusionRegistry(private val view: android.view.View) {
    private val regions = mutableMapOf<Int, List<android.graphics.Rect>>()
    private var nextToken = 0

    @Synchronized
    fun acquire(): Int = nextToken++

    @Synchronized
    fun update(token: Int, rects: List<android.graphics.Rect>) {
        regions[token] = rects
        flush()
    }

    @Synchronized
    fun release(token: Int) {
        regions.remove(token)
        flush()
    }

    private fun flush() {
        val merged = regions.values.flatten()
        runCatching { view.systemGestureExclusionRects = merged }
    }
}
