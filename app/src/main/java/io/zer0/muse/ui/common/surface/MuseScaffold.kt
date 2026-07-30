package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * v1.0.29: Muse 通用 Scaffold,统一处理状态栏 insets。
 *
 * 项目历史问题:在 edge-to-edge 下,各页面自己处理 statusBarsPadding(),
 * 非常容易遗漏,导致页面上滑时内容侵入状态栏(很常见,之前每次都手动修)。
 *
 * 本组件对所有页面提供一致行为:
 *  - 自动为 topBar 加 statusBarsPadding() + 背景色,避免内容滑到状态栏后面
 *  - 禁用 Scaffold 默认 contentWindowInsets,改由调用方通过 innerPadding 自己控制
 *  - 保持与 Material3 Scaffold 兼容的 API
 *
 * 使用方式:把各页面的 `Scaffold(...)` 替换为 `MuseScaffold(...)`,
 * 并确保传入的 topBar 内部不再自己调用 statusBarsPadding()/background()。
 */
@Composable
fun MuseScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(containerColor)
                    .statusBarsPadding(),
            ) {
                topBar()
            }
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        containerColor = containerColor,
        // 禁用默认 insets,由本组件统一在 topBar 层处理 statusBars,
        // 避免与 topBar 的 statusBarsPadding 重复计算。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        content(innerPadding)
    }
}
