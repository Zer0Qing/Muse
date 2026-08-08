package io.zer0.muse.ui

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
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
import coil.compose.AsyncImage
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        maxHeightFraction = 0.62f,
        // v1.0.72: 加号菜单左右不留白(图标顶到边缘,不被截半)
        horizontalPadding = 0.dp,
    ) {
        Text(
            text = stringResource(R.string.chat_tools_pick_content),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(MusePaddings.contentGap))

        // ── 顶部媒体区:相机预览(第一格) + 相册最近图片 ──
        var recentImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
        LaunchedEffect(hasGalleryPermission) {
            if (hasGalleryPermission) {
                recentImages = withContext(Dispatchers.IO) {
                    queryRecentGalleryImages(context, 8)
                }
            }
        }

        val cameraPermission = android.Manifest.permission.CAMERA
        val hasCameraPermission = remember {
            ContextCompat.checkSelfPermission(context, cameraPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        var cameraGranted by remember { mutableStateOf(hasCameraPermission) }
        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> cameraGranted = granted }

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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            // 第一格:相机实时预览
            if (cameraGranted) {
                CameraLivePreviewBox(
                    modifier = Modifier
                        .size(TelegramMediaHeight)
                        .clip(MuseShapes.extraLarge),
                    onTap = {
                        MuseHaptics.light(hapticFeedback)
                        // 创建拍照目标 uri(FileProvider)
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
            } else {
                // 未授权:显示相机图标卡,点击请求权限
                ToolMediaCard(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(R.string.chat_tool_camera),
                    modifier = Modifier.size(TelegramMediaHeight),
                    onClick = { cameraPermissionLauncher.launch(cameraPermission) },
                )
            }

            // v1.0.72: 相册入口卡已移除(右侧有相册缩略图,下方横排有"相册"tab,无需重复)
            // 最近相册图片缩略图
            if (hasGalleryPermission) {
                recentImages.forEach { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.chat_gallery_image_cd),
                        modifier = Modifier
                            .size(TelegramMediaHeight)
                            .clip(MuseShapes.extraLarge)
                            .clickable {
                                MuseHaptics.light(hapticFeedback)
                                onPickGalleryImage(uri)
                            },
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .height(TelegramMediaHeight)
                        .clip(MuseShapes.extraLarge)
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

        // ── v1.0.72: 全部选项合并进一条横向滚动 tab 行(去重) ──
        // 顺序: 联网搜索 / 深度思考(开关类,放最前,长按可切换深度)→ 相册 / 文件 / 文章
        //  → 其余 entries 追加(绘图/Prompt/技能/委托已在长按菜单覆盖,这里取消)
        val findEntry: (String) -> ToolEntry? = { keyword ->
            entries.firstOrNull {
                it.title.contains(keyword) || keyword in it.title
            }
        }
        // 已取消的入口(长按菜单/其他页面已覆盖,避免重复)
        val cancelled = listOf("绘图", "提示词", "Prompt", "技能", "委托")
        val builtin = buildList {
            // 开关类工具(带 isActive 状态 + 长按): 联网搜索 / 深度思考
            findEntry("联网")?.let { add(QuickAttachEntry(it.icon, it.title, isActive = it.isActive, onClick = it.onClick)) }
            findEntry("深度思考")?.let {
                add(QuickAttachEntry(it.icon, it.title, isActive = it.isActive, onClick = it.onClick, onLongClick = it.onLongClick))
            }
            // 固定功能: 相册 / 附件 / 知识库(从 entries 匹配,缺失则跳过)
            add(QuickAttachEntry(Icons.Default.Photo, "相册", onClick = {
                MuseHaptics.light(hapticFeedback)
                onPickImage(false)
            }))
            findEntry("附件")?.let { add(QuickAttachEntry(it.icon, it.title, onClick = it.onClick)) }
                ?: findEntry("文档")?.let { add(QuickAttachEntry(it.icon, it.title, onClick = it.onClick)) }
            findEntry("知识库")?.let { add(QuickAttachEntry(it.icon, it.title, onClick = it.onClick)) }
        }
        // 其余 entries 追加(去重 + 过滤已取消项)
        val usedTitles = builtin.map { it.label }.toSet()
        val rest = entries
            .filter { it.title !in usedTitles }
            .filter { e -> !cancelled.any { e.title.contains(it) } }
            .map { QuickAttachEntry(it.icon, it.title, isActive = it.isActive, onClick = it.onClick, onLongClick = it.onLongClick) }
        val allTabs = builtin + rest

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.screen),
        ) {
            allTabs.forEach { e ->
                QuickAttachTab(
                    icon = e.icon,
                    label = e.label,
                    isActive = e.isActive,
                    onClick = e.onClick,
                    onLongClick = e.onLongClick,
                )
            }
        }

        Spacer(Modifier.height(MusePaddings.screen))
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
) {
    Column(
        modifier = Modifier
            .widthIn(min = 60.dp)
            .clip(MuseShapes.extraLarge)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 圆形图标(激活时高亮)
        Surface(
            shape = CircleShape,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            },
            modifier = Modifier.size(44.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 文字独立放在圆外,不截断(允许换行)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
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

/** v1.0.72: Telegram 媒体区高度(相机/相册缩略图统一尺寸)。 */
private val TelegramMediaHeight = 128.dp
