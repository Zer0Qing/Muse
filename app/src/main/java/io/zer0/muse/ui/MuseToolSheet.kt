package io.zer0.muse.ui

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** B7-07: 加号工具面板的数据驱动条目。 */
internal data class ToolEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val isActive: Boolean = false,
    val showArrow: Boolean = true,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

/**
 * B7-07: 输入栏加号工具面板。
 *
 * 由 [MuseToolSheet] 统一渲染媒体快捷入口 + 数据驱动的工具列表,
 * 新增工具只需向 [entries] 增加一条 [ToolEntry]。
 */
@Composable
internal fun MuseToolSheet(
    context: Context,
    hapticFeedback: HapticFeedback,
    hasGalleryPermission: Boolean,
    galleryPermission: String,
    onRequestGalleryPermission: () -> Unit,
    onPickImage: (Boolean) -> Unit,
    onPickGalleryImage: (Uri) -> Unit,
    entries: List<ToolEntry>,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        maxHeightFraction = 0.55f,
    ) {
        Text(
            text = stringResource(R.string.chat_tools_pick_content),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(MusePaddings.screen))

        var recentImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
        LaunchedEffect(hasGalleryPermission) {
            if (hasGalleryPermission) {
                recentImages = withContext(Dispatchers.IO) {
                    queryRecentGalleryImages(context, 10)
                }
            }
        }

        // 媒体快捷入口:iOS 风格横向圆角卡片 + 右侧最近相册
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
                ToolMediaCard(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(R.string.chat_tool_camera),
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        onPickImage(true)
                    },
                )
                ToolMediaCard(
                    icon = Icons.Default.Photo,
                    label = stringResource(R.string.chat_tool_photo),
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        onPickImage(false)
                    },
                )
            }

            if (recentImages.isNotEmpty() || !hasGalleryPermission) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = MusePaddings.tightGap)
                        .width(MusePaddings.dividerWidth)
                        .height(MuseIconSizes.iconEmpty)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RectangleShape,
                        ),
                )
            }

            if (hasGalleryPermission) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    recentImages.forEach { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = stringResource(R.string.chat_gallery_image_cd),
                            modifier = Modifier
                                .size(MuseIconSizes.iconEmpty)
                                .clip(MuseShapes.medium)
                                .clickable {
                                    MuseHaptics.light(hapticFeedback)
                                    onPickGalleryImage(uri)
                                },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .height(MuseIconSizes.iconEmpty)
                        .clip(MuseShapes.medium)
                        .clickable(onClick = onRequestGalleryPermission),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Photo,
                            contentDescription = stringResource(R.string.chat_gallery_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.chat_authorize_gallery),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(MusePaddings.largeGap))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = MusePaddings.maxToolSheetListHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.padding(bottom = MusePaddings.emptyStateGap)) {
                entries.forEachIndexed { index, entry ->
                    ToolListRow(
                        icon = entry.icon,
                        title = entry.title,
                        subtitle = entry.subtitle,
                        isActive = entry.isActive,
                        showArrow = entry.showArrow,
                        onClick = entry.onClick,
                        onLongClick = entry.onLongClick,
                    )
                    if (index != entries.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = MusePaddings.dividerThickness,
                        )
                    }
                }
            }
        }
    }
}

/** B7-07: 工具菜单中的媒体快捷卡片。 */
@Composable
private fun ToolMediaCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        modifier = Modifier
            .size(MusePaddings.previewThumb)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(MuseIconSizes.iconLarge),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MusePaddings.auxGap))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** B7-07: 工具菜单中的列表行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolListRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    isActive: Boolean = false,
    showArrow: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(vertical = MusePaddings.listRowVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.screen),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(MuseIconSizes.icon),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(MusePaddings.tinyGap))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (isActive) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        } else if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** B7-07: 查询系统相册最近图片。 */
private fun queryRecentGalleryImages(context: Context, maxCount: Int): List<Uri> {
    return runCatching {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var count = 0
            while (cursor.moveToNext() && count < maxCount) {
                val id = cursor.getLong(idColumn)
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                count++
            }
        }
        uris
    }.onFailure { e ->
        Logger.w("MuseToolSheet", "queryRecentGalleryImages 查询失败", e)
    }.getOrDefault(emptyList())
}
