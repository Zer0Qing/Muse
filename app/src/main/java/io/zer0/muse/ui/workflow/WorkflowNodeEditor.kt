package io.zer0.muse.ui.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.ui.common.IosDropdown
import io.zer0.muse.ui.common.SegmentedControl

/**
 * 工作流节点编辑面板 Composable。
 *
 * 用于编辑单个 [DelegationContract.TeamWorkflowNode] 的字段:
 *  - [assistantId](下拉选择,数据源为 [assistants])
 *  - [name](单行文本)
 *  - [taskTemplate](多行文本)
 *  - [mode](SEQUENTIAL / PARALLEL,SegmentedControl;CONDITIONAL 暂不在 UI 暴露)
 *  - [dependsOn](展示当前依赖列表,每项可勾选移除)
 *
 * 设计为底部 Sheet / 侧栏内容,由 [WorkflowEditorScreen] 装载。
 *
 * @param node 当前编辑的节点(已选中)
 * @param assistants 可选助手列表(下拉数据源)
 * @param allNodes 全部节点(用于 dependsOn 列表展示 + 添加)
 * @param onUpdate 节点字段更新回调
 * @param onRemoveDependency 移除依赖:(fromId, toId=当前节点 id)
 * @param onAddDependency 添加依赖:(fromId, toId=当前节点 id)
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WorkflowNodeEditor(
    node: DelegationContract.TeamWorkflowNode,
    assistants: List<AssistantEntity>,
    allNodes: List<DelegationContract.TeamWorkflowNode>,
    onUpdate: (DelegationContract.TeamWorkflowNode) -> Unit,
    onRemoveDependency: (String, String) -> Unit,
    onAddDependency: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 本地缓存可编辑字段,失焦/变化时通过 onUpdate 回写
    var name by remember(node.id) { mutableStateOf(node.name) }
    var taskTemplate by remember(node.id) { mutableStateOf(node.taskTemplate) }

    // 助手下拉选项:value = assistantId,displayText = 助手名
    val unboundAssistantLabel = stringResource(R.string.workflow_node_unbound_assistant)
    val assistantOptions = remember(assistants, unboundAssistantLabel) {
        listOf("" to unboundAssistantLabel) + assistants.map { it.id to it.name }
    }
    // 模式选项:仅暴露 SEQUENTIAL / PARALLEL(CONDITIONAL 由高级 JSON 编辑)
    val modeOptions = remember {
        listOf(DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL, DelegationContract.TeamWorkflowNode.Mode.PARALLEL)
    }
    val modeLabels = listOf(
        stringResource(R.string.workflow_mode_sequential),
        stringResource(R.string.workflow_mode_parallel),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 标题:节点 id + mode ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.workflow_node_editor_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "ID: ${node.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // ---- 节点名称 ----
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                onUpdate(node.copy(name = it))
            },
            label = { Text(stringResource(R.string.workflow_node_name_label)) },
            placeholder = { Text(stringResource(R.string.workflow_node_name_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- 助手绑定(下拉) ----
        IosDropdown(
            value = node.assistantId,
            onValueChange = { selectedId ->
                onUpdate(node.copy(assistantId = selectedId))
            },
            label = stringResource(R.string.workflow_node_bind_assistant),
            options = assistantOptions,
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- 任务模板(多行) ----
        OutlinedTextField(
            value = taskTemplate,
            onValueChange = {
                taskTemplate = it
                onUpdate(node.copy(taskTemplate = it))
            },
            label = { Text(stringResource(R.string.workflow_node_task_template_label)) },
            placeholder = { Text(stringResource(R.string.workflow_node_task_template_placeholder)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
        )

        // ---- 执行模式(SegmentedControl) ----
        Text(
            text = stringResource(R.string.workflow_node_mode_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        SegmentedControl(
            options = modeLabels,
            selectedIndex = if (node.mode == DelegationContract.TeamWorkflowNode.Mode.PARALLEL) 1 else 0,
            onSelectedChange = { idx ->
                onUpdate(node.copy(mode = modeOptions[idx]))
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- 依赖列表(dependsOn) ----
        Text(
            text = stringResource(R.string.workflow_node_dependencies_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        // 当前已有的依赖:可点击移除
        if (node.dependsOn.isEmpty()) {
            Text(
                text = stringResource(R.string.workflow_node_no_dependencies_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                node.dependsOn.forEach { depId ->
                    val depNode = allNodes.firstOrNull { it.id == depId }
                    IosChip(
                        selected = false,
                        onClick = { onRemoveDependency(depId, node.id) },
                        label = depNode?.name?.ifBlank { depId } ?: depId,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.workflow_cd_remove_dependency),
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
        }

        // ---- 添加依赖:列出可选的其它节点(排除自身与已依赖) ----
        val candidates = allNodes.filter { it.id != node.id && it.id !in node.dependsOn }
        if (candidates.isNotEmpty()) {
            Text(
                text = stringResource(R.string.workflow_node_add_dependency_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                candidates.forEach { candidate ->
                    IosChip(
                        selected = false,
                        onClick = { onAddDependency(candidate.id, node.id) },
                        label = candidate.name.ifBlank { candidate.id },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}
