package io.zer0.muse.ui.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import io.zer0.muse.ui.common.ConfirmDeleteDialog
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.rememberWindowWidthClass
import io.zer0.muse.ui.common.WindowWidthClass
import io.zer0.muse.ui.settings.SettingField
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * v1.133: 知识库管理页 — 创建/重命名/删除 KB,查看文档数,一键重索引。
 *
 * 设计:
 *  - IosTopBar + LazyColumn(iOS Large Title 风格)
 *  - KB 卡片显示名称/描述/文档数,右侧编辑/删除/重索引按钮
 *  - 默认 KB(id="default")不可删除,只可编辑名称/描述
 *  - "重新索引全部文档"按钮调 [RagService.reindexAllInKbs],进度对话框实时显示
 */
@Composable
fun KnowledgeBaseManagePage(
    onBack: () -> Unit,
    kbDao: KnowledgeBaseDao = koinInject(),
    docDao: KnowledgeDocDao = koinInject(),
    ragService: RagService = koinInject(),
    settings: SettingsRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val kbs by kbDao.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val widthClass = rememberWindowWidthClass()

    var editing by remember { mutableStateOf<KnowledgeBaseEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KnowledgeBaseEntity?>(null) }
    var reindexing by remember { mutableStateOf<KnowledgeBaseEntity?>(null) }
    var reindexProgress by remember { mutableStateOf(0 to 0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            IosTopBar(
                title = stringResource(R.string.kb_manage_page_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    // v1.133: 一键重索引全部 KB(后台 Worker 执行)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable {
                                io.zer0.muse.rag.ReindexAllWorker.enqueue(context)
                                MuseToast.show(context.getString(R.string.kb_reindex_all))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.kb_reindex_all),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = MusePaddings.screen),
                contentAlignment = Alignment.TopCenter,
            ) {
                val list = kbs
                if (list == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (list.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Folder,
                        title = stringResource(R.string.kb_manage_empty),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (widthClass == WindowWidthClass.Expanded) {
                                    Modifier.widthIn(max = 720.dp)
                                } else {
                                    Modifier
                                }
                            ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            bottom = MusePaddings.screen + 80.dp,
                        ),
                    ) {
                        items(list, key = { it.id }) { kb ->
                            KbRow(
                                kb = kb,
                                onEdit = { editing = kb },
                                onDelete = { deleting = kb },
                                onReindex = {
                                    reindexing = kb
                                    reindexProgress = 0 to 0
                                    scope.launch {
                                        val config = runCatching { settings.getRagConfig() }
                                            .getOrElse { RagConfig() }
                                        val failures = withContext(Dispatchers.IO) {
                                            ragService.reindexAllInKbs(
                                                kbIds = listOf(kb.id),
                                                ragConfig = config,
                                                onProgress = { cur, total ->
                                                    reindexProgress = cur to total
                                                },
                                            )
                                        }
                                        val total = reindexProgress.second
                                        reindexing = null
                                        if (failures.isEmpty()) {
                                            MuseToast.show(
                                                context.getString(R.string.kb_reindex_done, total),
                                            )
                                        } else {
                                            MuseToast.show(
                                                context.getString(
                                                    R.string.kb_reindex_failed,
                                                    failures.entries.joinToString { "${it.key}: ${it.value}" },
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 右下角 FAB — 新建 KB
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = MusePaddings.screen, bottom = MusePaddings.screen)
                .size(56.dp)
                .clickable { creating = true },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.kb_manage_create),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }

    // 新建 KB 弹窗
    if (creating) {
        KbEditDialog(
            title = stringResource(R.string.kb_manage_create),
            initialName = "",
            initialDesc = "",
            onConfirm = { name, desc ->
                scope.launch {
                    if (name.isBlank()) {
                        MuseToast.show(context.getString(R.string.kb_manage_name_empty))
                        return@launch
                    }
                    val now = System.currentTimeMillis()
                    val id = "kb-" + now
                    withContext(Dispatchers.IO) {
                        kbDao.upsert(
                            KnowledgeBaseEntity(
                                id = id,
                                name = name,
                                description = desc,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    }
                    creating = false
                    MuseToast.show(context.getString(R.string.kb_manage_created, name))
                }
            },
            onDismiss = { creating = false },
        )
    }

    // 编辑 KB 弹窗
    editing?.let { kb ->
        KbEditDialog(
            title = stringResource(R.string.kb_manage_edit),
            initialName = kb.name,
            initialDesc = kb.description,
            onConfirm = { name, desc ->
                scope.launch {
                    if (name.isBlank()) {
                        MuseToast.show(context.getString(R.string.kb_manage_name_empty))
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        kbDao.upsert(kb.copy(name = name, description = desc, updatedAt = System.currentTimeMillis()))
                    }
                    editing = null
                    MuseToast.show(context.getString(R.string.kb_manage_updated, name))
                }
            },
            onDismiss = { editing = null },
        )
    }

    // 删除 KB 确认弹窗
    deleting?.let { kb ->
        if (kb.id == "default") {
            MuseToast.show(context.getString(R.string.kb_manage_default_no_delete))
            deleting = null
        } else {
            ConfirmDeleteDialog(
                title = stringResource(R.string.kb_manage_delete),
                itemName = stringResource(R.string.kb_manage_delete_confirm, kb.name),
                onConfirm = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            docDao.getByKbIds(listOf(kb.id)).forEach { doc ->
                                docDao.deleteDocWithChunks(doc.id)
                            }
                            kbDao.delete(kb.id)
                        }
                        deleting = null
                        MuseToast.show(context.getString(R.string.kb_manage_deleted, kb.name))
                    }
                },
                onDismiss = { deleting = null },
            )
        }
    }

    // 重新索引进度对话框
    if (reindexing != null) {
        val (current, total) = reindexProgress
        MuseDialog(
            onDismissRequest = {
                // 不允许用户取消(避免半成品索引)
            },
            title = stringResource(R.string.kb_reindex_all),
            content = {
                Column {
                    Text(
                        if (total > 0) {
                            stringResource(R.string.kb_reindex_in_progress, current, total)
                        } else {
                            stringResource(R.string.kb_reindex_all)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    LinearProgress(
                        progress = if (total > 0) current.toFloat() / total else 0f,
                    )
                }
            },
            onConfirm = null,
        )
    }
}

/**
 * KB 列表项 — 显示名称、描述、文档数;右侧编辑/删除/重索引按钮。
 */
@Composable
private fun KbRow(
    kb: KnowledgeBaseEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReindex: () -> Unit,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(MusePaddings.contentGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    kb.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (kb.description.isNotBlank()) {
                    Text(
                        kb.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.kb_manage_doc_count, kb.docCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            KbActionIcon(
                icon = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.kb_reindex_all),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onReindex,
            )
            KbActionIcon(
                icon = Icons.Default.Edit,
                contentDescription = stringResource(R.string.kb_manage_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onEdit,
            )
            if (kb.id != "default") {
                KbActionIcon(
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.kb_manage_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** KB 行内图标按钮(40dp 触摸目标 + 8dp 内边距)。 */
@Composable
private fun KbActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * KB 编辑/新建弹窗 — 名称 + 描述两字段表单。
 */
@Composable
private fun KbEditDialog(
    title: String,
    initialName: String,
    initialDesc: String,
    onConfirm: (name: String, desc: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                SettingField(
                    label = stringResource(R.string.kb_manage_name_label),
                    value = name,
                    onValueChange = { name = it },
                )
                SettingField(
                    label = stringResource(R.string.kb_manage_desc_label),
                    value = desc,
                    onValueChange = { desc = it },
                )
            }
        },
        onConfirm = { onConfirm(name, desc) },
        onDismiss = onDismiss,
    )
}

/**
 * 简化线性进度条(避免引入 Material3 LinearProgressIndicator 的实验 API)。
 */
@Composable
private fun LinearProgress(progress: Float) {
    val clamped = progress.coerceIn(0f, 1f)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(clamped)) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(),
            ) {}
        }
    }
}
