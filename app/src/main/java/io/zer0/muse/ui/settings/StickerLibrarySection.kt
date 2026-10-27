package io.zer0.muse.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.STICKER_FREQ_FREQUENT
import io.zer0.muse.data.STICKER_FREQ_NORMAL
import io.zer0.muse.data.STICKER_FREQ_OCCASIONALLY
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.sticker.StickerItem
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.foundation.lazy.items as lazyItems

/**
 * v1.95: 表情包库管理区 — 启用开关 + 发送概率 + 导入 zip + 分类筛选 + 预览网格。
 *
 * 外层是 LazyColumn,这里用 FlowRow(而非 LazyVerticalGrid)布局预览网格,
 * 避免嵌套滚动冲突。表情包库存储独立于 Room,通过 [StickerLibraryRepository]
 * 读写 filesDir/stickers 目录。
 *
 * 交互:
 *  - 启用开关:控制 AI 回复时是否可发送表情包
 *  - 发送概率:0-100 连续滑块,AI 每次回复时有此概率调用 send_sticker
 *  - 导入 zip:通过 SAF 选取 zip,按文件夹结构自动分类
 *  - 分类胶囊:首个为"全部",其余为各分类;点击切换筛选
 *  - 预览网格:80x80dp 缩略图,长按弹删除确认对话框
 *  - 空态:灰色图标 + 标题 + 副标题
 *
 * @param settings 设置仓库(读写 sticker_enabled / sticker_frequency)
 * @param scope 协程作用域(发起导入/删除/保存操作)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun StickerLibrarySection(settings: SettingsRepository, scope: CoroutineScope) {
    val context = LocalContext.current
    // v2.6.2: 预解析提示文案（带参数的用模板 + replace）—— 回调 lambda 里直接用
    // context.getString 会触发 lint LocalContextGetResourceValueCall。
    //
    // v2.6.3 修复（issue #9 / 设置→聊天必崩）:
    // 这里必须用**无参** `stringResource(id)`。带参版本内部就是
    // Resources.getString(id, args),会立即执行 String.format,
    // 把字符串 "%1$d" 填到 %1$d 的整数槽位 →
    // IllegalFormatConversionException: d != java.lang.String。
    // 无参版不做格式化,返回的就是含 %1$d 的模板原文,
    // 下方 .replace("%1\$d", ...) 照常工作,且无参版本就不触发那条 lint。
    val stickerDeletedFormat = androidx.compose.ui.res.stringResource(R.string.settings_sticker_deleted_toast)
    val stickerClearedFormat = androidx.compose.ui.res.stringResource(R.string.settings_sticker_cleared_toast)
    val stickerRepo: StickerLibraryRepository = koinInject()
    val stickerEnabled by settings.stickerEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    // v2.x: 发送频率档位(标记链路;替代旧发送概率)
    val stickerFrequency by settings.stickerFrequencyFlow.collectAsStateWithLifecycle(initialValue = STICKER_FREQ_NORMAL)

    // 刷新触发器:导入/删除后自增以重新加载分类与列表
    var refreshTrigger by remember { mutableStateOf(0) }
    // 当前选中分类;空字符串表示"全部"
    var selectedCategory by remember { mutableStateOf("") }
    // 待删除的表情包项(非 null 时弹删除确认对话框)
    var pendingDelete by remember { mutableStateOf<StickerItem?>(null) }
    // v1.0.52: 点击预览的表情包项(非 null 时弹全屏查看器)
    var previewItem by remember { mutableStateOf<StickerItem?>(null) }
    // v1.112 (F1-F2): 批量删除模式
    var batchDeleteMode by remember { mutableStateOf(false) }
    val selectedIds = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // v1.112: 批量删除/清空确认
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // 分类列表(导入/删除后刷新)
    val categories by produceState(initialValue = emptyList<String>(), refreshTrigger) {
        value = stickerRepo.listCategories()
    }

    // 表情包列表(按选中分类筛选;导入/删除/切分类时刷新)
    val stickers by produceState(
        initialValue = emptyList<StickerItem>(),
        refreshTrigger,
        selectedCategory,
    ) {
        value = stickerRepo.listStickers(selectedCategory.takeIf { it.isNotBlank() })
    }

    // 导入 zip launcher(SAF OpenDocument,限定 zip MIME)
    // v1.0.54: 改用 importUri(自动识别 zip/图片) + 进度回调,老设备导入大包需要可见反馈
    var importingSticker by remember { mutableStateOf(false) }
    var stickerImportProgress by remember { mutableStateOf<Float?>(null) }
    var stickerImportText by remember { mutableStateOf("") }
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            importingSticker = true
            stickerImportProgress = null
            stickerImportText = ""
            scope.launch {
                stickerRepo.importUri(uri) { phase, done, total ->
                    stickerImportProgress =
                        if (total != null && total > 0) {
                            (done.toFloat() / total).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                    stickerImportText =
                        if (total != null && total > 0) {
                            "$phase $done/$total"
                        } else {
                            context.getString(R.string.settings_sticker_import_progress, phase, done)
                        }
                }
                    .onSuccess { count ->
                        MuseToast.show(context.getString(R.string.settings_sticker_imported, count))
                        refreshTrigger++
                    }
                    .onError { msg, _ ->
                        MuseToast.show(context.getString(R.string.settings_sticker_import_failed, msg), 3500)
                    }
                importingSticker = false
                stickerImportProgress = null
            }
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup {
            // 启用开关
            SettingsSwitchRow(
                icon = MuseIcons.image,
                title = stringResource(R.string.settings_sticker_enabled),
                subtitle = stringResource(R.string.settings_sticker_enabled_subtitle),
                checked = stickerEnabled,
                onCheckedChange = { v -> scope.launch { settings.saveStickerEnabled(v) } },
            )
            SettingsGroupDivider()
            // v2.x: 发送频率档位(标记链路;替代旧概率滑块)
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.sliders,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_sticker_frequency),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_sticker_frequency_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            STICKER_FREQ_OCCASIONALLY to R.string.settings_sticker_freq_occasional,
                            STICKER_FREQ_NORMAL to R.string.settings_sticker_freq_normal,
                            STICKER_FREQ_FREQUENT to R.string.settings_sticker_freq_frequent,
                        ).forEach { (value, labelRes) ->
                            MuseChip(
                                selected = stickerFrequency == value,
                                onClick = {
                                    scope.launch {
                                        settings.saveStickerFrequency(value)
                                        // v2.x: 选择档位即视为想用表情包 — 自动开启总开关
                                        if (!stickerEnabled) settings.saveStickerEnabled(true)
                                    }
                                },
                                label = stringResource(labelRes),
                            )
                        }
                    }
                }
            }
            SettingsGroupDivider()
            // 导入 zip(通过 SAF 选取压缩包)
            SettingsItemRow(
                icon = MuseIcons.upload,
                title = stringResource(R.string.settings_sticker_upload_zip),
                subtitle = stringResource(R.string.settings_sticker_upload_zip_subtitle),
                onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                },
            ) { ChevronRight() }
            // v1.0.54: 导入进度文本(进度条不随真实进度,用户反馈只保留文本)
            if (importingSticker) {
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (stickerImportText.isNotBlank()) {
                        Text(
                            text = stickerImportText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // v1.112 (F1-F2): 批量删除 / 清空入口
            if (stickers.isNotEmpty()) {
                SettingsGroupDivider()
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseCapsuleButton(
                        text =
                        if (batchDeleteMode) {
                            stringResource(R.string.settings_sticker_batch_done)
                        } else {
                            stringResource(R.string.settings_sticker_batch_delete)
                        },
                        onClick = {
                            batchDeleteMode = !batchDeleteMode
                            if (!batchDeleteMode) selectedIds.clear()
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.settings_common_clear_all),
                        onClick = { showClearAllConfirm = true },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 分类胶囊标签(仅当有表情包时才显示)。
        // 分类数量来自导入内容，必须有界；否则会把下方预览网格推出可视区域。
        if (stickers.isNotEmpty()) {
            LazyRow(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                item(key = "all") {
                    MuseChip(
                        selected = selectedCategory.isBlank(),
                        onClick = { selectedCategory = "" },
                        label = stringResource(R.string.settings_sticker_category_all),
                    )
                }
                lazyItems(categories, key = { it }) { cat ->
                    MuseChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = cat,
                    )
                }
            }
        }

        // 预览网格 / 空态
        if (stickers.isEmpty()) {
            // 空态:灰色图标 + 标题 + 副标题
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = MuseIcons.image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_sticker_empty_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.settings_sticker_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            // v1.112 (F5): 用 LazyVerticalGrid 替代 FlowRow,懒加载 600 张不卡顿
            // 高度固定为 3 行(240dp),内部可滚动;加 key 让 Compose 精准重组
            val gridColumns = GridCells.Adaptive(80.dp)
            LazyVerticalGrid(
                columns = gridColumns,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = stickers,
                    key = { it.id },
                ) { item ->
                    val file = stickerRepo.getStickerFileByPath(item.relativePath)
                    val isSelected = selectedIds[item.id] == true
                    Box(
                        modifier =
                        Modifier
                            .size(80.dp)
                            .clip(MuseShapes.medium)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                            )
                            .combinedClickable(
                                onClick = {
                                    if (batchDeleteMode) {
                                        selectedIds[item.id] = !isSelected
                                    } else {
                                        // v1.0.52: 非批量模式点击预览大图
                                        previewItem = item
                                    }
                                },
                                onLongClick = {
                                    if (!batchDeleteMode) pendingDelete = item
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model =
                            coil.request.ImageRequest.Builder(context)
                                .data(file)
                                // v1.112 (F5): 限制解码尺寸为 80dp,避免大图全分辨率解码浪费内存
                                .size(160)
                                .crossfade(false)
                                .build(),
                            contentDescription = item.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // v1.112 (F1-F2): 批量删除模式下的选中蒙层
                        if (batchDeleteMode && isSelected) {
                            Box(
                                modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MuseIcons.check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            }
            // v1.112 (F1-F2): 批量删除模式下,底部显示删除按钮
            if (batchDeleteMode && selectedIds.isNotEmpty()) {
                Surface(
                    onClick = { showBatchDeleteConfirm = true },
                    color = MaterialTheme.colorScheme.error,
                    shape = MuseShapes.pill,
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_sticker_delete_selected, selectedIds.count { it.value }),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onError,
                        textAlign = TextAlign.Center,
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }

    // 删除确认对话框(长按表情包后弹出)
    pendingDelete?.let { item ->
        MuseDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.settings_sticker_delete_confirm),
            content = { Text(item.fileName) },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    stickerRepo.deleteSticker(item.id)
                    pendingDelete = null
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }

    // v1.0.52: 点击大图预览(复用聊天消息的全屏媒体查看器)
    previewItem?.let { item ->
        val file = stickerRepo.getStickerFileByPath(item.relativePath)
        FullScreenMediaViewer(
            images = listOf("file://${file.absolutePath}"),
            initialIndex = 0,
            onDismiss = { previewItem = null },
        )
    }

    // v1.112 (F1-F2): 批量删除确认
    if (showBatchDeleteConfirm) {
        val count = selectedIds.count { it.value }
        MuseDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = stringResource(R.string.settings_sticker_batch_confirm_title),
            content = { Text(stringResource(R.string.settings_sticker_batch_confirm_content, count)) },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    val ids = selectedIds.filter { it.value }.keys
                    val deleted = stickerRepo.deleteStickers(ids)
                    MuseToast.show(stickerDeletedFormat.replace("%1\$d", deleted.toString()))
                    selectedIds.clear()
                    batchDeleteMode = false
                    showBatchDeleteConfirm = false
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showBatchDeleteConfirm = false },
            destructive = true,
        )
    }

    // v1.112 (F1-F2): 清空全部确认
    if (showClearAllConfirm) {
        MuseDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = stringResource(R.string.settings_sticker_clear_all_title),
            content = { Text(stringResource(R.string.settings_sticker_clear_all_confirm_content)) },
            confirmText = stringResource(R.string.settings_common_clear),
            onConfirm = {
                scope.launch {
                    val deleted = stickerRepo.clearAll()
                    MuseToast.show(stickerClearedFormat.replace("%1\$d", deleted.toString()))
                    selectedIds.clear()
                    batchDeleteMode = false
                    showClearAllConfirm = false
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showClearAllConfirm = false },
            destructive = true,
        )
    }
}

/**
 * E3: 聊天渐变背景预设(双色线性渐变,ARGB Long 色值)。
 * 仅色块展示,名称不展示(避免引入 6 组 ×7 语言的翻译成本)。
 */
internal val PRESET_CHAT_GRADIENTS: List<Pair<Long, Long>> =
    listOf(
        // 晨蓝
        0xFFE3F2FDL to 0xFF90CAF9L,
        // 深海
        0xFF1A2980L to 0xFF26D0CEL,
        // 夜航
        0xFF0F2027L to 0xFF2C5364L,
        // 暖金
        0xFFFCEABBL to 0xFFF8B500L,
        // 松石
        0xFF43C6ACL to 0xFF191654L,
        // 晚霞
        0xFF800000L to 0xFFE8B298L,
    )

/**
 * 贴纸设置整块：分组标题 + 库管理。
 *
 * 包成单个 composable，调用方只需一个 item，避免把页面方法撑长。
 */
@Composable
internal fun StickerSettingsSection(settings: SettingsRepository, scope: CoroutineScope) {
    Column {
        SectionLabel(stringResource(R.string.settings_chat_sticker_section))
        StickerLibrarySection(settings = settings, scope = scope)
    }
}
