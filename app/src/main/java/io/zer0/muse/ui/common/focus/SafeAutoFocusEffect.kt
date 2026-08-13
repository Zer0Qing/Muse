package io.zer0.muse.ui.common.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * v1.0.74 fix: 安全的自动聚焦 — 解决华为 Android 10 上偶发崩溃
 * `IllegalStateException: FocusRequester is not initialized`。
 *
 * 崩溃根因:在 LaunchedEffect 里立即 requestFocus(),但目标组件可能尚未组合完成:
 *  - LazyColumn item 的内容在 measure 阶段才组合(延迟组合),而 LaunchedEffect
 *    在 composition applyChanges 后立即启动 → focusRequester modifier 还没 attach
 *  - 条件渲染分支(isSearching 等)与 effect 的生命周期不同步 → 竞态窗口
 *
 * 修复:先等一帧(确保上一帧的 measure/组合全部完成、modifier 已 attach),
 * 再请求焦点;仍失败则静默跳过(不崩溃,下次交互自然聚焦)。
 */
@Composable
fun SafeAutoFocusEffect(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}

/** 同上,但可由调用方控制触发条件(需要读其他状态时用)。 */
@Composable
fun SafeAutoFocusEffect(condition: () -> Boolean, focusRequester: FocusRequester) {
    LaunchedEffect(Unit) {
        if (condition()) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
}
