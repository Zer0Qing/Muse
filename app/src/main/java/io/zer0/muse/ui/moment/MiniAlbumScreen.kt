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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.`import`.MiniAlbumImage
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MusePaddings

/**
 * AI 相册:展示 AI 生成图片,保留生成日期,支持刷新、全屏预览、
 * 收藏和从小手机相册隐藏。
 */
@Composable
fun MiniAlbumScreen(
    images: List<MiniAlbumImage>,
    onBack: () -> Unit,
    isLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    hiddenImageIds: Set<String> = emptySet(),
    favoriteImageIds: Set<String> = emptySet(),
    onToggleFavorite: (String) -> Unit = {},
    onHideImage: (String) -> Unit = {},
    onUnhideImage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf(-1) }
    var selectedImage by remember { mutableStateOf<MiniAlbumImage?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    val visibleImages = remember(images, hiddenImageIds, showHidden) {
        if (showHidden) images else images.filterNot { it.id in hiddenImageIds }
    }
    val viewerImages = remember(visibleImages) { visibleImages.map { it.uri } }
    val dateFormat = remember {
        java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "AI 相册",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${visibleImages.size} 张",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新相册",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = { showHidden = !showHidden }) {
                Icon(
                    imageVector = if (showHidden) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = if (showHidden) "隐藏已隐藏图片" else "显示已隐藏图片",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (visibleImages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (images.isEmpty()) "还没有 AI 生成的图片" else "相册中没有可显示的图片",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (images.isEmpty()) {
                            "配置生图模型后,AI 生成的图片会出现在这里"
                        } else {
                            "可以在图片操作中取消隐藏"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(MusePaddings.screen),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(visibleImages, key = { it.uri }) { image ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
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
                            contentDescription = "AI 生成图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (image.createdAt > 0) {
                            Text(
                                text = dateFormat.format(java.util.Date(image.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                        if (image.id in favoriteImageIds) {
                            Icon(
                                imageVector = Icons.Filled.Star,
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
            title = "图片操作",
            content = {
                Column {
                    Text(
                        text = if (image.id in hiddenImageIds) {
                            "恢复后这张图片会重新出现在小手机相册。"
                        } else {
                            "这张图片只会从小手机相册隐藏,不会删除聊天记录。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            if (image.id in hiddenImageIds) {
                                onUnhideImage(image.id)
                            } else {
                                onHideImage(image.id)
                            }
                            selectedImage = null
                        },
                    ) {
                        Text(if (image.id in hiddenImageIds) "恢复显示" else "从相册隐藏")
                    }
                }
            },
            confirmText = if (image.id in favoriteImageIds) "取消收藏" else "收藏",
            onConfirm = {
                onToggleFavorite(image.id)
                selectedImage = null
            },
            dismissText = "取消",
            onDismiss = { selectedImage = null },
        )
    }
}
