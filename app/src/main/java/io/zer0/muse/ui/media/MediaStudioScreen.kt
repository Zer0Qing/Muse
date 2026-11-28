package io.zer0.muse.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.media.MediaAssetStorage
import io.zer0.muse.data.media.MediaGenTaskEntity
import io.zer0.muse.data.media.MediaGenerationManager
import io.zer0.muse.data.media.StudioImageRequest
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import org.koin.compose.koinInject

/** Top-level tab inside [MediaStudioScreen]. */
enum class MediaStudioTab { IMAGE, VIDEO, GALLERY }

/**
 * Unified 3-tab Media Studio (Image / Video / Gallery) backed by Room task queue and local asset storage.
 */
@Composable
fun MediaStudioScreen(
    onBack: () -> Unit,
    onOpenMediaSettings: () -> Unit = {},
    initialTab: MediaStudioTab = MediaStudioTab.IMAGE,
    manager: MediaGenerationManager = koinInject(),
    storage: MediaAssetStorage = koinInject(),
    settings: SettingsRepository = koinInject(),
) {
    var selectedTabIndex by rememberSaveable { mutableStateOf(initialTab.ordinal) }
    val selectedTab = MediaStudioTab.entries.getOrElse(selectedTabIndex) { MediaStudioTab.IMAGE }

    // Cross-tab prefill state (e.g. from Gallery asset -> Video ref image, or reuse Image parameters)
    var videoPrefillRefUri by remember { mutableStateOf<String?>(null) }
    var imagePrefillRequest by remember { mutableStateOf<StudioImageRequest?>(null) }

    val tasks by manager.observeTasks().collectAsStateWithLifecycle(initialValue = emptyList())
    val assets by manager.observeAssets().collectAsStateWithLifecycle(initialValue = emptyList())
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    val activeCount = tasks.count {
        it.status == MediaGenTaskEntity.STATUS_RUNNING || it.status == MediaGenTaskEntity.STATUS_QUEUED
    }
    val galleryLabel = if (activeCount > 0) {
        stringResource(R.string.media_studio_tab_gallery_count, activeCount)
    } else {
        stringResource(R.string.media_studio_tab_gallery)
    }
    val tabLabels = listOf(
        stringResource(R.string.media_studio_tab_image),
        stringResource(R.string.media_studio_tab_video),
        galleryLabel,
    )

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.media_studio_title),
                onBack = onBack,
                actions = {
                    MuseTopBarIconButton(
                        icon = MuseIcons.sliders,
                        contentDescription = stringResource(R.string.media_studio_settings_cd),
                        onClick = onOpenMediaSettings,
                        solid = true,
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            MuseCapsuleTab(
                tabs = tabLabels,
                selectedIndex = selectedTab.ordinal,
                onSelect = { idx -> selectedTabIndex = idx },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MuseSpacing.xs),
            )

            when (selectedTab) {
                MediaStudioTab.IMAGE -> {
                    MediaStudioImageTab(
                        providers = providers,
                        activeTasks = tasks.filter {
                            it.kind == MediaGenTaskEntity.KIND_IMAGE &&
                                (it.status == MediaGenTaskEntity.STATUS_RUNNING || it.status == MediaGenTaskEntity.STATUS_QUEUED)
                        },
                        prefillRequest = imagePrefillRequest,
                        onConsumePrefill = { imagePrefillRequest = null },
                        onOpenMediaSettings = onOpenMediaSettings,
                        onSubmit = { req ->
                            manager.submitImageTask(req)
                        },
                        onCancelTask = manager::cancelTask,
                        scope = scope,
                    )
                }
                MediaStudioTab.VIDEO -> {
                    MediaStudioVideoTab(
                        providers = providers,
                        activeTasks = tasks.filter {
                            it.kind == MediaGenTaskEntity.KIND_VIDEO &&
                                (it.status == MediaGenTaskEntity.STATUS_RUNNING || it.status == MediaGenTaskEntity.STATUS_QUEUED)
                        },
                        prefillRefUri = videoPrefillRefUri,
                        onConsumePrefillRef = { videoPrefillRefUri = null },
                        onOpenMediaSettings = onOpenMediaSettings,
                        onSubmit = { req ->
                            manager.submitVideoTask(req)
                        },
                        onCancelTask = manager::cancelTask,
                        scope = scope,
                    )
                }
                MediaStudioTab.GALLERY -> {
                    MediaStudioGalleryTab(
                        tasks = tasks,
                        assets = assets,
                        storage = storage,
                        manager = manager,
                        scope = scope,
                        onGoCreateImage = { selectedTabIndex = MediaStudioTab.IMAGE.ordinal },
                        onGoCreateVideo = { selectedTabIndex = MediaStudioTab.VIDEO.ordinal },
                        onReuseImageParams = { req ->
                            imagePrefillRequest = req
                            selectedTabIndex = MediaStudioTab.IMAGE.ordinal
                        },
                        onUseImageForVideo = { localPath ->
                            videoPrefillRefUri = "file://$localPath"
                            selectedTabIndex = MediaStudioTab.VIDEO.ordinal
                        },
                    )
                }
            }
        }
    }
}
