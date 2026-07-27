@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package io.zer0.muse.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.ui.ModelSwitchSheet
import io.zer0.muse.ui.common.AssistantAvatar
import io.zer0.muse.ui.common.ConfirmDeleteDialog
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.IosDropdown
import io.zer0.muse.ui.common.IosSlider
import io.zer0.muse.ui.common.IosSwitch
import io.zer0.muse.ui.common.IosTextField
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.UUID

/**
 * v1.25: 多 Agent 协作团队管理页。
 *
 * 功能:
 *  - 总开关:启用/禁用多 Agent 协作
 *  - 团队列表:展示名称、描述、成员头像(最多 3 个,+N)
 *  - 点击团队编辑,长按/点击删除图标删除(需确认)
 *  - 底部「新建团队」胶囊按钮
 */
@Composable
fun MultiAgentSettingsPage(
    onBack: () -> Unit,
    onOpenWorkflowEditor: (teamId: String) -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val config by settings.multiAgentConfigFlow.collectAsStateWithLifecycle(initialValue = MultiAgentConfig())
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    var editingTeam by remember { mutableStateOf<AgentTeam?>(null) }
    var teamToDelete by remember { mutableStateOf<AgentTeam?>(null) }
    var showReviewModelSheet by remember { mutableStateOf(false) }
    val unnamedTeam = stringResource(R.string.settings_multi_agent_unnamed_team)

    fun updateConfig(block: (MultiAgentConfig) -> MultiAgentConfig) {
        // M3: 用原子更新方法,避免读-改-写竞态
        scope.launch { settings.updateMultiAgentConfig(block) }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_multi_agent_title), onBack = onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.Users,
                    title = stringResource(R.string.settings_multi_agent_enable),
                    subtitle = stringResource(R.string.settings_multi_agent_enable_subtitle),
                    checked = config.enabled,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(enabled = v) }
                    },
                )
            }
        }

        // v1.201: LLM 综合评审全局设置区
        item { SectionLabel(stringResource(R.string.multi_agent_llm_review_enabled)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.User,
                    title = stringResource(R.string.multi_agent_llm_review_enabled),
                    subtitle = stringResource(R.string.multi_agent_llm_review_desc),
                    checked = config.llmReviewEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveLlmReviewEnabled(v) }
                    },
                )
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = TablerIcons.User,
                    title = stringResource(R.string.multi_agent_llm_review_model),
                    subtitle = resolveReviewModelName(
                        reviewModelId = config.reviewModelId,
                        providers = providers,
                        activeProviderId = activeProviderId,
                    ),
                    onClick = { showReviewModelSheet = true },
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_multi_agent_team_list)) }

        if (config.teams.isEmpty()) {
            // v1.48: h14 团队列表空态改用 EmptyState 组件
            item {
                EmptyState(
                    icon = TablerIcons.Users,
                    title = stringResource(R.string.settings_multi_agent_no_team),
                    subtitle = stringResource(R.string.settings_multi_agent_no_team_hint),
                    actionText = stringResource(R.string.settings_multi_agent_new_team),
                    onAction = { editingTeam = AgentTeam(id = "") },
                )
            }
        } else {
            items(
                items = config.teams,
                key = { it.id },
            ) { team ->
                TeamCard(
                    team = team,
                    assistants = assistants ?: emptyList(),
                    onClick = { editingTeam = team },
                    onDelete = { teamToDelete = team },
                    onOpenWorkflowEditor = { onOpenWorkflowEditor(team.id) },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MuseShapes.pill)
                    .clickable { editingTeam = AgentTeam(id = "") },
                color = MaterialTheme.colorScheme.primary,
                shape = MuseShapes.pill,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = TablerIcons.Plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_multi_agent_new_team),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // v1.201: 人机协作暂停策略
        item { SectionLabel(stringResource(R.string.delegation_pause_section_title)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.delegation_pause_before_team),
                    checked = config.pausePolicy.pauseBeforeTeam,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(pauseBeforeTeam = v)) }
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.delegation_pause_before_member),
                    checked = config.pausePolicy.pauseBeforeEachMember,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(pauseBeforeEachMember = v)) }
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.delegation_pause_intermediate),
                    checked = config.pausePolicy.pauseOnIntermediateResult,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(pauseOnIntermediateResult = v)) }
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.delegation_pause_high_risk),
                    checked = config.pausePolicy.pauseOnHighRisk,
                    onCheckedChange = { v ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(pauseOnHighRisk = v)) }
                    },
                )
                DelegationTimeoutSliderRow(
                    timeoutSec = config.pausePolicy.autoTimeoutSec,
                    onValueChange = { newSec ->
                        updateConfig { it.copy(pausePolicy = it.pausePolicy.copy(autoTimeoutSec = newSec)) }
                    },
                )
            }
        }
    }

    editingTeam?.let { team ->
        TeamEditDialog(
            team = team,
            assistants = assistants ?: emptyList(),
            onDismiss = { editingTeam = null },
            onSave = { saved ->
                val now = System.currentTimeMillis()
                val isNew = saved.id.isBlank()
                val finalTeam = if (isNew) {
                    saved.copy(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
                } else {
                    saved.copy(updatedAt = now)
                }
                updateConfig {
                    if (isNew) {
                        it.copy(teams = it.teams + finalTeam)
                    } else {
                        it.copy(teams = it.teams.map { t -> if (t.id == finalTeam.id) finalTeam else t })
                    }
                }
                editingTeam = null
            },
        )
    }

    teamToDelete?.let { team ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.settings_multi_agent_delete_team),
            itemName = team.name.ifBlank { unnamedTeam },
            onConfirm = {
                updateConfig {
                    it.copy(
                        teams = it.teams.filter { t -> t.id != team.id },
                        defaultTeamId = if (it.defaultTeamId == team.id) null else it.defaultTeamId,
                    )
                }
                teamToDelete = null
            },
            onDismiss = { teamToDelete = null },
        )
    }

    // v1.201: LLM 评审模型选择面板
    // 设计说明:评审模型属于"全局设置",不允许在此切换激活 Provider(避免副作用),
    // 仅允许用户在当前激活 Provider 的模型列表中挑选;若需使用其他 Provider 的模型,
    // 用户应先到 Provider 设置切换激活 Provider,再回到此页选择模型。
    // LlmAggregator.resolveReviewModel() 会在所有 Provider 中按 id 查找,跨 Provider 切换不影响解析。
    if (showReviewModelSheet) {
        ModelSwitchSheet(
            providers = providers,
            activeProviderId = activeProviderId,
            selectedModelId = config.reviewModelId,
            onPickProvider = { /* 评审模型不切换全局激活 Provider,空实现避免副作用 */ },
            onPickModel = { modelId ->
                if (!modelId.isNullOrBlank()) {
                    scope.launch { settings.saveReviewModelId(modelId) }
                }
                showReviewModelSheet = false
            },
            onRefreshModels = { /* 评审模型选择不提供手动刷新,依赖全局 Provider 配置页 */ },
            isFetchingModels = false,
            fetchModelsError = null,
            onDismiss = { showReviewModelSheet = false },
        )
    }
}

/**
 * v1.201: 解析评审模型在 UI 上的展示名称。
 *
 * 规则:
 *  - reviewModelId 为空 → "默认模型"(回退到 Provider 首个模型)
 *  - 在所有 Provider 中按 id 查找 → 返回模型 name(若 name 为空回退到 id)
 *  - 找不到匹配模型 → "未知模型(<id>)"(可能用户切换了 Provider 或删除了该模型)
 *
 * 注意:此函数只在 UI 层做名称展示,LlmAggregator.resolveReviewModel() 在调用时会做
 * 同样的查找并降级处理,二者查找逻辑保持一致。
 */
private fun resolveReviewModelName(
    reviewModelId: String?,
    providers: List<ProviderConfig>,
    activeProviderId: String?,
): String {
    if (reviewModelId.isNullOrBlank()) return "默认模型"
    // 优先在激活 Provider 中查找(最常见情况)
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    activeProvider?.models?.firstOrNull { it.id == reviewModelId }?.let { model ->
        return model.name.ifBlank { model.id }
    }
    // 其次在所有 Provider 中查找(用户可能切换了激活 Provider 但 reviewModelId 仍指向旧 Provider 的模型)
    for (provider in providers) {
        val matched = provider.models.firstOrNull { it.id == reviewModelId }
        if (matched != null) {
            return matched.name.ifBlank { matched.id }
        }
    }
    return "未知模型($reviewModelId)"
}

/**
 * 团队卡片:名称 + 描述 + 成员头像(最多 3 个,+N) + 删除按钮。
 */
@Composable
private fun TeamCard(
    team: AgentTeam,
    assistants: List<AssistantEntity>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onOpenWorkflowEditor: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val unnamedTeam = stringResource(R.string.settings_multi_agent_unnamed_team)
    val members = remember(team.memberIds, assistants) {
        team.memberIds.mapNotNull { id -> assistants.find { it.id == id } }
    }

    Card(
        shape = MuseShapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    MuseHaptics.medium(haptic)
                    onDelete()
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = team.name.ifBlank { unnamedTeam },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (team.description.isNotBlank()) {
                    Text(
                        text = team.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (members.isNotEmpty()) {
                    MemberAvatarRow(members = members)
                } else {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_no_members),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                }
            }
            // 工作流编排入口:点击进入可视化编排画布
            IconButton(onClick = onOpenWorkflowEditor) {
                Icon(
                    imageVector = TablerIcons.Users,
                    contentDescription = "工作流编排",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = TablerIcons.Trash,
                    contentDescription = stringResource(R.string.settings_multi_agent_delete),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 成员头像行:最多显示前 3 个头像,其余用 +N 圆形徽标。
 */
@Composable
private fun MemberAvatarRow(
    members: List<AssistantEntity>,
) {
    val shown = members.take(3)
    val overflow = members.size - shown.size

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-8).dp),
    ) {
        shown.forEach { assistant ->
            AssistantAvatar(
                assistant = assistant,
                avatarSize = 28.dp,
                modifier = Modifier.border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
            )
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 团队创建/编辑弹窗:名称、描述、成员多选、工作流编排。
 *
 * v1.0.19: 此弹窗内嵌的工作流节点编辑(聚合策略 / 节点列表 / 节点增删改 / 依赖管理)
 *  为工作流编排的首选入口,用户体验连贯(无需跳转全屏页)。
 *  旧的全屏 [io.zer0.muse.ui.workflow.WorkflowEditorScreen] 已标记 @Deprecated,
 *  保留仅因其提供画布拖拽 / JSON 导出等独有能力,后续迁移后将被移除。
 */
@Composable
private fun TeamEditDialog(
    team: AgentTeam,
    assistants: List<AssistantEntity>,
    onDismiss: () -> Unit,
    onSave: (AgentTeam) -> Unit,
) {
    var name by remember(team.id) { mutableStateOf(team.name) }
    var description by remember(team.id) { mutableStateOf(team.description) }
    var selectedIds by remember(team.id) { mutableStateOf(team.memberIds.toSet()) }
    var workflow by remember(team.id) { mutableStateOf(team.workflow ?: DelegationContract.TeamWorkflow()) }
    var editingNode by remember { mutableStateOf<DelegationContract.TeamWorkflowNode?>(null) }
    var nameError by remember { mutableStateOf(false) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = if (team.id.isBlank()) stringResource(R.string.settings_multi_agent_new_team) else stringResource(R.string.settings_multi_agent_edit_team),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IosTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.settings_multi_agent_team_name)) },
                    placeholder = { Text(stringResource(R.string.settings_multi_agent_team_name_hint)) },
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text(stringResource(R.string.settings_multi_agent_team_name_empty)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                IosTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.settings_multi_agent_description)) },
                    placeholder = { Text(stringResource(R.string.settings_multi_agent_description_hint)) },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_multi_agent_select_members),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (assistants.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_no_assistants),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        assistants.forEach { assistant ->
                            val selected = assistant.id in selectedIds
                            IosChip(
                                selected = selected,
                                onClick = {
                                    selectedIds = if (selected) {
                                        selectedIds - assistant.id
                                    } else {
                                        selectedIds + assistant.id
                                    }
                                },
                                label = assistant.name,
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = TablerIcons.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }

                // 工作流编排
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_multi_agent_workflow),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_multi_agent_workflow_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Text(
                    text = stringResource(R.string.settings_multi_agent_aggregation),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DelegationContract.TeamWorkflow.AggregationStrategy.entries.forEach { strategy ->
                        val selected = workflow.aggregationStrategy == strategy
                        IosChip(
                            selected = selected,
                            onClick = { workflow = workflow.copy(aggregationStrategy = strategy) },
                            label = aggregationName(strategy),
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = TablerIcons.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.settings_multi_agent_add_node),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (workflow.nodes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_no_workflow),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        workflow.nodes.forEach { node ->
                            WorkflowNodeRow(
                                node = node,
                                assistants = assistants,
                                onEdit = { editingNode = node },
                                onDelete = {
                                    workflow = workflow.copy(
                                        nodes = workflow.nodes.filter { it.id != node.id },
                                    )
                                },
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        editingNode = DelegationContract.TeamWorkflowNode(
                            id = "",
                            assistantId = "",
                        )
                    },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Icon(
                        imageVector = TablerIcons.Plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_multi_agent_add_node))
                }
            }
        },
        confirmText = stringResource(R.string.settings_multi_agent_save),
        onConfirm = {
            if (name.isBlank()) {
                nameError = true
                return@MuseDialog
            }
            onSave(
                team.copy(
                    name = name.trim(),
                    description = description.trim(),
                    memberIds = assistants.filter { it.id in selectedIds }.map { it.id },
                    workflow = workflow,
                )
            )
        },
        dismissText = stringResource(R.string.settings_multi_agent_cancel),
        onDismiss = onDismiss,
    )

    editingNode?.let { node ->
        WorkflowNodeEditDialog(
            node = node,
            assistants = assistants,
            existingNodeIds = workflow.nodes.map { it.id }.filter { it.isNotBlank() && it != node.id },
            onDismiss = { editingNode = null },
            onSave = { saved ->
                val finalNode = if (saved.id.isBlank()) {
                    saved.copy(id = "node-${UUID.randomUUID()}")
                } else {
                    saved
                }
                workflow = workflow.copy(
                    nodes = if (node.id.isBlank()) {
                        workflow.nodes + finalNode
                    } else {
                        workflow.nodes.map { if (it.id == node.id) finalNode else it }
                    },
                )
                editingNode = null
            },
        )
    }
}

@Composable
private fun WorkflowNodeRow(
    node: DelegationContract.TeamWorkflowNode,
    assistants: List<AssistantEntity>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val assistant = remember(node.assistantId, assistants) {
        assistants.find { it.id == node.assistantId }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = MuseShapes.small,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(width = 32.dp, height = 28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = modeShortName(node.mode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = node.name.ifBlank { stringResource(R.string.settings_multi_agent_unnamed_team) },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = assistant?.name ?: node.assistantId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = TablerIcons.Edit,
                contentDescription = stringResource(R.string.settings_multi_agent_edit_node),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = TablerIcons.Trash,
                contentDescription = stringResource(R.string.settings_multi_agent_delete),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun WorkflowNodeEditDialog(
    node: DelegationContract.TeamWorkflowNode,
    assistants: List<AssistantEntity>,
    existingNodeIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (DelegationContract.TeamWorkflowNode) -> Unit,
) {
    var name by remember(node.id) { mutableStateOf(node.name) }
    var taskTemplate by remember(node.id) { mutableStateOf(node.taskTemplate) }
    var assistantId by remember(node.id) { mutableStateOf(node.assistantId) }
    var mode by remember(node.id) { mutableStateOf(node.mode) }
    var dependencies by remember(node.id) { mutableStateOf(node.dependsOn.toSet()) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_multi_agent_edit_node),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IosTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_multi_agent_node_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IosTextField(
                    value = taskTemplate,
                    onValueChange = { taskTemplate = it },
                    label = { Text(stringResource(R.string.settings_multi_agent_node_task)) },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                IosDropdown(
                    value = assistantId,
                    onValueChange = { assistantId = it },
                    label = stringResource(R.string.settings_multi_agent_node_assistant),
                    options = assistants.map { it.id to it.name },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.settings_multi_agent_node_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DelegationContract.TeamWorkflowNode.Mode.entries.forEach { entry ->
                        val selected = mode == entry
                        IosChip(
                            selected = selected,
                            onClick = { mode = entry },
                            label = modeName(entry),
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = TablerIcons.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }

                if (existingNodeIds.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_multi_agent_node_dependencies),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        existingNodeIds.forEach { id ->
                            val selected = id in dependencies
                            IosChip(
                                selected = selected,
                                onClick = {
                                    dependencies = if (selected) {
                                        dependencies - id
                                    } else {
                                        dependencies + id
                                    }
                                },
                                label = id,
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = TablerIcons.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.action_save),
        onConfirm = {
            onSave(
                node.copy(
                    name = name.trim(),
                    taskTemplate = taskTemplate.trim(),
                    assistantId = assistantId,
                    mode = mode,
                    dependsOn = dependencies.toList(),
                )
            )
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

private fun aggregationName(strategy: DelegationContract.TeamWorkflow.AggregationStrategy): String = when (strategy) {
    DelegationContract.TeamWorkflow.AggregationStrategy.MERGE -> "合并"
    DelegationContract.TeamWorkflow.AggregationStrategy.VOTE -> "投票"
    DelegationContract.TeamWorkflow.AggregationStrategy.EXPERT_REVIEW -> "专家评审"
    DelegationContract.TeamWorkflow.AggregationStrategy.FIRST_SUCCESS -> "首个成功"
    DelegationContract.TeamWorkflow.AggregationStrategy.LLM_REVIEW -> "LLM 综合评审"
}

private fun modeName(mode: DelegationContract.TeamWorkflowNode.Mode): String = when (mode) {
    DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL -> "串行"
    DelegationContract.TeamWorkflowNode.Mode.PARALLEL -> "并行"
    DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL -> "条件"
}

private fun modeShortName(mode: DelegationContract.TeamWorkflowNode.Mode): String = when (mode) {
    DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL -> "串"
    DelegationContract.TeamWorkflowNode.Mode.PARALLEL -> "并"
    DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL -> "条"
}

/**
 * v1.201: 委派暂停超时滑块行(标题 + 当前值 + IosSlider)。
 *
 * 取值范围 60-600 秒,步进 30,共 19 个离散点(steps = 17)。
 * 与 [SettingsSwitchRow] 同样的 padding,使其在 [SettingsGroup] 内视觉对齐。
 */
@Composable
private fun DelegationTimeoutSliderRow(
    timeoutSec: Int,
    onValueChange: (Int) -> Unit,
) {
    val secUnit = stringResource(R.string.delegation_pause_sec)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.delegation_pause_timeout),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$timeoutSec $secUnit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        IosSlider(
            value = timeoutSec.toFloat(),
            onValueChange = { v -> onValueChange(v.toInt()) },
            valueRange = 60f..600f,
            steps = 17,  // 60, 90, ..., 600 共 19 个取值,steps = 19 - 2 = 17
            showValueLabel = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
