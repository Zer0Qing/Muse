package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Shared visual scrim for modal surfaces; platform window dim remains disabled.
 *
 * 深浅主题要分别取 alpha：深色主题的背景本身已经很暗，沿用浅色主题的 38% 黑
 * 会让遮罩几乎看不出层次，弹窗和背景糊在一起、发灰。深色下需要更重的遮罩才能
 * 把底层推远，所以两档分开取值。
 */
@Composable
internal fun museModalScrimColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val darkBackground = scheme.background.luminance() < 0.5f
    return scheme.scrim.copy(alpha = if (darkBackground) 0.62f else 0.38f)
}

/**
 * 模态遮罩：**状态栏与导航栏区域都不压暗**。
 *
 * 之前只留了导航栏安全区，状态栏被遮罩整片盖住 —— 浅色主题下表现为「一弹窗
 * 状态栏就突然变黑」，与不弹窗时的白底形成明显割裂。这里把状态栏也留出来，
 * 让系统栏始终保持宿主窗口原本的样子，遮罩只覆盖真正的内容区域。
 */
@Composable
internal fun Modifier.museModalScrim(): Modifier = statusBarsPadding()
    .navigationBarsPadding()
    .background(museModalScrimColor())
