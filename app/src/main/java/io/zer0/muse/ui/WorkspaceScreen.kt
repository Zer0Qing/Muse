package io.zer0.muse.ui

import android.content.ClipData
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.common.FileSizeFormatter
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.workspace.WorkspaceManager
import io.zer0.muse.workspace.WorkspaceMount
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-7: 工作区文件管理器 UI。
 *
 * 结构:
 *  - 顶部 [MuseTopBar]:当前路径(根目录显示"工作区",子目录显示当前目录名 + 返回上级)
 *  - 中间 [LazyColumn]:目录在前/文件在后,每条 [WorkspaceManager.WorkspaceEntry]
 *    点击目录进入子目录;点击文件弹 [MuseDialog] 显示内容;
 *    长按弹 [MuseDialog] 操作菜单(删除 / 重命名 / 复制路径)
 *  - 底部 [MuseFloatingButton]:弹出"新建"对话框
 *    (新建文件 / 新建文件夹 二选一)
 *  - 空目录展示 [MuseEmptyState]
 *
 * 路径安全由 [WorkspaceManager.resolveSafe] 严格保证,UI 仅展示其返回结果。
 *
 * @param onBack 返回回调(由 NavHost 注入,点击 TopBar 返回箭头触发)
 */
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    /** v2.0.1: 打开产物中心（顶栏入口）。 */
    onOpenArtifacts: () -> Unit = {},
) {
    val workspaceManager: WorkspaceManager = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widthClass = rememberWindowWidthClass()

    // 当前所在目录的相对路径(空串 = 根目录)
    // 前端修复 (持久化-1): currentPath 改 rememberSaveable,旋转/进程重建不丢当前目录
    var currentPath by rememberSaveable { mutableStateOf("") }
    // 当前目录下的条目列表
    // 前端修复 (持久化-1): WorkspaceEntry 为自定义复杂类型,列表无法直接 saveable,保持 remember(重建后由 reload() 重新加载)
    var entries by remember { mutableStateOf<List<WorkspaceManager.WorkspaceEntry>>(emptyList()) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var isImporting by rememberSaveable { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 弹窗状态
    // 前端修复 (持久化-1): 弹窗目标均为复杂对象类型(WorkspaceEntry?/CreateType?),无法直接 saveable,保持 remember;
    // 弹窗属临时交互态,进程重建后自动关闭可接受
    // 查看文件内容弹窗
    var viewingEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    // v2.0.1: PDF 文档预览目标（pdf.js 全屏渲染）
    var docPreviewEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    var viewingContent by remember { mutableStateOf<String?>(null) }
    var viewingLoading by rememberSaveable { mutableStateOf(false) }
    // 长按操作菜单弹窗
    var menuEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    // 删除确认弹窗
    var deleteEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    // 重命名弹窗
    var renameEntry by remember { mutableStateOf<WorkspaceManager.WorkspaceEntry?>(null) }
    // 新建弹窗(类型:文件 / 文件夹)
    var createDialog by remember { mutableStateOf<CreateType?>(null) }
    // 挂载表与挂载管理弹窗
    var mounts by remember { mutableStateOf<List<WorkspaceMount>>(emptyList()) }
    var mountSheetOpen by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    /**
     * 重新加载当前目录内容。
     * 每次进入子目录 / 增删改后调用,确保 UI 与文件系统一致。
     */
    fun reload() {
        scope.launch {
            isLoading = true
            errorMsg = null
            when (val r = workspaceManager.listDir(currentPath)) {
                is WorkspaceManager.ListResult.Success -> {
                    entries = r.entries
                    errorMsg = null
                }
                is WorkspaceManager.ListResult.Error -> {
                    entries = emptyList()
                    errorMsg = r.message
                }
            }
            isLoading = false
        }
    }

    val importFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 文件选择器返回后固定目标目录,避免用户在复制期间切换目录导致文件落错位置。
            val targetDirectory = currentPath
            scope.launch {
                isImporting = true
                var importedCount = 0
                val failures = mutableListOf<String>()
                uris.forEach { uri ->
                    when (val result = workspaceManager.importFile(uri, targetDirectory)) {
                        is WorkspaceManager.FileImportResult.Success -> importedCount++
                        is WorkspaceManager.FileImportResult.Error -> failures += result.message
                    }
                }
                isImporting = false
                reload()
                if (failures.isEmpty()) {
                    MuseToast.show(context.getString(R.string.workspace_import_success, importedCount))
                } else {
                    MuseToast.show(
                        context.getString(
                            R.string.workspace_import_failed,
                            failures.first().take(120),
                        ),
                    )
                }
            }
        }
    }

    // 挂载表：进入页面时读一次，增删后再刷新。
    LaunchedEffect(Unit) {
        mounts = workspaceManager.mounts()
    }

    val knownMountIds = remember(mounts) { mounts.mapTo(mutableSetOf()) { it.id } }
    val currentMountId = remember(currentPath, knownMountIds) {
        val prefix = currentPath.substringBefore(':', "")
        if (prefix.isNotEmpty() && prefix in knownMountIds) prefix else WorkspaceMount.MAIN_ID
    }
    val currentMount = mounts.firstOrNull { it.id == currentMountId }
    val currentReadOnly = currentMount?.isReadOnly == true

    // 选一个目录作为新挂载：拿持久化授权再登记。
    val mountDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                true
            }.getOrDefault(false)
            val label = mountLabelFromUri(uri)
            val mount = WorkspaceMount(
                id = nextMountId(label, workspaceManager.mounts().mapTo(mutableSetOf()) { it.id }),
                label = label,
                provider = io.zer0.muse.workspace.MountProvider.SAF,
                treeUri = uri.toString(),
                capabilities = if (granted) {
                    WorkspaceMount.DEFAULT_CAPABILITIES
                } else {
                    WorkspaceMount.READ_ONLY_CAPABILITIES
                },
                createdAt = System.currentTimeMillis(),
            )
            when (val r = workspaceManager.upsertMount(mount)) {
                is io.zer0.muse.workspace.WorkspaceMountStore.MountStoreResult.Success -> {
                    mounts = r.mounts
                    MuseToast.show(context.getString(R.string.workspace_mount_added, label))
                }
                is io.zer0.muse.workspace.WorkspaceMountStore.MountStoreResult.Error -> {
                    MuseToast.show(context.getString(R.string.workspace_mount_failed, r.message))
                }
            }
        }
    }

    // 审计修复 (3.1): 进入页面 / 切换目录时重新加载。
    // 加载逻辑直接内联进 LaunchedEffect,currentPath 变化时旧协程会被自动取消,
    // 避免先发的 listDir 晚返回后覆盖新目录的加载结果。
    LaunchedEffect(currentPath) {
        isLoading = true
        errorMsg = null
        when (val r = workspaceManager.listDir(currentPath)) {
            is WorkspaceManager.ListResult.Success -> {
                entries = r.entries
                errorMsg = null
            }
            is WorkspaceManager.ListResult.Error -> {
                entries = emptyList()
                errorMsg = r.message
            }
        }
        isLoading = false
    }

    // 计算当前目录显示名(根目录 = "工作区",挂载根 = 挂载名,子目录 = 最末段名)
    val currentName = when {
        currentPath.isEmpty() -> stringResource(R.string.workspace_title)
        currentPath.endsWith(":/") || currentPath.endsWith(":") ->
            currentMount?.label ?: stringResource(R.string.workspace_title)
        else -> currentPath.substringAfterLast('/')
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = currentName,
                onBack = if (currentPath.isEmpty()) onBack else ({ currentPath = parentPath(currentPath) }),
                largeTitle = true,
                actions = {
                    MuseTactileButton(
                        icon = MuseIcons.folder,
                        onClick = { mountSheetOpen = true },
                        contentDescription = stringResource(R.string.workspace_mounts_title),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        onClick = {
                            runCatching {
                                importFilesLauncher.launch(arrayOf("*/*"))
                            }.onFailure {
                                MuseToast.show(context.getString(R.string.workspace_import_failed, it.message))
                            }
                        },
                        contentDescription = stringResource(R.string.workspace_import_file),
                        enabled = !isImporting && !currentReadOnly,
                    )
                    // v2.0.1: 产物中心入口
                    MuseTactileButton(
                        icon = MuseIcons.fileText,
                        onClick = onOpenArtifacts,
                        contentDescription = stringResource(R.string.artifact_center_title),
                    )
                    // 子目录下额外提供"返回根目录"快捷按钮
                    if (currentPath.isNotEmpty()) {
                        MuseTactileButton(
                            icon = MuseIcons.arrowLeft,
                            onClick = { currentPath = "" },
                            contentDescription = stringResource(R.string.workspace_title),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!currentReadOnly) {
                MuseFloatingButton(
                    icon = MuseIcons.plus,
                    onClick = { createDialog = CreateType.FILE },
                    contentDescription = stringResource(R.string.workspace_new_file),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                isImporting -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MuseSpinner()
                            Spacer(Modifier.size(MusePaddings.contentGap))
                            Text(stringResource(R.string.workspace_import_file))
                        }
                    }
                }
                isLoading && entries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseLoadingState()
                    }
                }
                errorMsg != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseErrorStateBox(
                            message = errorMsg ?: "",
                            onRetry = { reload() },
                        )
                    }
                }
                entries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseEmptyState(
                            icon = MuseIcons.folder,
                            title = stringResource(R.string.workspace_empty),
                            subtitle = stringResource(R.string.workspace_empty_subtitle),
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (widthClass == WindowWidthClass.Expanded) {
                                    Modifier.widthIn(max = 720.dp)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = MusePaddings.screen),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen * 2,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        // 当前目录非根时,首项展示"返回上级"
                        if (currentPath.isNotEmpty()) {
                            item(key = "__parent__") {
                                ParentRow(onClick = { currentPath = parentPath(currentPath) })
                            }
                        }
                        items(entries, key = { it.relativePath }) { entry ->
                            WorkspaceEntryRow(
                                entry = entry,
                                dateFormat = dateFormat,
                                onClick = {
                                    if (entry.isDirectory) {
                                        // 进入子目录
                                        currentPath = entry.relativePath
                                    } else {
                                        // v2.0.1: PDF 走文档预览（pdf.js），其余保持文本弹窗
                                        if (entry.relativePath.endsWith(".pdf", ignoreCase = true)) {
                                            docPreviewEntry = entry
                                        } else {
                                            // 读取文件内容(在弹窗内展示)
                                            viewingEntry = entry
                                            viewingContent = null
                                            viewingLoading = true
                                            scope.launch {
                                                when (val r = workspaceManager.readFile(entry.relativePath)) {
                                                    is WorkspaceManager.ReadResult.Success -> viewingContent = r.content
                                                    is WorkspaceManager.ReadResult.Error -> viewingContent = "Error: ${r.message}"
                                                }
                                                viewingLoading = false
                                            }
                                        }
                                    }
                                },
                                onLongClick = { menuEntry = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    // 文件内容查看弹窗
    viewingEntry?.let { entry ->
        MuseDialog(
            onDismissRequest = {
                viewingEntry = null
                viewingContent = null
            },
            title = entry.name,
            content = {
                // 注:不使用内部 verticalScroll(MuseDialog 已在外层提供滚动容器,
                // 嵌套滚动会触发 IllegalStateException — 见项目记忆)
                when {
                    viewingLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MusePaddings.sectionGap),
                            contentAlignment = Alignment.Center,
                        ) {
                            MuseSpinner(
                                size = MuseIconSizes.iconMedium,
                            )
                        }
                    }
                    viewingContent != null -> {
                        // 长文件内容靠 MuseDialog 外层 verticalScroll 滚动展示
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = viewingContent ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(MusePaddings.contentGap),
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                viewingEntry = null
                viewingContent = null
            },
            dismissText = null,
        )
    }

    // v2.0.1: PDF 文档预览（pdf.js 全屏渲染）
    docPreviewEntry?.let { entry ->
        io.zer0.muse.ui.artifact.DocPreviewDialog(
            fileName = entry.name,
            loadBytes = { workspaceManager.readBytes(entry.relativePath) },
            onDismiss = { docPreviewEntry = null },
        )
    }

    // 长按操作菜单弹窗
    menuEntry?.let { entry ->
        MuseDialog(
            onDismissRequest = { menuEntry = null },
            title = entry.name,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    MenuActionRow(
                        icon = MuseIcons.edit,
                        label = stringResource(R.string.workspace_rename),
                        onClick = {
                            renameEntry = entry
                            menuEntry = null
                        },
                    )
                    MenuActionRow(
                        icon = MuseIcons.copy,
                        label = stringResource(R.string.workspace_path_copied),
                        onClick = {
                            copyToClipboard(context, entry.relativePath)
                            MuseToast.show(context.getString(R.string.workspace_path_copied))
                            menuEntry = null
                        },
                    )
                    MenuActionRow(
                        icon = MuseIcons.trash,
                        label = stringResource(R.string.common_delete),
                        onClick = {
                            deleteEntry = entry
                            menuEntry = null
                        },
                        destructive = true,
                    )
                }
            },
            confirmText = stringResource(R.string.common_cancel),
            onConfirm = { menuEntry = null },
            dismissText = null,
        )
    }

    // 删除确认弹窗
    deleteEntry?.let { entry ->
        MuseDialog(
            onDismissRequest = { deleteEntry = null },
            title = stringResource(R.string.common_delete),
            content = {
                Text(
                    text = stringResource(R.string.workspace_delete_confirm, entry.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                val target = entry
                deleteEntry = null
                scope.launch {
                    when (val r = workspaceManager.delete(target.relativePath)) {
                        is WorkspaceManager.OpResult.Success -> {
                            // 审计修复 (8.10): 原用 common_confirm("确定")文案错误
                            MuseToast.show(context.getString(R.string.chat_list_deleted_toast))
                            reload()
                        }
                        is WorkspaceManager.OpResult.Error -> {
                            MuseToast.show(r.message)
                        }
                    }
                }
            },
            destructive = true,
        )
    }

    // 重命名弹窗
    renameEntry?.let { entry ->
        var newName by remember(entry.relativePath) { mutableStateOf(entry.name) }
        MuseDialog(
            onDismissRequest = { renameEntry = null },
            title = stringResource(R.string.workspace_rename),
            content = {
                MuseTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                val name = newName.trim()
                // 名称为空或包含非法字符(/ 或 ..)时弹提示且不关闭弹窗
                if (name.isEmpty() || name.contains('/') || name.contains("..")) {
                    MuseToast.show(context.getString(R.string.workspace_file_name))
                    return@MuseDialog
                }
                // 名称未变直接关闭弹窗,不执行 IO
                if (name == entry.name) {
                    renameEntry = null
                    return@MuseDialog
                }
                val to = if (entry.relativePath.contains('/')) {
                    entry.relativePath.substringBeforeLast('/') + "/" + name
                } else {
                    name
                }
                val target = entry
                renameEntry = null
                scope.launch {
                    when (val r = workspaceManager.move(target.relativePath, to)) {
                        is WorkspaceManager.OpResult.Success -> reload()
                        is WorkspaceManager.OpResult.Error -> MuseToast.show(r.message)
                    }
                }
            },
        )
    }

    // 新建文件 / 文件夹弹窗
    if (mountSheetOpen) {
        MuseDialog(
            onDismissRequest = { mountSheetOpen = false },
            title = stringResource(R.string.workspace_mounts_title),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    mounts.forEach { mount ->
                        val selected = mount.id == currentMountId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MuseShapes.medium)
                                .clickable {
                                    currentPath = if (mount.id == WorkspaceMount.MAIN_ID) "" else mount.id + ":/"
                                    mountSheetOpen = false
                                }
                                .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.contentGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            Text(
                                text = mount.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (mount.isReadOnly) {
                                Text(
                                    text = stringResource(R.string.workspace_mount_read_only),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!mount.system) {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        scope.launch {
                                            workspaceManager.removeMount(mount.id)
                                            mounts = workspaceManager.mounts()
                                            if (currentMountId == mount.id) currentPath = ""
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.workspace_mount_remove))
                                }
                            }
                        }
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            mountSheetOpen = false
                            runCatching { mountDirectoryLauncher.launch(null) }
                                .onFailure {
                                    MuseToast.show(
                                        context.getString(R.string.workspace_mount_failed, it.message),
                                    )
                                }
                        },
                    ) {
                        Text(stringResource(R.string.workspace_mount_add))
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { mountSheetOpen = false },
        )
    }

    createDialog?.let { type ->
        var newName by remember { mutableStateOf("") }
        val titleRes = when (type) {
            CreateType.FILE -> R.string.workspace_new_file
            CreateType.FOLDER -> R.string.workspace_new_folder
        }
        MuseDialog(
            onDismissRequest = { createDialog = null },
            title = stringResource(titleRes),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    MuseTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = {
                            Text(text = stringResource(R.string.workspace_file_name))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    // 快捷按钮:在新建文件弹窗内提供切换到"新建文件夹"的快捷操作
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        QuickSwitchChip(
                            icon = MuseIcons.file,
                            label = stringResource(R.string.workspace_new_file),
                            selected = type == CreateType.FILE,
                            onClick = { createDialog = CreateType.FILE },
                        )
                        QuickSwitchChip(
                            icon = MuseIcons.folderPlus,
                            label = stringResource(R.string.workspace_new_folder),
                            selected = type == CreateType.FOLDER,
                            onClick = { createDialog = CreateType.FOLDER },
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                val name = newName.trim()
                if (name.isEmpty() || name.contains('/') || name.contains("..")) {
                    MuseToast.show(context.getString(R.string.workspace_file_name))
                    return@MuseDialog
                }
                val target = if (currentPath.isEmpty()) name else "$currentPath/$name"
                val t = type
                createDialog = null
                scope.launch {
                    val result = when (t) {
                        CreateType.FILE -> workspaceManager.writeFile(target, "")
                        CreateType.FOLDER -> workspaceManager.mkdir(target)
                    }
                    when (result) {
                        is WorkspaceManager.OpResult.Success -> reload()
                        is WorkspaceManager.OpResult.Error -> MuseToast.show(result.message)
                    }
                }
            },
        )
    }
}

// ── 内部辅助组件与函数 ──────────────────────────────────────────────────────

/**
 * 新建类型(文件 / 文件夹)。
 */
private enum class CreateType { FILE, FOLDER }

/**
 * 计算父目录的相对路径。根目录的父仍是空串。
 */
private fun parentPath(path: String): String {
    if (path.isEmpty()) return ""
    // 带挂载前缀时只在挂载内部退一级，到挂载根再退就回到默认工作区。
    val prefixEnd = path.indexOf(":/")
    if (prefixEnd >= 0) {
        val rest = path.substring(prefixEnd + 2)
        if (rest.isEmpty()) return ""
        val cut = rest.lastIndexOf('/')
        val prefix = path.substring(0, prefixEnd)
        return if (cut < 0) "$prefix:/" else "$prefix:/${rest.substring(0, cut)}"
    }
    val idx = path.lastIndexOf('/')
    return if (idx < 0) "" else path.substring(0, idx)
}

/**
 * 从授权 URI 里取一个可读的目录名。
 *
 * 目录 URI 的最后一段形如 `primary:DCIM/Camera`，取冒号后最末一级。
 */
private fun mountLabelFromUri(uri: android.net.Uri): String {
    val raw = uri.lastPathSegment?.substringAfterLast(':')?.trim().orEmpty()
    val name = raw.substringAfterLast('/').trim()
    return name.ifEmpty { "Mounted" }
}

/**
 * 由目录名生成一个合法且不重复的挂载标识。
 *
 * 标识只允许小写字母、数字、下划线与连字符，且以字母开头。
 */
private fun nextMountId(label: String, taken: Set<String>): String {
    val base = label.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
        .joinToString("")
        .trim('_')
        .ifEmpty { "mount" }
    val normalized = if (base.first() in 'a'..'z') base else "m$base"
    val stem = normalized.take(24)
    if (stem !in taken) return stem
    var index = 1
    while (true) {
        val candidate = "${stem.take(20)}_$index"
        if (candidate !in taken) return candidate
        index++
    }
}

/**
 * 把文本复制到系统剪贴板(用于"复制路径"操作)。
 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("workspace_path", text))
}

/**
 * "返回上级"行(只在子目录下显示)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParentRow(onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.arrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Spacer(Modifier.size(MusePaddings.contentGap))
            Text(
                text = "..",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 工作区条目行(目录 / 文件)。
 *
 * 点击进入子目录或查看文件内容;长按弹操作菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceEntryRow(
    entry: WorkspaceManager.WorkspaceEntry,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 类型图标(目录/文件)放在圆形背景中,提升视觉层次
            val icon = if (entry.isDirectory) MuseIcons.folder else MuseIcons.fileText
            val iconTint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                shape = CircleShape,
                color = iconTint.copy(alpha = 0.12f),
                modifier = Modifier.size(MuseIconSizes.touchTarget),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
            Spacer(Modifier.size(MusePaddings.contentGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(MusePaddings.tightGap))
                Text(
                    text = if (entry.isDirectory) {
                        dateFormat.format(Date(entry.lastModified))
                    } else {
                        "${formatSize(entry.size)} · ${dateFormat.format(Date(entry.lastModified))}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 文件类型额外显示编辑图标,提示可查看
            if (!entry.isDirectory) {
                MuseTactileButton(
                    icon = MuseIcons.edit,
                    onClick = onClick,
                    contentDescription = stringResource(R.string.action_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 长按菜单中的单个操作行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Spacer(Modifier.size(MusePaddings.contentGap))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 新建弹窗内的"文件/文件夹"快捷切换胶囊。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickSwitchChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.pill,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        modifier = Modifier
            .combinedClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MusePaddings.contentGap),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Spacer(Modifier.size(MusePaddings.tightGap))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 字节数格式化为人类可读字符串(B/KB/MB)。
 */
private fun formatSize(bytes: Long): String = FileSizeFormatter.format(bytes)
