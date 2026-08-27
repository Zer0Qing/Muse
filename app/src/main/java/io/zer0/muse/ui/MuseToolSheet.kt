package io.zer0.muse.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import java.io.File
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
 * v1.0.72: 输入栏加号工具面板(Telegram 风格重写)。
 *
 * Telegram 式布局(从下往上):
 *  - 顶部媒体区:第一格 = 相机实时取景预览(点击进入系统相机拍照),
 *    后面跟随相册最近图片(横向缩略图)。
 *  - 功能 tab 行:相册 / 文件 / 文章(知识库) / 技能 / 委托 / 绘图(横向图标+文字)。
 *  - 工具列表:保留原有数据驱动 entries(联网搜索/深度思考/重启上下文等)。
 *
 * 相机权限:预览需要 CAMERA 权限;未授权时第一格显示相机图标,点击请求权限。
 * 拍照:点击预览格 → TakePicture contract 调系统相机,拍完图片加入待发送。
 */
@Composable
internal fun MuseToolSheet(
    context: Context,
    hapticFeedback: HapticFeedback,
    onPickImage: (Boolean) -> Unit,
    onPickGalleryImage: (Uri) -> Unit,
    entries: List<ToolEntry>,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismiss,
        // 加号面板内容铺到屏幕边缘，避免横向空间不足导致文字被压缩。
        maxHeightFraction = 0.82f,
        horizontalPadding = 0.dp,
        // 在系统导航栏/手势区 inset 之外再留出一点呼吸空间。
        bottomContentSpacing = MusePaddings.itemGap,
    ) {
        Text(
            text = stringResource(R.string.chat_tools_pick_content),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(MusePaddings.contentGap))

        // ── 第一行:拍照预览 + 最近相册图片 ──
        val cameraPermission = android.Manifest.permission.CAMERA
        val hasCameraPermission = remember {
            ContextCompat.checkSelfPermission(context, cameraPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        var cameraGranted by remember { mutableStateOf(hasCameraPermission) }
        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> cameraGranted = granted }

        val galleryPermission = remember {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
        var galleryGranted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, galleryPermission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        }
        val galleryPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> galleryGranted = granted }
        var recentImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var selectedGalleryImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
        LaunchedEffect(galleryGranted) {
            recentImages = if (galleryGranted) {
                withContext(Dispatchers.IO) {
                    queryRecentGalleryImages(context, maxCount = 12)
                }
            } else {
                emptyList()
            }
            selectedGalleryImages = selectedGalleryImages.filter { it in recentImages }
        }

        // 拍照:系统相机 intent(TakePicture 不需要 CAMERA 权限,FileProvider 保存)
        var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
        val takePictureLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val uri = pendingCameraUri
            if (success && uri != null) {
                onPickGalleryImage(uri)
            }
            pendingCameraUri = null
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TelegramMediaHeight)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            if (cameraGranted) {
                Box(
                    modifier = Modifier
                        .size(TelegramMediaHeight)
                        .clip(MuseShapes.extraLarge),
                ) {
                    CameraLivePreviewBox(
                        modifier = Modifier.fillMaxSize(),
                        onTap = {
                            MuseHaptics.light(hapticFeedback)
                            val file = File.createTempFile("muse_capture_", ".jpg", context.cacheDir)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            pendingCameraUri = uri
                            takePictureLauncher.launch(uri)
                        },
                    )
                }
            } else {
                ToolMediaCard(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(R.string.chat_tool_camera),
                    modifier = Modifier.size(TelegramMediaHeight),
                    onClick = { cameraPermissionLauncher.launch(cameraPermission) },
                )
            }

            if (galleryGranted && recentImages.isNotEmpty()) {
                recentImages.forEach { uri ->
                    GalleryThumbnail(
                        uri = uri,
                        selectionIndex = selectedGalleryImages.indexOf(uri),
                        onClick = {
                            selectedGalleryImages = if (uri in selectedGalleryImages) {
                                selectedGalleryImages - uri
                            } else if (selectedGalleryImages.size < MAX_GALLERY_SELECTION) {
                                selectedGalleryImages + uri
                            } else {
                                selectedGalleryImages
                            }
                        },
                    )
                }
            } else if (!galleryGranted) {
                ToolMediaCard(
                    icon = Icons.Default.Photo,
                    label = stringResource(R.string.chat_authorize_gallery),
                    modifier = Modifier.size(TelegramMediaHeight),
                    onClick = { galleryPermissionLauncher.launch(galleryPermission) },
                )
            }

            // 保留完整系统相册入口,没有最近图片时也能直接选择媒体。
            ToolMediaCard(
                icon = Icons.Default.Photo,
                label = stringResource(R.string.chat_tool_photo),
                modifier = Modifier.size(TelegramMediaHeight),
                onClick = {
                    MuseHaptics.light(hapticFeedback)
                    onPickImage(false)
                },
            )
        }

        if (selectedGalleryImages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选 ${selectedGalleryImages.size} 张",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(MusePaddings.contentGap))
                FilledTonalButton(
                    onClick = {
                        selectedGalleryImages.forEach(onPickGalleryImage)
                        selectedGalleryImages = emptyList()
                        onDismiss()
                    },
                    contentPadding = PaddingValues(horizontal = MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Spacer(Modifier.width(MusePaddings.tinyGap))
                    Text(stringResource(R.string.action_send))
                }
            }
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        // ── 第二行:联网搜索 + 深度思考 ──
        val findEntry: (String) -> ToolEntry? = { keyword ->
            entries.firstOrNull { it.title.contains(keyword) || keyword in it.title }
        }
        val webSearchEntry = findEntry("联网")
        val deepThinkingEntry = findEntry("深度思考")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            webSearchEntry?.let { entry ->
                QuickAttachTab(
                    icon = entry.icon,
                    label = entry.title,
                    isActive = entry.isActive,
                    onClick = entry.onClick,
                    compact = true,
                    dense = true,
                    modifier = Modifier.weight(1f),
                )
            }
            deepThinkingEntry?.let { entry ->
                QuickAttachTab(
                    icon = entry.icon,
                    label = entry.title,
                    isActive = entry.isActive,
                    onClick = entry.onClick,
                    onLongClick = entry.onLongClick,
                    compact = true,
                    dense = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        // ── 第三行:相册、附件、引用知识库及其余杂项，保持小胶囊并横向滚动 ──
        val miscEntries = buildList {
            add(
                QuickAttachEntry(
                    icon = Icons.Default.Photo,
                    label = stringResource(R.string.chat_gallery_cd),
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        onPickImage(false)
                    },
                ),
            )
            entries.firstOrNull { it.title.contains("附件") || it.title.contains("文档") }?.let { entry ->
                add(QuickAttachEntry(entry.icon, entry.title, onClick = entry.onClick))
            }
            entries.firstOrNull { it.title.contains("知识库") }?.let { entry ->
                add(QuickAttachEntry(entry.icon, entry.title, onClick = entry.onClick))
            }
            // 第三层容纳所有剩余杂项：提示词、技能、绘图、委托、重启上下文等。
            // 只排除第二层的两个核心开关，以及已经放入本行的入口，避免重复。
            val usedTitles = map { it.label }.toSet()
            entries
                .filter { it.title !in usedTitles }
                .filter { it.title != webSearchEntry?.title && it.title != deepThinkingEntry?.title }
                .forEach { entry ->
                    add(
                        QuickAttachEntry(
                            icon = entry.icon,
                            label = entry.title,
                            isActive = entry.isActive,
                            onClick = entry.onClick,
                            onLongClick = entry.onLongClick,
                        ),
                    )
                }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            miscEntries.forEach { entry ->
                QuickAttachTab(
                    icon = entry.icon,
                    label = entry.label,
                    isActive = entry.isActive,
                    onClick = entry.onClick,
                    onLongClick = entry.onLongClick,
                    compact = true,
                    modifier = Modifier
                    .wrapContentWidth()
                    .widthIn(min = 82.dp),
                )
            }
        }
    }
}

/** v1.0.72: 功能 tab 行条目(带可选激活态 + 长按)。 */
private data class QuickAttachEntry(
    val icon: ImageVector,
    val label: String,
    val isActive: Boolean = false,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

/** v1.0.72: Telegram 风格功能 tab(圆形图标 + 下方独立文字,不截断)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickAttachTab(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = when {
                dense -> 64.dp
                compact -> 48.dp
                else -> 72.dp
            })
            .clip(if (compact) MuseShapes.pill else MuseShapes.large)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            )
            .padding(
                horizontal = when {
                    dense -> MusePaddings.tightGap
                    compact -> MusePaddings.itemGap
                    else -> MusePaddings.contentGap
                },
                vertical = when {
                    dense -> MusePaddings.tinyGap
                    compact -> MusePaddings.tinyGap
                    else -> MusePaddings.tightGap
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            if (dense) MusePaddings.tightGap else MusePaddings.contentGap,
        ),
    ) {
        Surface(
            shape = CircleShape,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            },
            modifier = Modifier.size(
                when {
                    dense -> 36.dp
                    compact -> 32.dp
                    else -> 48.dp
                },
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(
                        when {
                            dense -> MuseIconSizes.iconSmall
                            compact -> MuseIconSizes.iconSmall
                            else -> MuseIconSizes.icon
                        },
                    ),
                )
            }
        }
        Text(
            text = label,
            style = if (compact || dense) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = if (compact && !dense) 1 else 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = if (compact && !dense) Modifier else Modifier.weight(1f),
        )
        if (isActive && !compact) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        } else if (!compact && onLongClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
    }
}

/** 最近相册图片缩略图,支持多选并显示选择顺序。 */
@Composable
private fun GalleryThumbnail(
    uri: Uri,
    selectionIndex: Int,
    onClick: () -> Unit,
) {
    val shape = MuseShapes.extraLarge
    Box(
        modifier = Modifier
            .size(TelegramMediaHeight)
            .clip(shape)
            .then(
                if (selectionIndex >= 0) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        SmartImage(
            model = uri,
            contentDescription = stringResource(R.string.chat_gallery_image_cd),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selectionIndex >= 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MusePaddings.tinyGap)
                    .size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = (selectionIndex + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * v1.0.72: 相机实时取景预览(CameraX)。
 *
 * 生命周期绑定 LocalLifecycleOwner,预览挂载到 PreviewView;
 * 点击预览格触发 [onTap](由调用方启动系统相机拍照)。
 */
@Composable
private fun CameraLivePreviewBox(
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTap by rememberUpdatedState(onTap)
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setOnClickListener { currentOnTap() }
            }
            runCatching {
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                        )
                    }.onFailure { e ->
                        Logger.w("MuseToolSheet", "相机预览绑定失败", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }.onFailure { e ->
                Logger.w("MuseToolSheet", "相机预览初始化失败", e)
            }
            previewView
        },
        modifier = modifier,
    )
}

/** B7-07: 工具菜单中的媒体快捷卡片。 */
@Composable
private fun ToolMediaCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        modifier = modifier.clickable(onClick = onClick),
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

/** v1.0.72: Telegram 媒体区高度(相机/照片入口统一尺寸)。 */
private val TelegramMediaHeight = 128.dp

private const val MAX_GALLERY_SELECTION = 4

/** 查询系统相册最近图片。每次打开面板都会重新读取,保证显示最新内容。 */
private fun queryRecentGalleryImages(context: Context, maxCount: Int): List<Uri> {
    return runCatching {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && uris.size < maxCount) {
                uris += ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn),
                )
            }
        }
        uris
    }.onFailure { e -> Logger.w("MuseToolSheet", "查询相册失败", e) }.getOrDefault(emptyList())
}
