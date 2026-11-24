package io.zer0.muse.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import org.koin.compose.koinInject

/**
 * 表情包库独立页。
 */
@Composable
fun StickerSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_chat_sticker_section),
        onBack = onBack,
    ) {
        item(key = "sticker_library") {
            StickerLibrarySection(settings = settings, scope = scope)
        }
    }
}
