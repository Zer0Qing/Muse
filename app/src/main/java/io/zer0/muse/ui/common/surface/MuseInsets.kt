package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier

/**
 * Muse 系统栏适配唯一入口。
 *
 * 规则：
 * - 顶栏容器负责 status bar，页面内容不再重复加 statusBarsPadding。
 * - 底栏/底部面板整体负责 navigation bar、系统手势区和 IME，内部内容只处理业务间距。
 * - 背景层可以沉浸式延伸，但可点击内容层必须使用对应安全区 modifier。
 */
fun Modifier.museTopBarInsets(): Modifier = statusBarsPadding()

fun Modifier.museBottomBarInsets(): Modifier =
    navigationBarsPadding()
        .imePadding()

fun Modifier.museDialogInsets(): Modifier =
    imePadding().navigationBarsPadding()
