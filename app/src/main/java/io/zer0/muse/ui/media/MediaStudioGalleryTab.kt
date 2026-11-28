package io.zer0.muse.ui.media
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.data.media.MediaAssetStorage
import io.zer0.muse.data.media.MediaGenAssetEntity
import io.zer0.muse.data.media.MediaGenTaskEntity
import io.zer0.muse.data.media.MediaGenerationManager
import io.zer0.muse.data.media.StudioImageRequest
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.largeCard
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

private enum class GalleryFilter { ALL, IMAGES, VIDEOS, FAVORITES }

@Composable
internal fun MediaStudioGalleryTab(
    tasks: List<MediaGenTaskEntity>,
    assets: List<MediaGenAssetEntity>,
    storage: MediaAssetStorage,
    manager: MediaGenerationManager,
    scope: CoroutineScope,
    onGoCreateImage: () -> Unit,
    onGoCreateVideo: () -> Unit,
    onReuseImageParams: (StudioImageRequest) -> Unit,
    onUseImageForVideo: (localPath: String) -> Unit,
) {
    var filter by remember { mutableStateOf(GalleryFilter.ALL) }
    var previewImageAsset by remember { mutableStateOf<MediaGenAssetEntity?>(null) }
    var playingVideoAsset by remember { mutableStateOf<MediaGenAssetEntity?>(null) }
    var deleteConfirmAsset by remember { mutableStateOf<MediaGenAssetEntity?>(null) }

    val activeTasks = remember(tasks) {
        tasks.filter {
            it.status == MediaGenTaskEntity.STATUS_RUNNING || it.status == MediaGenTaskEntity.STATUS_QUEUED
        }
    }
    val failedTasks = remember(tasks) {
        tasks.filter { it.status == MediaGenTaskEntity.STATUS_FAILED }.take(5)
    }
    val filteredAssets = remember(assets, filter) {
        when (filter) {
            GalleryFilter.ALL -> assets
            GalleryFilter.IMAGES -> assets.filter { it.kind == MediaGenTaskEntity.KIND_IMAGE }
            GalleryFilter.VIDEOS -> assets.filter { it.kind == MediaGenTaskEntity.KIND_VIDEO }
            GalleryFilter.FAVORITES -> assets.filter { it.favorite }
        }
    }

    MediaGalleryGrid(
        state = MediaGalleryGridState(
            filter = filter,
            activeTasks = activeTasks,
            failedTasks = failedTasks,
            filteredAssets = filteredAssets,
            manager = manager,
            scope = scope,
        ),
        actions = MediaGalleryGridActions(
            onFilter = { filter = it },
            onGoCreateImage = onGoCreateImage,
            onGoCreateVideo = onGoCreateVideo,
            onOpenAsset = { asset ->
                if (asset.kind == MediaGenTaskEntity.KIND_VIDEO) {
                    playingVideoAsset = asset
                } else {
                    previewImageAsset = asset
                }
            },
            onToggleFavorite = { asset -> scope.launch { manager.toggleFavorite(asset.id) } },
        ),
    )

    val galleryImages = remember(filteredAssets) {
        filteredAssets.filter { it.kind == MediaGenTaskEntity.KIND_IMAGE }
    }
    previewImageAsset?.let { initialAsset ->
        val startIndex = galleryImages.indexOfFirst { it.id == initialAsset.id }.coerceAtLeast(0)
        val viewerImages = remember(galleryImages) {
            if (galleryImages.isEmpty()) {
                listOf(initialAsset)
            } else {
                galleryImages
            }
        }
        val imageUris = remember(viewerImages) {
            viewerImages.map { asset ->
                File(asset.localPath).takeIf { it.exists() }?.let { "file://${it.absolutePath}" }
                    ?: asset.remoteUrl
                    ?: asset.localPath
            }
        }
        MediaImageFullScreenViewer(
            assets = viewerImages,
            imageUris = imageUris,
            initialIndex = startIndex,
            storage = storage,
            scope = scope,
            onDismiss = { previewImageAsset = null },
            onToggleFavorite = { asset -> scope.launch { manager.toggleFavorite(asset.id) } },
            onRequestDelete = { asset -> deleteConfirmAsset = asset },
            onReuseParams = { req ->
                previewImageAsset = null
                onReuseImageParams(req)
            },
            onSendToVideo = { path ->
                previewImageAsset = null
                onUseImageForVideo(path)
            },
        )
    }

    playingVideoAsset?.let { asset ->
        val liveAsset = filteredAssets.firstOrNull { it.id == asset.id } ?: asset
        val playableUri = File(liveAsset.localPath).takeIf { it.exists() }?.let { "file://${it.absolutePath}" }
            ?: liveAsset.remoteUrl
            ?: liveAsset.localPath
        MuseVideoPlayerDialog(
            videoUri = playableUri,
            promptSummary = liveAsset.prompt,
            onDismiss = { playingVideoAsset = null },
            storage = storage,
            isFavorite = liveAsset.favorite,
            onToggleFavorite = { scope.launch { manager.toggleFavorite(liveAsset.id) } },
            onDelete = { deleteConfirmAsset = liveAsset },
        )
    }

    deleteConfirmAsset?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.media_studio_delete_asset_cd),
            itemName = target.prompt.take(36).ifBlank { target.modelId },
            consequence = stringResource(R.string.media_studio_delete_confirm_message),
            onConfirm = {
                deleteConfirmAsset = null
                if (previewImageAsset?.id == target.id) previewImageAsset = null
                if (playingVideoAsset?.id == target.id) playingVideoAsset = null
                scope.launch { manager.deleteAsset(target.id) }
            },
            onDismiss = { deleteConfirmAsset = null },
        )
    }
}

@Composable
private fun GalleryAssetTile(asset: MediaGenAssetEntity, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    val thumbPath = if (asset.kind == MediaGenTaskEntity.KIND_VIDEO) asset.thumbnailPath else asset.localPath
    Surface(
        shape = MuseShapes.largeCard,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MuseShapes.largeCard)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!thumbPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(thumbPath),
                    contentDescription = stringResource(R.string.media_studio_preview_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (asset.kind == MediaGenTaskEntity.KIND_VIDEO) MuseIcons.video else MuseIcons.image,
                    contentDescription = stringResource(R.string.media_studio_preview_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(MuseIconSizes.iconLarge)
                        .align(Alignment.Center),
                )
            }

            if (asset.kind == MediaGenTaskEntity.KIND_VIDEO) {
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.touchTarget)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.play,
                        contentDescription = stringResource(R.string.chat_video_play_cd),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }

            MuseTactileButton(
                icon = MuseIcons.star,
                onClick = onToggleFavorite,
                contentDescription = stringResource(R.string.media_studio_favorite_cd),
                tint = if (asset.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MuseSpacing.xs)
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                        CircleShape,
                    ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
            ) {
                Text(
                    text = asset.prompt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
+@Composable
private fun MediaGalleryActiveTasks(tasks: List<MediaGenTaskEntity>, manager: MediaGenerationManager) {
    Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
        tasks.forEach { task ->
            Surface(
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    MuseSpinner(size = MuseIconSizes.iconMedium)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.media_studio_task_elapsed, task.elapsedSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MuseCapsuleButton(
                        text = stringResource(R.string.media_studio_task_cancel),
                        onClick = { manager.cancelTask(task.id) },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGalleryFailedTasks(
    tasks: List<MediaGenTaskEntity>,
    manager: MediaGenerationManager,
    scope: CoroutineScope,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
        tasks.forEach { task ->
            Surface(
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        task.errorMessage?.takeIf(String::isNotBlank)?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    MuseTactileButton(
                        icon = MuseIcons.refresh,
                        onClick = { scope.launch { manager.retryTask(task.id) } },
                        contentDescription = stringResource(R.string.media_studio_task_retry),
                        tint = MaterialTheme.colorScheme.primary,
                        size = MuseIconSizes.touchTarget,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = { scope.launch { manager.deleteTask(task.id) } },
                        contentDescription = stringResource(R.string.media_studio_task_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = MuseIconSizes.touchTarget,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGalleryEmptyState(onCreateImage: () -> Unit, onCreateVideo: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
        MuseEmptyState(
            icon = MuseIcons.image,
            title = stringResource(R.string.media_studio_gallery_empty_title),
            subtitle = stringResource(R.string.media_studio_gallery_empty_subtitle),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.media_studio_go_create_image),
                onClick = onCreateImage,
                variant = IosCapsuleButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.media_studio_go_create_video),
                onClick = onCreateVideo,
                variant = IosCapsuleButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaImageFullScreenViewer(
    assets: List<MediaGenAssetEntity>,
    imageUris: List<String>,
    initialIndex: Int,
    storage: MediaAssetStorage,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onToggleFavorite: (MediaGenAssetEntity) -> Unit,
    onRequestDelete: (MediaGenAssetEntity) -> Unit,
    onReuseParams: (StudioImageRequest) -> Unit,
    onSendToVideo: (localPath: String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    FullScreenMediaViewer(
        images = imageUris,
        initialIndex = initialIndex,
        onDismiss = onDismiss,
        onSaveImage = { uri ->
            val index = imageUris.indexOf(uri).coerceAtLeast(0)
            val current = assets.getOrNull(index) ?: return@FullScreenMediaViewer
            scope.launch {
                storage.saveToSystemGallery(File(current.localPath), isVideo = false)
                    .onSuccess { MuseToast.show(context.getString(R.string.media_studio_saved_to_gallery)) }
                    .onFailure { e ->
                        MuseToast.show(context.getString(R.string.media_studio_save_failed, e.message ?: ""))
                    }
            }
        },
        onShareImage = { uri ->
            val index = imageUris.indexOf(uri).coerceAtLeast(0)
            val current = assets.getOrNull(index) ?: return@FullScreenMediaViewer
            if (!storage.shareLocalFile(context, File(current.localPath), current.mimeType)) {
                MuseToast.show(context.getString(R.string.chat_share_failed_no_uri))
            }
        },
        headerExtra = { currentIndex ->
            val current = assets.getOrNull(currentIndex)
            if (current != null) {
                MuseTactileButton(
                    icon = MuseIcons.star,
                    onClick = { onToggleFavorite(current) },
                    contentDescription = stringResource(R.string.media_studio_favorite_cd),
                    tint = if (current.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                    size = MuseIconSizes.touchTarget,
                )
                MuseTactileButton(
                    icon = MuseIcons.trash,
                    onClick = { onRequestDelete(current) },
                    contentDescription = stringResource(R.string.media_studio_delete_asset_cd),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    size = MuseIconSizes.touchTarget,
                )
                Text(
                    text = current.modelId,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        bottomContent = { currentIndex ->
            val asset = assets.getOrNull(currentIndex)
            if (asset != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = MuseShapes.largeCard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MuseSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = asset.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                        ) {
                            MuseChip(
                                selected = false,
                                onClick = {
                                    clipboard.setText(AnnotatedString(asset.prompt))
                                    MuseToast.show(context.getString(R.string.media_studio_prompt_copied))
                                },
                                label = stringResource(R.string.media_studio_copy_prompt),
                                leadingIcon = {
                                    Icon(MuseIcons.copy, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                                },
                            )
                            MuseChip(
                                selected = false,
                                onClick = {
                                    val req = runCatching {
                                        AppJson.decodeFromString(StudioImageRequest.serializer(), asset.paramsJson)
                                    }.getOrElse {
                                        StudioImageRequest(
                                            prompt = asset.prompt,
                                            providerId = asset.providerId,
                                            modelId = asset.modelId,
                                        )
                                    }
                                    onReuseParams(req)
                                },
                                label = stringResource(R.string.media_studio_reuse_image_params),
                                leadingIcon = {
                                    Icon(MuseIcons.refresh, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                                },
                            )
                            MuseChip(
                                selected = false,
                                onClick = { onSendToVideo(asset.localPath) },
                                label = stringResource(R.string.media_studio_to_video),
                                leadingIcon = {
                                    Icon(MuseIcons.video, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}
private data class MediaGalleryGridState(
    val filter: GalleryFilter,
    val activeTasks: List<MediaGenTaskEntity>,
    val failedTasks: List<MediaGenTaskEntity>,
    val filteredAssets: List<MediaGenAssetEntity>,
    val manager: MediaGenerationManager,
    val scope: CoroutineScope,
)

private data class MediaGalleryGridActions(
    val onFilter: (GalleryFilter) -> Unit,
    val onGoCreateImage: () -> Unit,
    val onGoCreateVideo: () -> Unit,
    val onOpenAsset: (MediaGenAssetEntity) -> Unit,
    val onToggleFavorite: (MediaGenAssetEntity) -> Unit,
)

@Composable
private fun MediaGalleryGrid(
    state: MediaGalleryGridState,
    actions: MediaGalleryGridActions,
) {
    val filter = state.filter
    val activeTasks = state.activeTasks
    val failedTasks = state.failedTasks
    val filteredAssets = state.filteredAssets
    val manager = state.manager
    val scope = state.scope
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = MuseSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        item(key = "gallery_filters", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                val chips = listOf(
                    GalleryFilter.ALL to R.string.media_studio_filter_all,
                    GalleryFilter.IMAGES to R.string.media_studio_filter_images,
                    GalleryFilter.VIDEOS to R.string.media_studio_filter_videos,
                    GalleryFilter.FAVORITES to R.string.media_studio_filter_favorites,
                )
                chips.forEach { (kind, labelRes) ->
                    MuseChip(
                        selected = filter == kind,
                        onClick = { actions.onFilter(kind) },
                        label = stringResource(labelRes),
                    )
                }
            }
        }

        if (activeTasks.isNotEmpty()) {
            item(key = "gallery_active", span = { GridItemSpan(maxLineSpan) }) {
                MediaGalleryActiveTasks(activeTasks, manager)
            }
        }

        if (failedTasks.isNotEmpty()) {
            item(key = "gallery_failed", span = { GridItemSpan(maxLineSpan) }) {
                MediaGalleryFailedTasks(failedTasks, manager, scope)
            }
        }

        if (filteredAssets.isEmpty()) {
            item(key = "gallery_empty", span = { GridItemSpan(maxLineSpan) }) {
                MediaGalleryEmptyState(actions.onGoCreateImage, actions.onGoCreateVideo)
            }
        } else {
            items(filteredAssets, key = { it.id }) { asset ->
                GalleryAssetTile(
                    asset = asset,
                    onClick = { actions.onOpenAsset(asset) },
                    onToggleFavorite = { actions.onToggleFavorite(asset) },
                )
            }
        }

    }

}
