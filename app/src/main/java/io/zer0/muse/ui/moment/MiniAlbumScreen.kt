package io.zer0.muse.ui.moment

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.`import`.MiniAlbumImage
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * AI 相册:展示 AI 生成图片,保留生成日期,支持刷新、全屏预览、
 * 收藏和从小手机相册隐藏。
 *
 * ST-09: 加载态统一 [MuseLoadingState],加载失败走 [MuseErrorStateBox] + 重试(onRefresh)。
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun MiniAlbumScreen(
    images: List<MiniAlbumImage>,
    onBack: () -> Unit,
    isLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    /** ST-09: 加载失败原因(非空且无图时显示错误态 + 重试)。 */
    errorMessage: String? = null,
    hiddenImageIds: Set<String> = emptySet(),
    favoriteImageIds: Set<String> = emptySet(),
    onToggleFavorite: (String) -> Unit = {},
    onHideImage: (String) -> Unit = {},
    onUnhideImage: (String) -> Unit = {},
    /** v2.6.9: 从本地相册导入图片到小手机相册。 */
    onImportImages: (List<android.net.Uri>) -> Unit = {},
    /**
     * v2.6.9: 删除一张**导入的**图片（真删文件）。
     *
     * 为什么要与“隐藏”分开：
     *  - 导入的图是用户自己放的，文件就在应用私有目录，可以真删
     *  - 派生的图（朋友圈/消息配图）本体属于消息，删了会把消息里的图一起弄没，只能隐藏
     * 两种行为不一样，所以 UI 上分开给，且在提示文案里写清楚。
     */
    onDeleteImported: (String) -> Unit = {},
    /** v2.6.9: 判断某张图是否为导入图片（可删）。 */
    isImportedImage: (String) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf(-1) }
    var selectedImage by remember { mutableStateOf<MiniAlbumImage?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    // v2.6.9: 批量选本地图片导入
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) onImportImages(uris)
    }
    val visibleImages = remember(images, hiddenImageIds, showHidden) {
        if (showHidden) images else images.filterNot { it.id in hiddenImageIds }
    }
    val viewerImages = remember(visibleImages) { visibleImages.map { it.uri } }
    val dateFormat = remember {
        java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
    }
    val yearFormat = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    }
    val today = java.time.LocalDate.now()

    fun formatAlbumDate(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val itemDate = java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return if (itemDate.year == today.year) {
            dateFormat.format(java.util.Date(timestamp))
        } else {
            yearFormat.format(java.util.Date(timestamp))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTactileButton(
                icon = MuseIcons.arrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.mini_album_back_cd),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.mini_album_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // v2.6.9: 从本地导入图片
            MuseTactileButton(
                icon = MuseIcons.plus,
                onClick = {
                    importLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                contentDescription = stringResource(R.string.mini_album_import_cd),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.mini_album_count, visibleImages.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Box(
                modifier = Modifier.size(MuseIconSizes.touchTarget),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    // ST-09: 内联刷新指示器(位于 48dp 触摸区内,不能用 MuseLoadingState 的全宽布局)
                    MuseSpinner(
                        size = MuseIconSizes.iconSmallTiny,
                        strokeWidth = MuseIconSizes.progressStroke,
                    )
                } else {
                    MuseTactileButton(
                        icon = MuseIcons.refresh,
                        onClick = onRefresh,
                        contentDescription = stringResource(R.string.mini_album_refresh_cd),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            MuseTactileButton(
                icon = if (showHidden) {
                    MuseIcons.eyeOff
                } else {
                    MuseIcons.eye
                },
                onClick = { showHidden = !showHidden },
                contentDescription = if (showHidden) {
                    stringResource(R.string.mini_album_visibility_cd_hidden)
                } else {
                    stringResource(R.string.mini_album_visibility_cd_shown)
                },
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        if (isLoading && images.isEmpty()) {
            // ST-09: 首次加载统一 MuseLoadingState(此前直接落到空态,无加载反馈)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MuseLoadingState()
            }
        } else if (errorMessage != null && images.isEmpty()) {
            // ST-09: 加载失败 → 可读原因 + 重试
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MuseErrorStateBox(
                    message = errorMessage,
                    onRetry = onRefresh,
                )
            }
        } else if (visibleImages.isEmpty()) {
            // ST-03: 空态统一 MuseEmptyState(文案与 MEM-05 的 i18n 迁移保持一致,暂沿用现有中文)
            val emptyTitle = if (images.isEmpty()) {
                stringResource(R.string.mini_album_empty_no_images)
            } else {
                stringResource(R.string.mini_album_empty_all_hidden)
            }
            val emptySubtitle = if (images.isEmpty()) {
                stringResource(R.string.mini_album_empty_no_images_hint)
            } else {
                stringResource(R.string.mini_album_empty_all_hidden_hint)
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MuseEmptyState(title = emptyTitle, subtitle = emptySubtitle)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(MusePaddings.screen),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
            ) {
                items(visibleImages, key = { it.uri }) { image ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(MusePaddings.tinyGap))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .pointerInput(image.id) {
                                detectTapGestures(
                                    onTap = {
                                        viewerIndex = visibleImages.indexOfFirst { it.uri == image.uri }
                                    },
                                    onLongPress = { selectedImage = image },
                                )
                            },
                    ) {
                        io.zer0.muse.ui.SmartImage(
                            model = image.uri,
                            contentDescription = stringResource(R.string.mini_album_image_cd),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (image.createdAt > 0) {
                            Text(
                                text = formatAlbumDate(image.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = MusePaddings.tightGap, vertical = MusePaddings.tinyGap),
                            )
                        }
                        if (image.id in favoriteImageIds) {
                            Icon(
                                imageVector = MuseIcons.star,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (viewerIndex >= 0 && viewerIndex < viewerImages.size) {
        io.zer0.muse.ui.common.media.FullScreenMediaViewer(
            images = viewerImages,
            initialIndex = viewerIndex,
            onDismiss = { viewerIndex = -1 },
        )
    }

    selectedImage?.let { image ->
        MuseDialog(
            onDismissRequest = { selectedImage = null },
            title = stringResource(R.string.mini_album_actions_title),
            content = {
                Column {
                    // v2.6.9: 导入的图给真删，派生的图只能隐藏——两者语义不同
                    if (isImportedImage(image.id)) {
                        Text(
                            text = stringResource(R.string.mini_album_delete_imported_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.mini_album_delete_imported_action),
                            onClick = {
                                onDeleteImported(image.id)
                                selectedImage = null
                            },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    } else {
                        Text(
                            text = if (image.id in hiddenImageIds) {
                                stringResource(R.string.mini_album_restore_hint)
                            } else {
                                stringResource(R.string.mini_album_hide_hint)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MuseCapsuleButton(
                            text = if (image.id in hiddenImageIds) {
                                stringResource(R.string.mini_album_restore_action)
                            } else {
                                stringResource(R.string.mini_album_hide_action)
                            },
                            onClick = {
                                if (image.id in hiddenImageIds) {
                                    onUnhideImage(image.id)
                                } else {
                                    onHideImage(image.id)
                                }
                                selectedImage = null
                            },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    }
                }
            },
            confirmText = if (image.id in favoriteImageIds) {
                stringResource(R.string.mini_album_unfavorite_action)
            } else {
                stringResource(R.string.mini_album_favorite_action)
            },
            onConfirm = {
                onToggleFavorite(image.id)
                selectedImage = null
            },
            dismissText = stringResource(R.string.mini_album_cancel),
            onDismiss = { selectedImage = null },
        )
    }
}
