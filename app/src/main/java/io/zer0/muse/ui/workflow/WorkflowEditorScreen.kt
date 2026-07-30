package io.zer0.muse.ui.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseToast
import androidx.compose.foundation.shape.RoundedCornerShape
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Multi-Agent 工作流可视化编排全屏页。
 *
 * 装配关系:
 *  - 顶部:[MuseTopBar](返回 + 标题)
 *  - 中部:[WorkflowCanvas](画布,占满剩余空间)
 *  - 底部工具栏:添加节点 / 保存 / 加载 / 清空 / 导出 JSON / 聚合策略下拉
 *  - 选中节点时:弹出 [MuseBottomSheet] 装载 [WorkflowNodeEditor]
 *  - 长按节点:弹出 AlertDialog 菜单(编辑 / 删除 / 复制)
 *  - 导出 JSON:弹出 AlertDialog 展示 JSON 文本 + 复制按钮
 *
 * @param teamId 关联团队 id(可为空,空时作为独立工作流编辑)
 * @param onBack 返回回调
 *
 * v1.0.19: 此全屏编排页与 [io.zer0.muse.ui.settings.MultiAgentSettingsPage] 中的
 *  TeamEditDialog 内嵌工作流节点编辑功能重复(后者在团队编辑弹窗内直接管理节点列表、
 *  聚合策略、节点依赖)。TeamEditDialog 用户体验更连贯(无需跳转),已标记为首选入口。
 *  本页保留是因为它提供 TeamEditDialog 暂未覆盖的能力(画布拖拽、JSON 导出/导入、
 *  加载/清空),后续如将这些能力迁移至 TeamEditDialog,可整体删除本页及
 *  WorkflowCanvas / WorkflowNodeEditor / WorkflowEditorViewModel。
 */
@Deprecated(
    message = "工作流编排与 MultiAgentSettingsPage.TeamEditDialog 功能重复," +
        "TeamEditDialog 内嵌编辑体验更连贯,请优先使用 TeamEditDialog。" +
        "本页保留画布拖拽/JSON 导出等独有能力,后续迁移后将被移除。",
    level = DeprecationLevel.WARNING,
)
@Composable
fun WorkflowEditorScreen(
    teamId: String,
    onBack: () -> Unit,
    viewModel: WorkflowEditorViewModel = koinViewModel { parametersOf(teamId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewport = rememberWorkflowViewportState()

    // 选中节点 → 自动弹出编辑面板
    var showEditorSheet by remember { mutableStateOf(false) }
    // 长按节点 → 弹出操作菜单
    var menuNodeId by remember { mutableStateOf<String?>(null) }
    // 导出 JSON 弹窗
    var showExportDialog by remember { mutableStateOf(false) }
    var exportedJson by remember { mutableStateOf("") }
    // 清空确认弹窗
    var showClearConfirm by remember { mutableStateOf(false) }

    // 选中节点变化时,打开编辑面板
    LaunchedEffect(state.selectedNodeId) {
        showEditorSheet = state.selectedNodeId != null
    }
    // toast:直接转发到全局 MuseToast 单例,由 MuseToastHost 渲染
    LaunchedEffect(state.toast) {
        state.toast?.let {
            MuseToast.show(it)
            viewModel.consumeToast()
        }
    }

    val selectedNode = state.selectedNodeId?.let { id ->
        state.workflow.nodes.firstOrNull { it.id == id }
    }

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.workflow_editor_title),
                onBack = onBack,
                actions = {
                    // 顶部仅放最常用的两个:添加 + 保存
                    IconButton(onClick = { viewModel.addNode() }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.workflow_cd_add_node))
                    }
                    IconButton(onClick = { viewModel.save() }) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.workflow_cd_save))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ---- 画布 ----
            WorkflowCanvas(
                nodes = state.workflow.nodes,
                positions = state.nodePositions,
                selectedNodeId = state.selectedNodeId,
                viewport = viewport,
                modifier = Modifier.fillMaxSize(),
                onAddNodeAt = { canvasCoord ->
                    // 双击空白添加节点,减去半个节点宽高使落点居中
                    viewModel.addNode(
                        position = Offset(canvasCoord.x - 80f, canvasCoord.y - 44f),
                    )
                },
                onBackgroundTap = { viewModel.selectNode(null) },
                onNodeClick = { id -> viewModel.selectNode(id) },
                onNodeLongClick = { id -> menuNodeId = id },
                onNodeDrag = { id, delta ->
                    // canvas 传的是画布坐标系下的位移增量,转成绝对位置后回写
                    val oldPos = state.nodePositions[id] ?: Offset.Zero
                    viewModel.moveNode(id, oldPos + delta)
                },
                onConnect = { from, to -> viewModel.addDependency(from, to) },
            )

            // ---- 底部工具栏 ----
            WorkflowToolbar(
                aggregationStrategy = state.workflow.aggregationStrategy,
                onAggregationChange = { viewModel.updateAggregationStrategy(it) },
                onAdd = { viewModel.addNode() },
                onSave = { viewModel.save() },
                onLoad = { viewModel.load() },
                onClear = { showClearConfirm = true },
                onExport = {
                    exportedJson = viewModel.exportJson()
                    showExportDialog = true
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // ---- 节点编辑面板(底部 Sheet) ----
    if (showEditorSheet && selectedNode != null) {
        MuseBottomSheet(
            onDismissRequest = {
                showEditorSheet = false
                viewModel.selectNode(null)
            },
        ) {
            WorkflowNodeEditor(
                node = selectedNode,
                assistants = state.assistants,
                allNodes = state.workflow.nodes,
                onUpdate = { viewModel.updateNode(it) },
                onRemoveDependency = { from, to -> viewModel.removeDependency(from, to) },
                onAddDependency = { from, to -> viewModel.addDependency(from, to) },
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }

    // ---- 长按节点:操作菜单 ----
    menuNodeId?.let { nodeId ->
        val nodeName = state.workflow.nodes.firstOrNull { it.id == nodeId }?.name?.ifBlank { nodeId } ?: nodeId
        AlertDialog(
            onDismissRequest = { menuNodeId = null },
            title = { Text(nodeName) },
            text = { Text(stringResource(R.string.workflow_select_action)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        // 编辑:选中并打开面板
                        viewModel.selectNode(nodeId)
                        menuNodeId = null
                    }) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_edit))
                    }
                    TextButton(onClick = {
                        viewModel.duplicateNode(nodeId)
                        menuNodeId = null
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_copy))
                    }
                    TextButton(onClick = {
                        viewModel.removeNode(nodeId)
                        menuNodeId = null
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { menuNodeId = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // ---- 导出 JSON 弹窗 ----
    if (showExportDialog) {
        ExportJsonDialog(
            json = exportedJson,
            onDismiss = { showExportDialog = false },
        )
    }

    // ---- 清空确认弹窗 ----
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.workflow_clear_canvas_title)) },
            text = { Text(stringResource(R.string.workflow_clear_canvas_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    showClearConfirm = false
                }) { Text(stringResource(R.string.workflow_action_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * 底部浮动工具栏(iOS 风格胶囊形):聚合策略 + 添加 / 保存 / 加载 / 清空 / 导出。
 *
 * 视觉:胶囊形([MuseShapes.pill])、悬浮阴影、左右留边距,浮于画布之上。
 * 内容超出可用宽度时内部水平滚动,小屏不溢出。
 */
@Composable
private fun WorkflowToolbar(
    aggregationStrategy: DelegationContract.TeamWorkflow.AggregationStrategy,
    onAggregationChange: (DelegationContract.TeamWorkflow.AggregationStrategy) -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strategyOptions = DelegationContract.TeamWorkflow.AggregationStrategy.entries.map { strategy ->
        strategy to stringResource(aggregationName(strategy))
    }

    // 外层 Box 占满宽度并居中,提供宽度上限;内容少时胶囊 hug 内容并浮动居中,
    // 内容多时胶囊被约束到可用宽度,内部 Row 水平滚动。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 聚合策略下拉
                MuseDropdown(
                    value = aggregationStrategy.name,
                    onValueChange = { name ->
                        DelegationContract.TeamWorkflow.AggregationStrategy.entries
                            .firstOrNull { it.name == name }
                            ?.let(onAggregationChange)
                    },
                    label = stringResource(R.string.workflow_label_aggregation),
                    options = strategyOptions.map { it.first.name to it.second },
                    modifier = Modifier.width(160.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.workflow_cd_add_node)) }
                IconButton(onClick = onSave) { Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.workflow_cd_save)) }
                IconButton(onClick = onLoad) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.workflow_cd_load)) }
                IconButton(onClick = onClear) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.workflow_cd_clear)) }
                IconButton(onClick = onExport) { Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.workflow_cd_export_json)) }
            }
        }
    }
}

/**
 * 导出 JSON 弹窗:展示 JSON 文本 + 复制按钮。
 */
@Composable
private fun ExportJsonDialog(
    json: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workflow_export_json_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) },
        text = {
            Column {
                MuseTextField(
                    value = json,
                    onValueChange = { /* 只读 */ },
                    readOnly = true,
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(minOf(360.dp, 480.dp)),
                )
                if (copied) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.workflow_export_copied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(json))
                copied = true
            }) { Text(stringResource(R.string.action_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** 聚合策略显示名对应的字符串资源 id。 */
private fun aggregationName(strategy: DelegationContract.TeamWorkflow.AggregationStrategy): Int = when (strategy) {
    DelegationContract.TeamWorkflow.AggregationStrategy.MERGE -> R.string.workflow_agg_merge
    DelegationContract.TeamWorkflow.AggregationStrategy.VOTE -> R.string.workflow_agg_vote
    DelegationContract.TeamWorkflow.AggregationStrategy.EXPERT_REVIEW -> R.string.workflow_agg_expert_review
    DelegationContract.TeamWorkflow.AggregationStrategy.FIRST_SUCCESS -> R.string.workflow_agg_first_success
    DelegationContract.TeamWorkflow.AggregationStrategy.LLM_REVIEW -> R.string.workflow_agg_llm_review
}
