package io.zer0.muse.ui.assistant

import androidx.compose.runtime.Composable
import io.zer0.ai.video.VideoGenerationService
import io.zer0.muse.data.SettingsRepository
import org.koin.compose.koinInject

/**
 * 创作工坊视频入口壳：保留兼容函数签名并委托给统一 [io.zer0.muse.ui.media.MediaStudioScreen]。
 */
@Composable
fun VideoGenerationPage(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") videoService: VideoGenerationService = koinInject(),
    @Suppress("UNUSED_PARAMETER") settingsRepo: SettingsRepository = koinInject(),
) {
    io.zer0.muse.ui.media.MediaStudioScreen(
        onBack = onBack,
        initialTab = io.zer0.muse.ui.media.MediaStudioTab.VIDEO,
    )
}
