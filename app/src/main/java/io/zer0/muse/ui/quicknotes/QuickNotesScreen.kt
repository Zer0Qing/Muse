@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")

package io.zer0.muse.ui.quicknotes

import android.content.Intent
import io.zer0.muse.util.ShareIntentHelper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.0.19: 快速记录页面按新设计重写。
 *
 * 主要变化:
 *  - 顶部栏改为标题居中 + 两侧操作图标(导出/删除),使用大标题
 *  - 搜索框胶囊化、居中占位符
 *  - 标签筛选使用 MuseChip,选中态为品牌绿
 *  - 历史记录区域显示"历史记录 / 共 N 条"
 *  - 卡片列表重新设计:
 *    - 置顶记录:浅绿背景 + AutoAwesome 图标 + 无底部操作按钮
 *    - 普通记录:白色卡片 + 复制/发送/更多 操作
 *    - 单击卡片可展开/收起正文
 *    - 加密记录显示 Lock 图标 + 占位文案
 *    - 文件夹/提醒使用独立小 chip 展示
 *  - 保留回收站、导入导出、文件夹、提醒、加密、编辑等全部既有能力
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickNotesScreen(
    onBack: () -> Unit,
    viewModel: QuickNotesViewModel,
    initialNoteId: String? = null,
    onSendToNewChat: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings: SettingsRepository = koinInject()
    val quickCaptureEnabled by settings.quickCaptureEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val quickCaptureOverlayEnabled by settings.quickCaptureOverlayEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val listState = rememberLazyListState()
    // 前端修复 (性能-3): 页面级共享时间 ticker — 单协程每 60s 广播一次
    // 当前时间戳,所有卡片共用;替代原先每张卡片一个 produceState 无限循环。
    val timeTicker by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    var editingNote by remember { mutableStateOf<QuickNoteEntity?>(null) }
    var noteToDelete by remember { mutableStateOf<QuickNoteEntity?>(null) }
    var noteForMenu by remember { mutableStateOf<QuickNoteEntity?>(null) }
    // 前端修复 (持久化-6): 输入草稿改 rememberSaveable,旋转/进程重建不丢已输入内容
    var inputText by rememberSaveable { mutableStateOf("") }
    // 回收站相关弹窗状态
    // 前端修复 (持久化-6): 弹窗目标为 QuickNoteEntity 复杂对象,无法直接 saveable,保持 remember
    var noteToPermanentDelete by remember { mutableStateOf<QuickNoteEntity?>(null) }
    var showClearTrashConfirm by rememberSaveable { mutableStateOf(false) }
    // 导出/导入菜单 + 文件夹设置 + 提醒设置 弹窗状态
    // 前端修复 (持久化-6): Boolean 弹窗态改 rememberSaveable;实体引用保持 remember
    var showExportMenu by rememberSaveable { mutableStateOf(false) }
    var showQuickCaptureSettings by rememberSaveable { mutableStateOf(false) }
    var noteForFolder by remember { mutableStateOf<QuickNoteEntity?>(null) }
    var noteForReminder by remember { mutableStateOf<QuickNoteEntity?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                }.getOrNull()
                if (json.isNullOrBlank()) {
                    MuseToast.show(context.getString(R.string.quick_notes_import_failed))
                } else {
                    val count = viewModel.importFromJson(json)
                    MuseToast.show(
                        if (count > 0) context.getString(R.string.quick_notes_import_done, count)
                        else context.getString(R.string.quick_notes_import_failed),
                    )
                }
            }
        }
    }

    // 分页 — 列表滚动接近底部时自动加载更多
    val reachedBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(reachedBottom, state.hasMore) {
        if (reachedBottom && state.hasMore) viewModel.loadMore()
    }
    LaunchedEffect(initialNoteId) {
        initialNoteId?.let(viewModel::revealNote)
    }
    LaunchedEffect(initialNoteId, state.notes) {
        val targetIndex = initialNoteId?.let { id -> state.notes.indexOfFirst { it.id == id } } ?: -1
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
        }
    }

    fun saveInput() {
        val text = inputText.trim()
        if (text.isBlank()) return
        val tags = extractHashTags(text)
        val title = deriveTitle(text, tags)
        viewModel.add(title, text, tags)
        inputText = ""
    }

    fun copyNote(note: QuickNoteEntity) {
        val text = buildString {
            if (note.title.isNotBlank()) appendLine(note.title)
            if (note.content.isNotBlank()) appendLine(note.content)
            if (note.tags.isNotEmpty()) {
                appendLine(note.tags.joinToString(" ") { "#$it" })
            }
        }.trim()
        clipboard.setText(AnnotatedString(text))
        MuseToast.show(context.getString(R.string.quick_notes_copied))
    }

    fun sendNoteToChat(note: QuickNoteEntity) {
        val text = buildString {
            if (note.title.isNotBlank()) appendLine(note.title)
            if (note.content.isNotBlank()) appendLine(note.content)
        }.trim()
        onSendToNewChat(text)
    }

    // 导出为 Markdown/JSON — 通过系统分享面板让用户保存或转发
    fun shareText(text: String, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, context.getString(R.string.quick_notes_share_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ShareIntentHelper.startChooserSafely(context, intent, context.getString(R.string.quick_notes_export))
    }

    fun exportMarkdown() {
        scope.launch {
            val md = viewModel.exportToMarkdown()
            if (md.isBlank()) {
                MuseToast.show(context.getString(R.string.quick_notes_export_empty))
            } else {
                shareText(md, "text/markdown")
            }
        }
    }

    fun exportJson() {
        scope.launch {
            val json = viewModel.exportToJson()
            if (json.isBlank() || json == "[]") {
                MuseToast.show(context.getString(R.string.quick_notes_export_empty))
            } else {
                shareText(json, "application/json")
            }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.quick_notes_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    IconButton(onClick = { showQuickCaptureSettings = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_screen_quick_notes),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.icon),
                        )
                    }
                    // 导出/导入入口
                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.IosShare,
                            contentDescription = stringResource(R.string.quick_notes_export),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.icon),
                        )
                    }
                    // 回收站入口
                    IconButton(onClick = { viewModel.toggleTrash(true) }) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.quick_notes_trash),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.icon),
                        )
                    }
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
        ) {
            Spacer(Modifier.height(MusePaddings.contentGap))
            QuickNoteSearchField(
                value = state.searchKeyword,
                onValueChange = viewModel::onSearchKeywordChange,
                placeholder = stringResource(R.string.quick_notes_search),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MusePaddings.sectionGap))
            QuickNoteInputCard(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = ::saveInput,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MusePaddings.sectionGap))
            // 文件夹筛选条(有文件夹时展示)
            if (state.folders.isNotEmpty()) {
                QuickNoteFolderFilterRow(
                    folders = state.folders,
                    selectedFolder = state.selectedFolder,
                    onFolderSelected = viewModel::onFolderSelected,
                )
                Spacer(Modifier.height(MusePaddings.sectionGap))
            }
            if (state.allTags.isNotEmpty()) {
                QuickNoteTagFilterRow(
                    tags = state.allTags,
                    selectedTag = state.selectedTag,
                    onTagSelected = viewModel::onTagSelected,
                )
                Spacer(Modifier.height(MusePaddings.sectionGap))
            }
            if (state.notes.isEmpty()) {
                MuseEmptyState(
                    icon = Icons.Outlined.Lightbulb,
                    title = if (state.searchKeyword.isBlank()) {
                        stringResource(R.string.quick_notes_empty_title)
                    } else {
                        stringResource(R.string.quick_notes_empty_search_title)
                    },
                    subtitle = if (state.searchKeyword.isBlank()) {
                        stringResource(R.string.quick_notes_empty_subtitle)
                    } else {
                        stringResource(R.string.quick_notes_empty_search_subtitle)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.quick_notes_history),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.quick_notes_count, state.notes.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(MusePaddings.contentGap))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    items(state.notes, key = { it.id }) { note ->
                        QuickNoteCard(
                            note = note,
                            now = timeTicker,
                            initiallyExpanded = note.id == initialNoteId,
                            onCopy = { copyNote(note) },
                            onSendToChat = { sendNoteToChat(note) },
                            onEdit = { editingNote = note },
                            onDelete = { noteToDelete = note },
                            onMore = { noteForMenu = note },
                        )
                    }
                    // 分页 — 还有更多时显示加载更多提示
                    if (state.hasMore) {
                        item(key = "load_more") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MusePaddings.contentGap),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.quick_notes_load_more),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQuickCaptureSettings) {
        QuickCaptureSettingsSheet(
            quickCaptureEnabled = quickCaptureEnabled,
            quickCaptureOverlayEnabled = quickCaptureOverlayEnabled,
            context = context,
            settings = settings,
            onDismiss = { showQuickCaptureSettings = false },
        )
    }

    editingNote?.let { note ->
        QuickNoteDialog(
            existing = note,
            onDismiss = { editingNote = null },
            onSave = { title, content, tags, contentType ->
                viewModel.update(note.id, title, content, tags, contentType = contentType)
                editingNote = null
            },
        )
    }

    noteToDelete?.let { note ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.quick_notes_delete_title),
            itemName = note.title,
            onConfirm = {
                // 改为 soft delete(移入回收站),用户可在回收站恢复或永久删除
                viewModel.delete(note.id)
                noteToDelete = null
            },
            onDismiss = { noteToDelete = null },
        )
    }

    noteForMenu?.let { note ->
        QuickNoteActionMenu(
            note = note,
            onDismiss = { noteForMenu = null },
            onEdit = {
                noteForMenu = null
                editingNote = note
            },
            onCopy = {
                noteForMenu = null
                copyNote(note)
            },
            onSetFolder = {
                noteForMenu = null
                noteForFolder = note
            },
            onSetReminder = {
                noteForMenu = null
                noteForReminder = note
            },
            onToggleEncrypt = {
                noteForMenu = null
                viewModel.setEncrypted(note.id, !note.encrypted)
            },
            onDelete = {
                noteForMenu = null
                noteToDelete = note
            },
        )
    }

    // 导出/导入菜单
    if (showExportMenu) {
        QuickNoteExportMenu(
            onDismiss = { showExportMenu = false },
            onExportMarkdown = {
                showExportMenu = false
                exportMarkdown()
            },
            onExportJson = {
                showExportMenu = false
                exportJson()
            },
            onImportJson = {
                showExportMenu = false
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
        )
    }

    // 文件夹设置弹窗
    noteForFolder?.let { note ->
        QuickNoteFolderDialog(
            folders = state.folders,
            onDismiss = { noteForFolder = null },
            onSelect = { folder ->
                viewModel.setFolder(note.id, folder)
                noteForFolder = null
            },
        )
    }

    // 提醒设置弹窗(日期 + 时间选择器)
    noteForReminder?.let { note ->
        QuickNoteReminderDialog(
            currentReminderAt = note.reminderAt,
            onDismiss = { noteForReminder = null },
            onSet = { timestamp ->
                viewModel.setReminder(note.id, note.title, note.content, timestamp)
                noteForReminder = null
            },
            onCancel = {
                viewModel.cancelReminder(note.id)
                noteForReminder = null
            },
        )
    }

    // 回收站面板
    if (state.showTrash) {
        QuickNoteTrashDialog(
            items = state.trashItems,
            onDismiss = { viewModel.toggleTrash(false) },
            onRestore = { viewModel.restore(it.id) },
            onPermanentDelete = { noteToPermanentDelete = it },
            onClearAll = { showClearTrashConfirm = true },
        )
    }

    // 永久删除单条确认
    noteToPermanentDelete?.let { note ->
        MuseDialog(
            onDismissRequest = { noteToPermanentDelete = null },
            title = stringResource(R.string.quick_notes_delete_permanent_title),
            content = {
                Text(
                    text = stringResource(R.string.quick_notes_delete_permanent_confirm, note.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmText = stringResource(R.string.quick_notes_delete_permanent),
            onConfirm = {
                viewModel.deletePermanent(note.id)
                noteToPermanentDelete = null
            },
            dismissText = stringResource(R.string.quick_notes_cancel),
            onDismiss = { noteToPermanentDelete = null },
        )
    }

    // 清空回收站确认
    if (showClearTrashConfirm) {
        MuseDialog(
            onDismissRequest = { showClearTrashConfirm = false },
            title = stringResource(R.string.quick_notes_clear_trash),
            content = {
                Text(
                    text = stringResource(
                        R.string.quick_notes_clear_trash_confirm,
                        state.trashItems.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmText = stringResource(R.string.quick_notes_clear_trash),
            onConfirm = {
                viewModel.clearTrash()
                showClearTrashConfirm = false
            },
            dismissText = stringResource(R.string.quick_notes_cancel),
            onDismiss = { showClearTrashConfirm = false },
        )
    }
}

@Composable
private fun QuickCaptureSettingsSheet(
    quickCaptureEnabled: Boolean,
    quickCaptureOverlayEnabled: Boolean,
    context: android.content.Context,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.quick_notes_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            QuickCaptureSettingRow(
                title = stringResource(R.string.quick_capture_swipe_title),
                description = stringResource(R.string.quick_capture_swipe_desc),
                checked = quickCaptureEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        settings.saveQuickCaptureEnabled(enabled)
                        if (!enabled) {
                            settings.saveQuickCaptureOverlayEnabled(false)
                            QuickCaptureOverlayService.stop(context)
                        }
                    }
                },
            )
            QuickCaptureSettingRow(
                title = stringResource(R.string.quick_capture_overlay_title),
                description = stringResource(R.string.quick_capture_overlay_desc),
                checked = quickCaptureOverlayEnabled,
                enabled = quickCaptureEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        scope.launch { settings.saveQuickCaptureOverlayEnabled(false) }
                    } else if (
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                        !android.provider.Settings.canDrawOverlays(context)
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }.onFailure {
                            MuseToast.show(context.getString(R.string.quick_capture_overlay_permission_error))
                        }
                    } else {
                        scope.launch { settings.saveQuickCaptureOverlayEnabled(true) }
                    }
                },
            )
            Text(
                text = stringResource(R.string.quick_capture_overlay_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = MusePaddings.sectionGap),
            )
        }
    }
}

@Composable
private fun QuickCaptureSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        MuseSwitch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            contentDescription = title,
        )
    }
}

@Composable
private fun QuickNoteSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    MuseTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.quick_notes_clear_search),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
        },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun QuickNoteInputCard(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
        ) {
            MuseTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.quick_notes_input_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp),
                minLines = 3,
                maxLines = 6,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.quick_notes_tag_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                SendButton(
                    enabled = value.trim().isNotBlank(),
                    onClick = onSend,
                )
            }
        }
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.outline
        },
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = stringResource(R.string.quick_notes_save),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickNoteTagFilterRow(
    tags: List<String>,
    selectedTag: String?,
    onTagSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        tags.forEach { tag ->
            MuseChip(
                selected = tag == selectedTag,
                onClick = { onTagSelected(tag) },
                label = "#$tag",
            )
        }
    }
}

@Composable
private fun QuickNoteCard(
    note: QuickNoteEntity,
    /** 前端修复 (性能-3): 页面级共享 ticker 时间戳,用于相对时间刷新。 */
    now: Long,
    initiallyExpanded: Boolean = false,
    onCopy: () -> Unit,
    onSendToChat: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
) {
    val isPinned = note.pinned
    val canExpand = !note.encrypted && note.content.isNotBlank()
    var expanded by rememberSaveable(note.id) { mutableStateOf(initiallyExpanded) }
    val cardBackground = if (isPinned) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentTint = if (isPinned) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val attachments = remember(note.attachmentsJson) {
        QuickNotesViewModel.parseAttachments(note.attachmentsJson)
    }

    Surface(
        onClick = { if (canExpand) expanded = !expanded },
        enabled = canExpand,
        shape = MuseShapes.extraLarge,
        color = cardBackground,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS)),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
        ) {
            // 标题行:置顶时左侧显示 AI 星星图标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                if (isPinned) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = contentTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canExpand) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = stringResource(
                            if (expanded) R.string.common_collapse else R.string.common_expand,
                        ),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
            }

            // 内容区 — 加密时显示占位,Markdown 时用 MarkdownText 渲染
            if (note.encrypted) {
                Spacer(Modifier.height(MusePaddings.tightGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Spacer(Modifier.width(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.quick_notes_encrypt_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                }
            } else if (note.content.isNotBlank()) {
                Spacer(Modifier.height(MusePaddings.tightGap))
                if (note.contentType == "markdown") {
                    if (expanded) {
                        MarkdownText(
                            text = note.content,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPinned) {
                                contentTint
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    } else {
                        Text(
                            text = note.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPinned) {
                                contentTint
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = note.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPinned) contentTint else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 附件指示
            if (attachments.isNotEmpty()) {
                Spacer(Modifier.height(MusePaddings.labelVerticalGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Spacer(Modifier.size(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.quick_notes_attachment_count, attachments.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // 标签
            if (note.tags.isNotEmpty()) {
                Spacer(Modifier.height(MusePaddings.labelVerticalGap))
                Text(
                    text = note.tags.joinToString("  #", prefix = "#"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPinned) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 文件夹/提醒 chip
            val showFolder = note.folder.isNotBlank()
            val showReminder = note.reminderAt > 0
            if (showFolder || showReminder) {
                Spacer(Modifier.height(MusePaddings.labelVerticalGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showFolder) {
                        NoteMetaChip(
                            icon = Icons.Default.Folder,
                            text = note.folder,
                        )
                    }
                    if (showReminder) {
                        NoteMetaChip(
                            icon = Icons.Outlined.Notifications,
                            text = formatReminderAt(note.reminderAt),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(MusePaddings.auxGap))
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(MusePaddings.auxGap))

            // 底部:时间 + 操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatNoteTime(note.updatedAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.weight(1f))

                if (isPinned) {
                    // 置顶记录只保留"更多"入口(编辑/删除等)
                    IconButton(
                        onClick = onMore,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.quick_notes_more),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                } else {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.quick_notes_copy),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                    IconButton(
                        onClick = onSendToChat,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = stringResource(R.string.quick_notes_send_to_chat),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                    IconButton(
                        onClick = onMore,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.quick_notes_more),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
            }
        }
    }
}


