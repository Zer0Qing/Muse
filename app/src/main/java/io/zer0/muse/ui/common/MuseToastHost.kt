package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

/**
 * MuseToast 渲染宿主 — 放在根 Composable 中,观察 [MuseToast] 状态。
 *
 * 当 MuseToast.show() 被调用时,顶部滑入一个半透明胶囊提示,
 * 超时后自动滑出消失。
 *
 * iOS 风格:
 *  - 深色半透明背景(暗色主题下更明显)
 *  - 白色文字
 *  - 胶囊圆角(24dp)
 *  - 顶部居中悬浮,不遮挡输入区域
 */
@Composable
fun MuseToastHost() {
    val toastData by MuseToast.message.collectAsState()

    // M-MTH1: 缓存上一次非空的 toast 文本。
    // 退出动画期间 toastData 已被 clear 为 null,若直接用 toastData?.text 会渲染空文本,
    // 导致滑出动画看到空白胶囊。用 remember 保留最后一次的文本,退出动画仍显示原文本。
    var lastText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toastData) {
        // v1.74: 局部变量捕获避免 !!(委托属性无法 smart-cast)
        val data = toastData
        if (data != null) {
            lastText = data.text
            kotlinx.coroutines.delay(data.durationMillis)
            MuseToast.clear()
        }
    }

    AnimatedVisibility(
        visible = toastData != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // M-MTH2: 60.dp 硬编码 → statusBars inset,适配刘海/状态栏高度差异。
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                shape = MuseShapes.huge,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                shadowElevation = 8.dp,
                // M-MTH3: liveRegion = Polite, toast 文本变化时 TalkBack 自动朗读,
                // 对标原生 Toast 的无障碍朗读行为(不打断用户当前操作,空闲时朗读)。
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = lastText ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
