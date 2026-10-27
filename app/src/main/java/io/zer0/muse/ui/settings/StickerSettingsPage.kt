package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import org.koin.compose.koinInject

/**
 * 表情包库独立页。
 *
 * 内容与「媒体」页底部那一段完全同源（[StickerLibrarySection]），
 * 这里只是给它一个能找得到的门。两处入口并存不冲突。
 */
@Composable
fun StickerSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_chat_sticker_section),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MusePaddings.screen,
                end = MusePaddings.screen,
                top = 8.dp,
                bottom = 24.dp,
            ),
        ) {
            item(key = "sticker_library") {
                StickerSettingsSection(settings = settings, scope = scope)
            }
        }
    }
}
