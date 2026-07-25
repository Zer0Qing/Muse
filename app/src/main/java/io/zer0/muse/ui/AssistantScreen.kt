package io.zer0.muse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import io.zer0.muse.ui.common.MuseDialog
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow  // v1.48 (h21): 名称/预览省略号
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantCardExporter
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.CharacterCardImporter
import kotlin.uuid.Uuid
import io.zer0.muse.ui.common.AssistantAvatar
import io.zer0.muse.ui.common.ChevronRight
import io.zer0.muse.ui.common.ConfirmDeleteDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.components.CardGroup
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 助手列表页架构。
 *
 * LargeTopAppBar + CardGroup(头像 + 操作菜单)。
 * 点击助手卡片跳转详情聚合页(ASSISTANT_DETAIL/{id})。
 * 操作菜单: 克隆 / 删除(默认助手不可删除)。
 * 创建/删除/克隆走 AssistantRepository + rememberCoroutineScope(即时保存)。
 */
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenMemory: () -> Unit = {},
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val repo: AssistantRepository = koinInject()
    val scope = rememberCoroutineScope()
    var actionSheetAssistant by remember { mutableStateOf<AssistantEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<AssistantEntity?>(null) }
    val context = LocalContext.current
    // 导出目标:点击"导出角色卡"时暂存,SAF 回调时取出
    var exportTarget by remember { mutableStateOf<AssistantEntity?>(null) }

    // i18n: 预提取字符串资源,避免在 onClick/ifBlank/协程 等非 Composable lambda 内调用 stringResource
    val screenTitle = stringResource(R.string.assistant_screen_title)
    val backCd = stringResource(R.string.assistant_back_cd)
    val importCardCd = stringResource(R.string.assistant_import_card_cd)
    val memoryEntryTitle = stringResource(R.string.assistant_memory_entry_title)
    val memoryEntrySubtitle = stringResource(R.string.assistant_memory_entry_subtitle)
    val emptyCreateText = stringResource(R.string.assistant_empty_create)
    val unnamedText = stringResource(R.string.assistant_unnamed)
    val currentText = stringResource(R.string.assistant_current)
    val moreCd = stringResource(R.string.assistant_more_cd)
    val newActionText = stringResource(R.string.assistant_new_action)
    val moveUpCd = stringResource(R.string.assistant_move_up_cd)
    val moveUpText = stringResource(R.string.assistant_move_up)
    val moveDownCd = stringResource(R.string.assistant_move_down_cd)
    val moveDownText = stringResource(R.string.assistant_move_down)
    val cloneCd = stringResource(R.string.assistant_clone_cd)
    val cloneText = stringResource(R.string.assistant_clone)
    val exportCardCd = stringResource(R.string.assistant_export_card_cd)
    val exportCardText = stringResource(R.string.assistant_export_card)
    val deleteCd = stringResource(R.string.assistant_delete_cd)
    val deleteText = stringResource(R.string.assistant_delete)
    val closeText = stringResource(R.string.assistant_close)
    val deleteDialogTitle = stringResource(R.string.assistant_delete_title)
    // SillyTavern 角色卡 (PNG/JSON) 导入
    val importSillyTavernCd = stringResource(R.string.assistant_import_sillytavern_cd)
    val importSillyTavernText = stringResource(R.string.assistant_import_sillytavern)
    val toastSillyTavernImported = stringResource(R.string.assistant_toast_sillytavern_imported)
    val toastSillyTavernImportFailed = stringResource(R.string.assistant_toast_sillytavern_import_failed)

    // 导出角色卡 launcher(SAF CreateDocument,.muse-assistant 文件)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            scope.launch {
                runCatching { AssistantCardExporter.export(context, target, uri) }
                    .onSuccess { MuseToast.show(context.getString(R.string.assistant_toast_exported, target.name)) }
                    .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_export_failed, it.message ?: ""), 3500) }
            }
        }
    }

    // 导入角色卡 launcher(SAF OpenDocument)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching { AssistantCardExporter.import(context, repo, it) }
                    .onSuccess { imported ->
                        if (imported != null) {
                            // 列表由 repo.observeAll Flow 自动刷新
                            MuseToast.show(context.getString(R.string.assistant_toast_imported, imported.name))
                        } else {
                            MuseToast.show(context.getString(R.string.assistant_toast_import_invalid), 3500)
                        }
                    }
                    .onFailure { MuseToast.show(context.getString(R.string.assistant_toast_import_failed, it.message ?: ""), 3500) }
            }
        }
    }

    // 导入 SillyTavern 角色卡 launcher(SAF OpenDocument, PNG/JSON 通用)
    // SillyTavern 卡是业内通用格式: PNG tEXt chunk (key="chara") 存 base64 JSON, 或纯 JSON 文件
    val importSillyTavernLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                CharacterCardImporter.importAuto(context, it)
                    .onSuccess { entity ->
                        // 生成新 id + 时间戳, upsert 到 DB (列表由 repo.observeAll Flow 自动刷新)
                        val now = System.currentTimeMillis()
                        val saved = entity.copy(
                            id = Uuid.random().toString(),
                            createdAt = now,
                            updatedAt = now,
                        )
                        repo.upsert(saved)
                        MuseToast.show(toastSillyTavernImported.format(saved.name))
                    }
                    .onFailure { MuseToast.show(toastSillyTavernImportFailed.format(it.message ?: ""), 3500) }
            }
        }
    }

    // 创建新助手: 插入新实体后跳转详情页
    fun createNewAssistant() {
        val now = System.currentTimeMillis()
        val newId = "assistant-$now"
        scope.launch {
            repo.upsert(AssistantEntity(id = newId, name = context.getString(R.string.assistant_new_default_name), createdAt = now, updatedAt = now))
            onOpenDetail(newId)
        }
    }

    // 克隆助手: 复制当前实体,改 id / name / 时间戳
    fun cloneAssistant(assistant: AssistantEntity) {
        val now = System.currentTimeMillis()
        val cloneId = "assistant-$now-clone"
        scope.launch {
            repo.upsert(
                assistant.copy(
                    id = cloneId,
                    name = context.getString(R.string.assistant_clone_name, assistant.name),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = screenTitle,
                onBack = onBack,
                largeTitle = true,
                actions = {
                    // 导入角色卡入口
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = importCardCd)
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 记忆入口
            item {
                CardGroup {
                    item(
                        onClick = onOpenMemory,
                        leadingContent = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
                        headlineContent = { Text(memoryEntryTitle) },
                        supportingContent = { Text(memoryEntrySubtitle) },
                        trailingContent = { ChevronRight() },
                    )
                }
            }
            // 助手列表
            // v1.72: 首次加载时显示 loading,避免闪"尚未创建助手"空状态
            if (state.isAssistantsLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item {
                    CardGroup {
                    if (state.assistants.isEmpty()) {
                        item(
                            onClick = { createNewAssistant() },
                            leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                            headlineContent = { Text(emptyCreateText) },
                        )
                    } else {
                        state.assistants.forEach { assistant ->
                            item(
                                key = assistant.id,
                                onClick = { onOpenDetail(assistant.id) },
                                leadingContent = {
                                    AssistantAvatar(assistant = assistant, avatarSize = 40.dp)
                                },
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = assistant.name.ifBlank { unnamedText },
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            // v1.48 (h21): 名称单行 + 省略号,防止长名撑破布局
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (assistant.id == state.currentAssistant?.id) {
                                            Spacer(Modifier.size(6.dp))
                                            Text(
                                                currentText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                },
                                supportingContent = if (assistant.systemPrompt.isNotEmpty()) {
                                    {
                                        Text(
                                            text = assistant.systemPrompt.take(40).replace("\n", " "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            // v1.48 (h21): 预览补 ellipsis(原仅 maxLines=1,无省略号会硬截断)
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else null,
                                trailingContent = {
                                    IconButton(onClick = { actionSheetAssistant = assistant }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = moreCd)
                                    }
                                },
                            )
                        }
                        // 新建助手入口
                        item(
                            onClick = { createNewAssistant() },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = {
                                Text(
                                    newActionText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                        )
                        // 导入 SillyTavern 角色卡 (PNG/JSON) 入口
                        // SillyTavern 卡是业内通用格式, 支持导入其他 App 创建的角色卡
                        item(
                            onClick = {
                                runCatching {
                                    importSillyTavernLauncher.launch(arrayOf("image/png", "application/json", "*/*"))
                                }.onFailure {
                                    MuseToast.show(toastSillyTavernImportFailed.format(it.message ?: ""), 3500)
                                }
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Image,
                                    contentDescription = importSillyTavernCd,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = {
                                Text(
                                    importSillyTavernText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                        )
                    }
                    }
                }
            }
        }
    }

    // 操作菜单(MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死)
    actionSheetAssistant?.let { assistant ->
        MuseDialog(
            onDismissRequest = { actionSheetAssistant = null },
            title = assistant.name.ifBlank { unnamedText },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 头部: 头像
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantAvatar(assistant = assistant, avatarSize = 40.dp)
                    }
                    Spacer(Modifier.size(12.dp))
                    // v1.66: 上移/下移(通过 sortIndex 接线实现自定义排序)
                    val currentIndex = state.assistants.indexOf(assistant)
                    val canMoveUp = currentIndex > 0
                    val canMoveDown = currentIndex >= 0 && currentIndex < state.assistants.size - 1
                    if (canMoveUp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        // 交换相邻两个助手的 sortIndex(用列表索引确保唯一递增)
                                        val upper = state.assistants[currentIndex - 1]
                                        repo.upsert(assistant.copy(sortIndex = currentIndex - 1, updatedAt = System.currentTimeMillis()))
                                        repo.upsert(upper.copy(sortIndex = currentIndex, updatedAt = System.currentTimeMillis()))
                                    }
                                    actionSheetAssistant = null
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = moveUpCd,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(moveUpText, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (canMoveDown) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        // 交换相邻两个助手的 sortIndex(用列表索引确保唯一递增)
                                        val lower = state.assistants[currentIndex + 1]
                                        repo.upsert(assistant.copy(sortIndex = currentIndex + 1, updatedAt = System.currentTimeMillis()))
                                        repo.upsert(lower.copy(sortIndex = currentIndex, updatedAt = System.currentTimeMillis()))
                                    }
                                    actionSheetAssistant = null
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = moveDownCd,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(moveDownText, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    // 克隆
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                cloneAssistant(assistant)
                                actionSheetAssistant = null
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = cloneCd,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(cloneText, style = MaterialTheme.typography.bodyLarge)
                    }
                    // 导出角色卡
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                exportTarget = assistant
                                actionSheetAssistant = null
                                // 文件名去掉非法字符,SAF CreateDocument 用作建议名
                                val safeName = assistant.name
                                    .ifBlank { "assistant" }
                                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                exportLauncher.launch("$safeName.muse-assistant")
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = exportCardCd,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(exportCardText, style = MaterialTheme.typography.bodyLarge)
                    }
                    // 删除(默认助手不显示)
                    if (assistant.id != "default") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    deleteTarget = assistant
                                    actionSheetAssistant = null
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = deleteCd,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                deleteText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            onConfirm = null,
            dismissText = closeText,
            onDismiss = { actionSheetAssistant = null },
        )
    }

    // 删除确认对话框
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = deleteDialogTitle,
            itemName = target.name,
            onConfirm = {
                scope.launch { repo.delete(target.id) }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}
