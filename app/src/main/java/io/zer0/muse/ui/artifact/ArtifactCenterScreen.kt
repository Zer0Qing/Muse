package io.zer0.muse.ui.artifact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.data.media.MediaAssetStorage
import io.zer0.muse.data.media.MediaGenAssetEntity
import io.zer0.muse.data.media.MediaGenTaskEntity
import io.zer0.muse.data.media.MediaGenerationManager
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseSearchField
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.media.MuseVideoPlayerDialog
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.largeCard
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

/** 产物库顶部筛选枚举。 */
enum class LibraryFilter {
    ALL,
    WEB,
    CHART,
    CODE,
    DOC,
    MEDIA,
}

/** 产物库统一条目（融合 `artifacts` 表产物与只读桥接的创作工坊媒体作品）。 */
sealed interface LibraryItem {
    val key: String
    val createdAt: Long

    data class Artifact(
        val entity: ArtifactEntity,
        val sessionTitle: String?,
    ) : LibraryItem {
        override val key: String get() = "art:${entity.id}"
        override val createdAt: Long get() = entity.createdAt
    }

    data class Media(
        val asset: MediaGenAssetEntity,
    ) : LibraryItem {
        override val key: String get() = "media:${asset.id}"
        override val createdAt: Long get() = asset.createdAt
    }
}

/** 纯函数过滤与时间倒序归并（供 UI 与单测共用）。 */
internal fun filterAndMergeLibraryItems(
    artifacts: List<ArtifactEntity>,
    mediaAssets: List<MediaGenAssetEntity>,
    sessionTitlesById: Map<String, String>,
    filter: LibraryFilter,
    query: String,
): List<LibraryItem> {
    val q = query.trim()

    val artifactItems = artifacts
        .asSequence()
        .filter { matchesArtifactFilter(it, filter) }
        .map { entity ->
            val sessionTitle = sessionTitlesById[entity.sessionId]?.takeIf { it.isNotBlank() }
            LibraryItem.Artifact(entity = entity, sessionTitle = sessionTitle)
        }
        .filter { item ->
            q.isBlank() ||
                item.entity.title.contains(q, ignoreCase = true) ||
                item.entity.content.contains(q, ignoreCase = true) ||
                item.entity.type.contains(q, ignoreCase = true) ||
                item.entity.language?.contains(q, ignoreCase = true) == true ||
                item.sessionTitle?.contains(q, ignoreCase = true) == true
        }

    val mediaItems = if (filter == LibraryFilter.ALL || filter == LibraryFilter.MEDIA) {
        mediaAssets
            .asSequence()
            .filter { asset ->
                q.isBlank() ||
                    asset.prompt.contains(q, ignoreCase = true) ||
                    asset.modelId.contains(q, ignoreCase = true)
            }
            .map { LibraryItem.Media(it) }
    } else {
        emptySequence()
    }

    return (artifactItems + mediaItems)
        .sortedByDescending { it.createdAt }
        .toList()
}

private fun matchesArtifactFilter(entity: ArtifactEntity, filter: LibraryFilter): Boolean {
    if (filter == LibraryFilter.ALL) return true
    val mode = artifactRenderMode(entity.type, entity.language)
    val richLang = richPreviewLanguage(entity.type, entity.language)
    return when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.WEB -> mode == ArtifactRenderMode.RichWeb && (richLang == "html" || richLang == "svg")
        LibraryFilter.CHART -> mode == ArtifactRenderMode.RichWeb && (richLang == "chart" || richLang == "mermaid")
        LibraryFilter.CODE -> mode == ArtifactRenderMode.Code
        LibraryFilter.DOC -> mode == ArtifactRenderMode.Markdown || mode == ArtifactRenderMode.PlainText
        LibraryFilter.MEDIA -> mode == ArtifactRenderMode.Image
    }
}

/**
 * 产物库（原产物中心）—— 方案 A 全站 AI 产出聚合入口：
 *  - 融合对话产物（网页/图表/代码/文档/图片）与创作工坊媒体作品；
 *  - 支持关键词搜索、六类筛选条、来源会话署名与一键跳回原对话、重命名与确认删除；
 *  - 手机单列全宽信息卡 / 平板双列自适应网格。
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun ArtifactCenterScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    onOpenMediaStudio: () -> Unit = {},
    repository: ArtifactRepository = koinInject(),
    mediaManager: MediaGenerationManager = koinInject(),
    mediaStorage: MediaAssetStorage = koinInject(),
    sessionRepository: SessionRepository = koinInject(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val widthClass = rememberWindowWidthClass()

    val artifacts by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val mediaAssets by mediaManager.observeAssets().collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by sessionRepository.observeAllSessions().collectAsStateWithLifecycle(initialValue = emptyList())
    val sessionTitlesById = remember(sessions) {
        sessions.associate { it.id to it.title }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var openedArtifact by remember { mutableStateOf<ArtifactEntity?>(null) }
    var renameTarget by remember { mutableStateOf<ArtifactEntity?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteArtifactTarget by remember { mutableStateOf<ArtifactEntity?>(null) }
    var deleteMediaTarget by remember { mutableStateOf<MediaGenAssetEntity?>(null) }
    var previewMediaImage by remember { mutableStateOf<MediaGenAssetEntity?>(null) }
    var playingMediaVideo by remember { mutableStateOf<MediaGenAssetEntity?>(null) }

    val items = remember(artifacts, mediaAssets, sessionTitlesById, selectedFilter, searchQuery) {
        filterAndMergeLibraryItems(
            artifacts = artifacts,
            mediaAssets = mediaAssets,
            sessionTitlesById = sessionTitlesById,
            filter = selectedFilter,
            query = searchQuery,
        )
    }

    val hasAnyLibraryData = artifacts.isNotEmpty() || mediaAssets.isNotEmpty()

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.artifact_center_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
        ) {
            if (hasAnyLibraryData) {
                MuseSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.artifact_center_search_placeholder),
                    modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
                )
                LibraryFilterRow(
                    selected = selectedFilter,
                    onSelect = { selectedFilter = it },
                )
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseEmptyState(
                        icon = MuseIcons.fileText,
                        title = stringResource(
                            if (hasAnyLibraryData) R.string.artifact_center_filter_empty else R.string.artifact_center_empty,
                        ),
                        subtitle = if (hasAnyLibraryData) null else stringResource(R.string.artifact_center_empty_hint),
                    )
                }
            } else if (widthClass == WindowWidthClass.Expanded) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = MusePaddings.screen,
                        end = MusePaddings.screen,
                        top = MuseSpacing.sm,
                        bottom = MuseSpacing.xxl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    items(items, key = { it.key }) { item ->
                        LibraryItemRow(
                            item = item,
                            onOpenArtifact = { openedArtifact = it },
                            onOpenSession = onOpenSession,
                            onRequestRename = { target ->
                                renameDraft = target.title
                                renameTarget = target
                            },
                            onRequestDeleteArtifact = { deleteArtifactTarget = it },
                            onOpenMediaImage = { previewMediaImage = it },
                            onOpenMediaVideo = { playingMediaVideo = it },
                            onToggleMediaFavorite = { asset -> scope.launch { mediaManager.toggleFavorite(asset.id) } },
                            onRequestDeleteMedia = { deleteMediaTarget = it },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = MusePaddings.screen,
                        end = MusePaddings.screen,
                        top = MuseSpacing.sm,
                        bottom = MuseSpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    items(items, key = { it.key }) { item ->
                        LibraryItemRow(
                            item = item,
                            onOpenArtifact = { openedArtifact = it },
                            onOpenSession = onOpenSession,
                            onRequestRename = { target ->
                                renameDraft = target.title
                                renameTarget = target
                            },
                            onRequestDeleteArtifact = { deleteArtifactTarget = it },
                            onOpenMediaImage = { previewMediaImage = it },
                            onOpenMediaVideo = { playingMediaVideo = it },
                            onToggleMediaFavorite = { asset -> scope.launch { mediaManager.toggleFavorite(asset.id) } },
                            onRequestDeleteMedia = { deleteMediaTarget = it },
                        )
                    }
                }
            }
        }
    }

    ArtifactOpenHost(
        artifact = openedArtifact,
        onDismiss = { openedArtifact = null },
        onOpenSession = { sid ->
            openedArtifact = null
            onOpenSession(sid)
        },
        onDelete = openedArtifact?.let { target ->
            {
                openedArtifact = null
                deleteArtifactTarget = target
            }
        },
        onRename = openedArtifact?.let { target ->
            {
                openedArtifact = null
                renameDraft = target.title
                renameTarget = target
            }
        },
    )

    val libraryImages = remember(items) {
        items.filterIsInstance<LibraryItem.Media>()
            .map { it.asset }
            .filter { it.kind == MediaGenTaskEntity.KIND_IMAGE }
    }
    previewMediaImage?.let { initialAsset ->
        val imagesList = if (libraryImages.isEmpty()) listOf(initialAsset) else libraryImages
        val startIndex = imagesList.indexOfFirst { it.id == initialAsset.id }.coerceAtLeast(0)
        val uris = remember(imagesList) {
            imagesList.map { asset ->
                File(asset.localPath).takeIf { it.exists() }?.let { "file://${it.absolutePath}" }
                    ?: asset.remoteUrl
                    ?: asset.localPath
            }
        }
        FullScreenMediaViewer(
            images = uris,
            initialIndex = startIndex,
            onDismiss = { previewMediaImage = null },
            onSaveImage = { uri ->
                val idx = uris.indexOf(uri).coerceAtLeast(0)
                val target = imagesList.getOrNull(idx) ?: return@FullScreenMediaViewer
                scope.launch {
                    mediaStorage.saveToSystemGallery(File(target.localPath), isVideo = false)
                        .onSuccess { MuseToast.show(context.getString(R.string.media_studio_saved_to_gallery)) }
                        .onFailure { e ->
                            MuseToast.show(context.getString(R.string.media_studio_save_failed, e.message ?: ""))
                        }
                }
            },
            onShareImage = { uri ->
                val idx = uris.indexOf(uri).coerceAtLeast(0)
                val target = imagesList.getOrNull(idx) ?: return@FullScreenMediaViewer
                if (!mediaStorage.shareLocalFile(context, File(target.localPath), target.mimeType)) {
                    MuseToast.show(context.getString(R.string.chat_share_failed_no_uri))
                }
            },
            headerExtra = { idx ->
                val current = imagesList.getOrNull(idx)
                if (current != null) {
                    MuseTactileButton(
                        icon = MuseIcons.star,
                        onClick = { scope.launch { mediaManager.toggleFavorite(current.id) } },
                        contentDescription = stringResource(R.string.media_studio_favorite_cd),
                        tint = if (current.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                        size = MuseIconSizes.touchTarget,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.trash,
                        onClick = { deleteMediaTarget = current },
                        contentDescription = stringResource(R.string.media_studio_delete_asset_cd),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        size = MuseIconSizes.touchTarget,
                    )
                }
            },
            bottomContent = { idx ->
                val asset = imagesList.getOrNull(idx)
                if (asset != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = MuseShapes.largeCard,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(MuseSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        ) {
                            Text(
                                text = asset.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                                MuseChip(
                                    selected = false,
                                    onClick = {
                                        clipboard.setText(AnnotatedString(asset.prompt))
                                        MuseToast.show(context.getString(R.string.media_studio_prompt_copied))
                                    },
                                    label = stringResource(R.string.media_studio_copy_prompt),
                                )
                                MuseChip(
                                    selected = false,
                                    onClick = {
                                        previewMediaImage = null
                                        onOpenMediaStudio()
                                    },
                                    label = stringResource(R.string.artifact_open_media_studio),
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    playingMediaVideo?.let { asset ->
        val live = mediaAssets.firstOrNull { it.id == asset.id } ?: asset
        val playableUri = File(live.localPath).takeIf { it.exists() }?.let { "file://${it.absolutePath}" }
            ?: live.remoteUrl
            ?: live.localPath
        MuseVideoPlayerDialog(
            videoUri = playableUri,
            promptSummary = live.prompt,
            onDismiss = { playingMediaVideo = null },
            storage = mediaStorage,
            isFavorite = live.favorite,
            onToggleFavorite = { scope.launch { mediaManager.toggleFavorite(live.id) } },
            onDelete = { deleteMediaTarget = live },
        )
    }

    renameTarget?.let { target ->
        MuseFormDialog(
            onDismissRequest = { renameTarget = null },
            title = stringResource(R.string.artifact_rename),
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                val trimmed = renameDraft.trim()
                if (trimmed.isNotEmpty()) {
                    scope.launch {
                        repository.rename(target.id, trimmed)
                        MuseToast.show(context.getString(R.string.artifact_renamed))
                    }
                }
                renameTarget = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { renameTarget = null },
        ) {
            MuseTextField(
                value = renameDraft,
                onValueChange = { renameDraft = it },
                placeholder = { Text(stringResource(R.string.artifact_rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    deleteArtifactTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete),
            itemName = target.title.ifBlank { stringResource(R.string.artifact_untitled) },
            onConfirm = {
                deleteArtifactTarget = null
                scope.launch {
                    repository.delete(target.id)
                    MuseToast.show(context.getString(R.string.artifact_deleted))
                }
            },
            onDismiss = { deleteArtifactTarget = null },
        )
    }

    deleteMediaTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.media_studio_delete_asset_cd),
            itemName = target.prompt.take(36).ifBlank { target.modelId },
            consequence = stringResource(R.string.media_studio_delete_confirm_message),
            onConfirm = {
                deleteMediaTarget = null
                if (previewMediaImage?.id == target.id) previewMediaImage = null
                if (playingMediaVideo?.id == target.id) playingMediaVideo = null
                scope.launch {
                    mediaManager.deleteAsset(target.id)
                    MuseToast.show(context.getString(R.string.artifact_deleted))
                }
            },
            onDismiss = { deleteMediaTarget = null },
        )
    }
}

@Composable
private fun LibraryFilterRow(selected: LibraryFilter, onSelect: (LibraryFilter) -> Unit) {
    val filters = listOf(
        LibraryFilter.ALL to R.string.artifact_filter_all,
        LibraryFilter.WEB to R.string.artifact_filter_web,
        LibraryFilter.CHART to R.string.artifact_filter_chart,
        LibraryFilter.CODE to R.string.artifact_filter_code,
        LibraryFilter.DOC to R.string.artifact_filter_doc,
        LibraryFilter.MEDIA to R.string.artifact_filter_media,
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
    ) {
        items(filters, key = { it.first.name }) { (filter, labelRes) ->
            MuseChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = stringResource(labelRes),
            )
        }
    }
}

@Composable
private fun LibraryItemRow(
    item: LibraryItem,
    onOpenArtifact: (ArtifactEntity) -> Unit,
    onOpenSession: (String) -> Unit,
    onRequestRename: (ArtifactEntity) -> Unit,
    onRequestDeleteArtifact: (ArtifactEntity) -> Unit,
    onOpenMediaImage: (MediaGenAssetEntity) -> Unit,
    onOpenMediaVideo: (MediaGenAssetEntity) -> Unit,
    onToggleMediaFavorite: (MediaGenAssetEntity) -> Unit,
    onRequestDeleteMedia: (MediaGenAssetEntity) -> Unit,
) {
    when (item) {
        is LibraryItem.Artifact -> {
            var menuExpanded by remember { mutableStateOf(false) }
            val entity = item.entity
            val timeStr = remember(entity.createdAt) {
                android.text.format.DateUtils.getRelativeTimeSpanString(entity.createdAt).toString()
            }
            val metaExtra = listOfNotNull(item.sessionTitle, timeStr).joinToString(" · ")
            ArtifactCard(
                artifact = entity,
                onClick = { onOpenArtifact(entity) },
                metaExtra = metaExtra,
                trailingAction = {
                    Box {
                        MuseTactileButton(
                            icon = MuseIcons.moreVertical,
                            onClick = { menuExpanded = true },
                            contentDescription = stringResource(R.string.settings_mcp_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            compact = true,
                        )
                        MuseAnchoredMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            alignEnd = true,
                        ) {
                            if (entity.sessionId.isNotBlank()) {
                                MuseListItem(
                                    onClick = {
                                        menuExpanded = false
                                        onOpenSession(entity.sessionId)
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = MuseIcons.messages,
                                            contentDescription = null,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                    },
                                    headlineContent = { Text(stringResource(R.string.artifact_jump_to_chat)) },
                                )
                            }
                            MuseListItem(
                                onClick = {
                                    menuExpanded = false
                                    onRequestRename(entity)
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = MuseIcons.edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                },
                                headlineContent = { Text(stringResource(R.string.artifact_rename)) },
                            )
                            MuseListItem(
                                onClick = {
                                    menuExpanded = false
                                    onRequestDeleteArtifact(entity)
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = MuseIcons.trash,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.common_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                },
            )
        }

        is LibraryItem.Media -> {
            val asset = item.asset
            val isVideo = asset.kind == MediaGenTaskEntity.KIND_VIDEO
            val thumbPath = if (isVideo) asset.thumbnailPath else asset.localPath
            val timeStr = remember(asset.createdAt) {
                android.text.format.DateUtils.getRelativeTimeSpanString(asset.createdAt).toString()
            }
            MuseSurface(
                onClick = { if (isVideo) onOpenMediaVideo(asset) else onOpenMediaImage(asset) },
                shape = MuseShapes.largeCard,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = MuseElevation.card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Icon(
                            imageVector = if (isVideo) MuseIcons.video else MuseIcons.image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = asset.prompt.ifBlank { asset.modelId },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(if (isVideo) R.string.artifact_type_video else R.string.artifact_type_image),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        MuseTactileButton(
                            icon = MuseIcons.star,
                            onClick = { onToggleMediaFavorite(asset) },
                            contentDescription = stringResource(R.string.media_studio_favorite_cd),
                            tint = if (asset.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            compact = true,
                        )
                        MuseTactileButton(
                            icon = MuseIcons.trash,
                            onClick = { onRequestDeleteMedia(asset) },
                            contentDescription = stringResource(R.string.media_studio_delete_asset_cd),
                            tint = MaterialTheme.colorScheme.outline,
                            compact = true,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(MuseShapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!thumbPath.isNullOrBlank()) {
                            AsyncImage(
                                model = File(thumbPath),
                                contentDescription = asset.prompt,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                imageVector = if (isVideo) MuseIcons.video else MuseIcons.image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(MuseIconSizes.iconLarge),
                            )
                        }
                        if (isVideo) {
                            Box(
                                modifier = Modifier
                                    .size(MuseIconSizes.touchTarget)
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
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.artifact_from_media_studio) + " · " + asset.modelId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
