package io.zer0.muse.ui.common.state

import androidx.compose.runtime.Composable
/**
 * 页面内容状态统一分发器。
 *
 * 页面只负责提供状态和内容，加载、空数据、错误三种视觉由基础组件统一维护，
 * 避免每个页面各自写一套 CircularProgressIndicator/Text/Retry Button。
 */
@Composable
fun <T> MuseContentState(
    value: T?,
    isLoading: Boolean,
    errorMessage: String? = null,
    isEmpty: (T) -> Boolean = { false },
    empty: @Composable () -> Unit,
    error: @Composable (String) -> Unit = { MuseErrorStateBox(it) },
    loading: @Composable () -> Unit = { MuseLoadingState() },
    content: @Composable (T) -> Unit,
) {
    when {
        isLoading -> loading()
        errorMessage != null -> error(errorMessage)
        value == null || isEmpty(value) -> empty()
        else -> content(value)
    }
}
