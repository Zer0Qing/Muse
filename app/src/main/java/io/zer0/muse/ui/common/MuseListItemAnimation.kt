package io.zer0.muse.ui.common

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

// E5 (前端专项 H4): 列表项动画原语 — 主列表统一入场/位移过渡。
// 与 ChatScreen 消息行动画规格一致(SLOW_MS 淡入 + NORMAL_MS 位移);
// reduced-motion 降级为 0 时长(立即到位,不播放动画)。
// 用法:在 LazyColumn/LazyRow 的 items 块内对 item 根施加
//   items(list) { item -> Box(museAnimateItem()) { ItemView(...) } }
// 注:MuseMotion.isReducedMotion() 为 @Composable(读取系统设置),故本原语也须 @Composable。
@Composable
internal fun LazyItemScope.museAnimateItem(enabled: Boolean = true): Modifier {
    val reducedMotion = MuseMotion.isReducedMotion()
    val animate = enabled && !reducedMotion
    return Modifier.animateItem(
        fadeInSpec = if (!animate) {
            MuseMotion.tween(0)
        } else {
            MuseMotion.tween(MuseAnimation.SLOW_MS, easing = MuseAnimation.EaseOutCubic)
        },
        placementSpec = if (!animate) {
            MuseMotion.tween(0)
        } else {
            MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic)
        },
    )
}
