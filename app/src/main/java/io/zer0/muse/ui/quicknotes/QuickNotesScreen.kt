package io.zer0.muse.ui.quicknotes

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Scaffold
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
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
 *    - 加密记录显示 Lock 图标 + 占位文案
 *    - 文件夹/提醒使用独立小 chip 展示
 *  - 保留回收站、导入导出、文件夹、提醒、加密、编辑等全部既有能力
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickNotesScreen(
    onBack: () -> Unit,
    viewModel: QuickNotesViewModel,
    onSendToNewChat: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var editingNote by remember { mutableStateOf<QuickNoteEntity?>(null) }
    var noteToDelete by remember { mutableStateOf<QuickNoteEntity?>(null) }
    var noteForMenu by remember { mutableStateOf<QuickNoteEntity?>(null) }
    var inputText by remember { mutableStateOf("") }
    // 回收站相关弹窗状态
    var noteToPermanentDelete by remember { mutableStateOf<QuickNoteEntity?>(null) }
    var showClearTrashConfirm by remember { mutableStateOf(false) }
    // 导出/导入菜单 + 文件夹设置 + 提醒设置 弹窗状态
    var showExportMenu by remember { mutableStateOf(false) }
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
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.quick_notes_export)))
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

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.quick_notes_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
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
    onCopy: () -> Unit,
    onSendToChat: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
) {
    val isPinned = note.pinned
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
        shape = MuseShapes.extraLarge,
        color = cardBackground,
        modifier = Modifier.fillMaxWidth(),
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
                    MarkdownText(
                        text = note.content,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPinned) contentTint else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = note.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPinned) contentTint else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
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
                    text = formatNoteTime(note.updatedAt),
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

/** 卡片元信息小胶囊(文件夹 / 提醒时间标记)。 */
@Composable
private fun NoteMetaChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        shape = MuseShapes.pill,
        color = tint.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.iconPadding,
                vertical = MusePaddings.labelVerticalGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 按设计图显示相对时间:今天 HH:mm / 昨天 / N 天前 / MM-dd。 */
@Composable
private fun formatNoteTime(timestamp: Long): String {
    val fmtTime = remember { SimpleDateFormat(MuseDateFormats.TIME_SHORT, Locale.getDefault()) }
    val fmtDate = remember { SimpleDateFormat(MuseDateFormats.DATE_SHORT, Locale.getDefault()) }
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    if (timestamp <= 0) return ""

    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val noteCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val daysDiff = ((now - timestamp) / 86_400_000).toInt()

    return when {
        nowCal.get(Calendar.YEAR) == noteCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == noteCal.get(Calendar.DAY_OF_YEAR) -> {
            "${stringResource(R.string.memory_filter_today)} ${fmtTime.format(Date(timestamp))}"
        }
        daysDiff == 1 -> stringResource(R.string.memory_created_yesterday)
        daysDiff in 2..6 -> stringResource(R.string.memory_created_days_ago, daysDiff)
        else -> fmtDate.format(Date(timestamp))
    }
}

@Composable
private fun formatReminderAt(timestamp: Long): String {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    return fmt.format(Date(timestamp))
}

@Composable
private fun QuickNoteActionMenu(
    note: QuickNoteEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onSetFolder: () -> Unit = {},
    onSetReminder: () -> Unit = {},
    onToggleEncrypt: () -> Unit = {},
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = note.title.takeIf { it.isNotBlank() },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                QuickNoteActionRow(
                    icon = Icons.Default.Edit,
                    label = stringResource(R.string.quick_notes_edit),
                    onClick = onEdit,
                )
                QuickNoteActionRow(
                    icon = Icons.Default.ContentCopy,
                    label = stringResource(R.string.quick_notes_copy),
                    onClick = onCopy,
                )
                // 设置文件夹
                QuickNoteActionRow(
                    icon = Icons.Outlined.FolderOpen,
                    label = stringResource(R.string.quick_notes_folder_set),
                    onClick = onSetFolder,
                )
                // 设置提醒
                QuickNoteActionRow(
                    icon = Icons.Outlined.Notifications,
                    label = if (note.reminderAt > 0) {
                        stringResource(R.string.quick_notes_reminder_cancel)
                    } else {
                        stringResource(R.string.quick_notes_reminder_set)
                    },
                    onClick = onSetReminder,
                )
                // 加密/解密
                QuickNoteActionRow(
                    icon = Icons.Outlined.Lock,
                    label = if (note.encrypted) {
                        stringResource(R.string.quick_notes_encrypt_off)
                    } else {
                        stringResource(R.string.quick_notes_encrypt_on)
                    },
                    onClick = onToggleEncrypt,
                )
                QuickNoteActionRow(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.quick_notes_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
private fun QuickNoteActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MusePaddings.iconPadding,
                    vertical = MusePaddings.inputPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickNoteDialog(
    existing: QuickNoteEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>, String) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(",") ?: "") }
    var isMarkdown by remember { mutableStateOf(existing?.contentType == "markdown") }
    var previewMode by remember { mutableStateOf(false) }
    val errorRequired = stringResource(R.string.quick_notes_error_required)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (existing == null) R.string.quick_notes_new else R.string.quick_notes_edit_title
        ),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                MuseTextField(
                    value = title,
                    onValueChange = { title = it; errorMessage = null },
                    label = { Text(stringResource(R.string.quick_notes_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Markdown 模式切换 + 预览切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = { isMarkdown = !isMarkdown },
                        shape = MuseShapes.pill,
                        color = if (isMarkdown) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.quick_notes_toggle_markdown),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isMarkdown) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(
                                horizontal = MusePaddings.iconPadding,
                                vertical = MusePaddings.labelVerticalGap,
                            ),
                        )
                    }
                    if (isMarkdown) {
                        Surface(
                            onClick = { previewMode = !previewMode },
                            shape = MuseShapes.pill,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = stringResource(
                                    if (previewMode) R.string.quick_notes_markdown_edit
                                    else R.string.quick_notes_markdown_preview
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(
                                    horizontal = MusePaddings.iconPadding,
                                    vertical = MusePaddings.labelVerticalGap,
                                ),
                            )
                        }
                    }
                }
                // Markdown 预览模式用 MarkdownText 渲染
                if (isMarkdown && previewMode) {
                    Surface(
                        shape = MuseShapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                    ) {
                        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                            MarkdownText(
                                text = content,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else {
                    MuseTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(stringResource(R.string.quick_notes_content_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                    )
                }
                MuseTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.quick_notes_tags_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.quick_notes_save),
        onConfirm = {
            if (title.isBlank()) {
                errorMessage = errorRequired
                return@MuseDialog
            }
            onSave(
                title.trim(),
                content.trim(),
                tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                if (isMarkdown) "markdown" else "plain",
            )
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * 回收站面板 — 列出已删除记录,支持恢复 / 永久删除 / 清空全部。
 */
@Composable
private fun QuickNoteTrashDialog(
    items: List<QuickNoteEntity>,
    onDismiss: () -> Unit,
    onRestore: (QuickNoteEntity) -> Unit,
    onPermanentDelete: (QuickNoteEntity) -> Unit,
    onClearAll: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.quick_notes_trash_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.quick_notes_trash_count, items.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.quick_notes_trash_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        items(items, key = { it.id }) { note ->
                            Surface(
                                shape = MuseShapes.semiLarge,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(MusePaddings.cardInner),
                                ) {
                                    Text(
                                        text = note.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (note.content.isNotBlank()) {
                                        Spacer(Modifier.height(MusePaddings.tightGap))
                                        Text(
                                            text = note.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.height(MusePaddings.auxGap))
                                    Text(
                                        text = fmt.format(Date(note.deletedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Spacer(Modifier.height(MusePaddings.contentGap))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                                    ) {
                                        Surface(
                                            onClick = { onRestore(note) },
                                            shape = MuseShapes.pill,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    horizontal = MusePaddings.iconPadding,
                                                    vertical = MusePaddings.labelVerticalGap,
                                                ),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                )
                                                Spacer(Modifier.size(MusePaddings.tightGap))
                                                Text(
                                                    text = stringResource(R.string.quick_notes_restore),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                        Surface(
                                            onClick = { onPermanentDelete(note) },
                                            shape = MuseShapes.pill,
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    horizontal = MusePaddings.iconPadding,
                                                    vertical = MusePaddings.labelVerticalGap,
                                                ),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                                Spacer(Modifier.size(MusePaddings.tightGap))
                                                Text(
                                                    text = stringResource(R.string.quick_notes_delete_permanent),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        onClick = onClearAll,
                        shape = MuseShapes.semiLarge,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.inputPadding),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                            Spacer(Modifier.size(MusePaddings.tightGap))
                            Text(
                                text = stringResource(R.string.quick_notes_clear_trash),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/** 从文本中提取 #标签,返回不含 # 的标签列表。 */
private fun extractHashTags(text: String): List<String> {
    val regex = Regex("#([^#\\s,，.。!！?？\\n]+)")
    return regex.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

/**
 * 从内容推导标题。
 * 优先取第一行非空文本;若第一行过短或只有标签,则取前 40 个字符。
 */
private fun deriveTitle(text: String, tags: List<String>): String {
    val stripped = text.replace(Regex("#[^#\\s,，.。!！?？\\n]+"), "").trim()
    val firstLine = stripped.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    return when {
        firstLine.length >= 3 -> firstLine.take(40)
        stripped.isNotBlank() -> stripped.take(40)
        tags.isNotEmpty() -> tags.first().take(40)
        else -> text.trim().take(40)
    }
}

// ── 快速记录增强组件 ───────────────────────────────────────────────────────

/**
 * 文件夹筛选条 — FlowRow 胶囊,与标签筛选风格一致。
 * - "全部"(selectedFolder=null): 显示所有文件夹
 * - "未分类"(selectedFolder=""): folder 为空的记录
 * - 具体文件夹名: 该文件夹下的记录
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickNoteFolderFilterRow(
    folders: List<String>,
    selectedFolder: String?,
    onFolderSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        FolderChip(
            label = stringResource(R.string.quick_notes_folder_all),
            selected = selectedFolder == null,
            onClick = { onFolderSelected(null) },
        )
        FolderChip(
            label = stringResource(R.string.quick_notes_folder_uncategorized),
            selected = selectedFolder == "",
            onClick = { onFolderSelected("") },
        )
        folders.forEach { folder ->
            FolderChip(
                label = folder,
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    MuseChip(
        selected = selected,
        onClick = onClick,
        label = label,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        },
    )
}

/**
 * 导出/导入菜单 — 提供 Markdown 导出 / JSON 导出 / JSON 导入 三个入口。
 */
@Composable
private fun QuickNoteExportMenu(
    onDismiss: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExportJson: () -> Unit,
    onImportJson: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.quick_notes_export),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                QuickNoteActionRow(
                    icon = Icons.Default.IosShare,
                    label = stringResource(R.string.quick_notes_export_markdown),
                    onClick = onExportMarkdown,
                )
                QuickNoteActionRow(
                    icon = Icons.Default.IosShare,
                    label = stringResource(R.string.quick_notes_export_json),
                    onClick = onExportJson,
                )
                QuickNoteActionRow(
                    icon = Icons.Outlined.FolderOpen,
                    label = stringResource(R.string.quick_notes_import_json),
                    onClick = onImportJson,
                )
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * 文件夹设置弹窗 — 列出已有文件夹 + 未分类 + 新建文件夹输入框。
 */
@Composable
private fun QuickNoteFolderDialog(
    folders: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var newFolder by remember { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.quick_notes_folder_set),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                QuickNoteActionRow(
                    icon = Icons.Outlined.FolderOpen,
                    label = stringResource(R.string.quick_notes_folder_uncategorized),
                    onClick = { onSelect("") },
                )
                folders.forEach { folder ->
                    QuickNoteActionRow(
                        icon = Icons.Default.Folder,
                        label = folder,
                        onClick = { onSelect(folder) },
                    )
                }
                Spacer(Modifier.height(MusePaddings.contentGap))
                Text(
                    text = stringResource(R.string.quick_notes_folder_new),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    MuseTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        label = { Text(stringResource(R.string.quick_notes_folder_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        onClick = {
                            val name = newFolder.trim()
                            if (name.isNotBlank()) onSelect(name)
                        },
                        shape = MuseShapes.pill,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = stringResource(R.string.quick_notes_save),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(
                                horizontal = MusePaddings.iconPadding,
                                vertical = MusePaddings.inputPadding,
                            ),
                        )
                    }
                }
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * 提醒设置弹窗 — 展示当前提醒状态,点击设置依次弹出日期/时间选择器。
 */
@Composable
private fun QuickNoteReminderDialog(
    currentReminderAt: Long,
    onDismiss: () -> Unit,
    onSet: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }

    fun showDatePicker() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hour)
                        cal.set(Calendar.MINUTE, minute)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        onSet(cal.timeInMillis)
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.quick_notes_reminder),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = if (currentReminderAt > 0) {
                        stringResource(R.string.quick_notes_reminder_scheduled, fmt.format(Date(currentReminderAt)))
                    } else {
                        stringResource(R.string.quick_notes_reminder_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuickNoteActionRow(
                    icon = Icons.Outlined.Notifications,
                    label = stringResource(R.string.quick_notes_reminder_set),
                    onClick = { showDatePicker() },
                )
                if (currentReminderAt > 0) {
                    QuickNoteActionRow(
                        icon = Icons.Default.Clear,
                        label = stringResource(R.string.quick_notes_reminder_cancel),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onCancel,
                    )
                }
            }
        },
        dismissText = stringResource(R.string.quick_notes_cancel),
        onDismiss = onDismiss,
    )
}
