package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.workspace.WorkspaceManager

/**
 * 当前层的标题：根显示「工作区」；目录显示末段名；
 * 停在挂载根时 `path` 形如 `docs:`（尾斜杠被下钻栈吃掉），直接显示它就成了机器标识，
 * 所以这里换成挂载标签 —— 这是用户唯一能认出"我在哪个工作区"的地方。
 */
internal fun workspacePickTitle(path: String, mountLabels: Map<String, String>, rootLabel: String): String {
    if (path.isEmpty()) return rootLabel
    val slash = path.lastIndexOf('/')
    if (slash < 0) {
        val mountId = path.removeSuffix(":")
        return mountLabels[mountId] ?: mountId
    }
    return path.substring(slash + 1).ifEmpty { mountLabels[path.substringBefore(':')] ?: path }
}

/** 可下钻的条目：只要文件夹，且跳过 `.` 开头的隐藏目录（不是给用户挑的）。 */
internal fun workspacePickFolders(entries: List<WorkspaceManager.WorkspaceEntry>): List<WorkspaceManager.WorkspaceEntry> =
    entries.filter { it.isDirectory && !it.name.startsWith(".") }

/**
 * 「更多 → 工作区」的下钻选择器。
 *
 * 第一层就是各个工作区：`listDir("")` 会把其它挂载一并作为目录列出，
 * 所以这里不再另写一份挂载列表（本项目反复出的问题就是抄第二份）。
 */
@Composable
internal fun WorkspaceQuickPickDialog(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val manager: WorkspaceManager = org.koin.compose.koinInject()
    val stack = remember { WorkspacePickStack() }
    var path by remember { mutableStateOf(WorkspacePickStack.ROOT) }
    var entries by remember { mutableStateOf<List<WorkspaceManager.WorkspaceEntry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var mountLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(path) {
        entries = null
        if (mountLabels.isEmpty()) {
            // 只为把 `docs:` 这种挂载根显示成人类看得懂的名字（标签表取一次就够）
            mountLabels = manager.mounts().associate { it.id to it.label }
        }
        when (val result = manager.listDir(path)) {
            is WorkspaceManager.ListResult.Success -> {
                entries = workspacePickFolders(result.entries)
                failure = null
            }

            is WorkspaceManager.ListResult.Error -> {
                entries = emptyList()
                failure = result.message
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsItemRow(
                    icon = MuseIcons.arrowLeft,
                    title = workspacePickTitle(path, mountLabels, stringResource(R.string.workspace_title)),
                    subtitle = stringResource(R.string.workspace_pick_hint),
                    enabled = stack.canGoBack,
                    onClick = {
                        if (stack.back()) path = stack.current
                    },
                )
                val folders = entries.orEmpty()
                if (folders.isEmpty()) {
                    SettingsItemRow(
                        title = stringResource(
                            if (failure == null) R.string.workspace_pick_empty else R.string.workspace_pick_failed,
                        ),
                        subtitle = failure,
                        enabled = false,
                    )
                }
                folders.forEach { entry ->
                    SettingsItemRow(
                        icon = MuseIcons.folder,
                        // 与 WorkspaceScreen 条目行同一份内置目录映射，选择器与打开页叫法一致。
                        title = io.zer0.muse.ui.workspaceDirectoryLabelRes(entry.name)
                            ?.let { stringResource(it) } ?: entry.name,
                        onClick = {
                            stack.enter(WorkspacePickStack.normalize(entry.relativePath))
                            path = stack.current
                        },
                    )
                }
                SettingsItemRow(
                    icon = MuseIcons.folderOpen,
                    title = stringResource(R.string.workspace_pick_open_here),
                    onClick = { onOpen(path) },
                )
                SettingsItemRow(
                    title = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                )
            }
        }
    }
}
