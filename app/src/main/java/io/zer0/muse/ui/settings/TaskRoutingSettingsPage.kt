package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.data.routing.UtilityTier
import io.zer0.muse.ui.ModelSwitchSheet
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.x: 辅助模型设置页(原"任务路由"页重构)。
 *
 * 三档模型统一收敛后台辅助任务的模型选择:
 *  - 小工具模型: 标题生成、轻量分类、对话工具轮、封面 prompt、渠道摘要等短任务;
 *  - 大工具模型: 上下文压缩、记忆提取、活动摘要、任务拆解(子代理)等需要理解力的场景;
 *  - 视觉辅助模型: 图片理解 / PDF OCR,点击进入独立页配置(模型选择 + 探针测试)。
 *
 * 级联规则: 大工具留空 → 复用小工具;小工具留空 → 回退主对话模型。
 * 绑定均带 Provider,精确命中所选渠道(修复跨渠道同 id 串台的历史缺陷)。
 *
 * 说明: 原"按对话内容自动分流主模型"的任务分流功能已按用户要求整体移除
 * (安卓端场景过重、使用门槛高);TaskRoutingConfig 存储结构保留以兼容历史数据。
 */
@Composable
fun TaskRoutingSettingsPage(
    onBack: () -> Unit,
    /** v2.x: 打开视觉辅助独立页(辅助模型区块的视觉入口)。 */
    onOpenVision: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    // v2.x: 全局默认模型(主对话/Agent 共用)
    val selectedModelId by settings.selectedModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val currentModelName = remember(providers, activeProviderId, selectedModelId) {
        val provider = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
        val model = provider?.models?.firstOrNull { it.id == selectedModelId } ?: provider?.models?.firstOrNull()
        model?.name ?: "—"
    }
    var showMainModelPicker by remember { mutableStateOf(false) }
    // v2.x: 辅助模型(小工具/大工具)绑定状态 + 视觉模型展示
    val utilityBinding by settings.utilityModelBindingFlow.collectAsStateWithLifecycle(initialValue = null)
    val utilityLargeBinding by settings.utilityLargeModelBindingFlow.collectAsStateWithLifecycle(initialValue = null)
    val visionModelId by settings.visionModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val visionProviderId by settings.visionProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var editingProviderId by remember { mutableStateOf<String?>(null) }
    // v2.x: 辅助模型编辑态(null=无;否则正在编辑的档位)
    var editingUtilityTier by remember { mutableStateOf<UtilityTier?>(null) }
    // v2.x: 辅助模型显示名(绑定带 provider 时优先精确命中)
    val utilitySmallLabel = remember(utilityBinding, providers) {
        utilityBinding?.let { b ->
            providers.firstOrNull { it.id == b.providerId }?.models?.firstOrNull { it.id == b.modelId }?.name
                ?: providers.flatMap { it.models }.firstOrNull { it.id == b.modelId }?.name
        }
    }
    val utilityLargeLabel = remember(utilityLargeBinding, providers) {
        utilityLargeBinding?.let { b ->
            providers.firstOrNull { it.id == b.providerId }?.models?.firstOrNull { it.id == b.modelId }?.name
                ?: providers.flatMap { it.models }.firstOrNull { it.id == b.modelId }?.name
        }
    }
    val visionLabel = remember(visionModelId, visionProviderId, providers) {
        if (visionModelId.isNullOrBlank()) {
            null
        } else {
            providers.firstOrNull { it.id == visionProviderId }?.models?.firstOrNull { it.id == visionModelId }?.name
                ?: providers.flatMap { it.models }.firstOrNull { it.id == visionModelId }?.name
        }
    }
    // v2.x: 自定义压缩提示词(自记忆页归位)
    val customCompressPrompt by settings.customCompressPromptFlow.collectAsStateWithLifecycle(initialValue = null)
    // 默认深度思考：主对话是否默认开推理
    val chatPrefs by settings.chatPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = ChatPreferences())
    var showCompressPromptDialog by remember { mutableStateOf(false) }
    var compressPromptDraft by remember(customCompressPrompt) { mutableStateOf(customCompressPrompt.orEmpty()) }
    val utilitySmallInheritText = stringResource(R.string.settings_agent_tool_model_not_set_inherit)
    val utilityLargeInheritText = stringResource(R.string.settings_agent_utility_large_not_set_inherit)

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_task_routing_aux_section),
        onBack = onBack,
    ) {
        // v2.x: 分组拆分 — 「主对话模型」与「辅助模型」各自独立 label(消除混层歧义)
        item { SectionLabel(stringResource(R.string.settings_task_routing_main_models_label)) }
        item {
            SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
                // 主对话模型(全局默认) — 主聊天/Agent 共用的主模型
                SettingsItemRow(
                    icon = MuseIcons.chat,
                    title = stringResource(R.string.settings_agent_current_model),
                    subtitle = currentModelName + " · " + stringResource(R.string.settings_agent_current_model_hint),
                    onClick = { showMainModelPicker = true },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                // 默认深度思考：免去每次新会话都要按按钮
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_default_deep_thinking),
                    subtitle = stringResource(R.string.settings_chat_default_deep_thinking_subtitle),
                    checked = chatPrefs.defaultDeepThinking,
                    onCheckedChange = { v ->
                        scope.launch { settings.updateChatPreferences { it.copy(defaultDeepThinking = v) } }
                    },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_task_routing_aux_models_label)) }
        item {
            SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
                // 小工具模型
                SettingsItemRow(
                    icon = MuseIcons.wrench,
                    title = stringResource(R.string.settings_agent_tool_model_title),
                    subtitle = stringResource(R.string.settings_task_routing_utility_small_desc),
                    onClick = {
                        editingProviderId = utilityBinding?.providerId ?: activeProviderId
                        editingUtilityTier = UtilityTier.SMALL
                    },
                ) {
                    ModelValue(
                        text = utilitySmallLabel ?: utilitySmallInheritText,
                        bound = utilityBinding != null,
                    )
                }
                SettingsGroupDivider()
                // 大工具模型
                SettingsItemRow(
                    icon = MuseIcons.bolt,
                    title = stringResource(R.string.settings_agent_subagent_model_title),
                    subtitle = stringResource(R.string.settings_task_routing_utility_large_desc),
                    onClick = {
                        editingProviderId = utilityLargeBinding?.providerId ?: activeProviderId
                        editingUtilityTier = UtilityTier.LARGE
                    },
                ) {
                    ModelValue(
                        text = utilityLargeLabel ?: utilityLargeInheritText,
                        bound = utilityLargeBinding != null,
                    )
                }
                SettingsGroupDivider()
                // 压缩模型：跟随主对话默认模型，不单独配置
                SettingsItemRow(
                    icon = MuseIcons.gauge,
                    title = stringResource(R.string.settings_chat_compress_model),
                    subtitle = stringResource(R.string.settings_chat_compress_model_default),
                    onClick = null,
                )
                SettingsGroupDivider()
                // v2.x: 自定义压缩提示词(自记忆页归位;上下文压缩归大工具职责)
                SettingsItemRow(
                    icon = MuseIcons.edit,
                    title = stringResource(R.string.settings_memory_custom_compress_prompt),
                    subtitle =
                    customCompressPrompt?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_memory_custom_compress_prompt_default),
                    onClick = {
                        compressPromptDraft = customCompressPrompt.orEmpty()
                        showCompressPromptDialog = true
                    },
                ) { ChevronRight() }
                SettingsGroupDivider()
                // 视觉辅助模型(跳独立页配置)
                SettingsItemRow(
                    icon = MuseIcons.eye,
                    title = stringResource(R.string.settings_vision_title),
                    subtitle = visionLabel ?: stringResource(R.string.settings_task_routing_vision_unset),
                    onClick = onOpenVision,
                ) {
                    ChevronRight()
                }
            }
        }
    }

    // v2.x: 全局默认模型选择弹窗(按 Provider 分组,点击即切 Provider 并选模型)
    if (showMainModelPicker) {
        MainModelPickerDialog(
            providers = providers,
            activeProviderId = activeProviderId,
            selectedModelId = selectedModelId,
            onPick = { providerId, modelId ->
                scope.launch {
                    settings.setActiveProvider(providerId)
                    settings.saveSelectedModel(modelId)
                }
                showMainModelPicker = false
            },
            onDismiss = { showMainModelPicker = false },
        )
    }

    // v2.x: 自定义压缩提示词弹窗(自记忆页归位)
    if (showCompressPromptDialog) {
        MuseDialog(
            onDismissRequest = { showCompressPromptDialog = false },
            title = stringResource(R.string.settings_memory_custom_compress_prompt),
            content = {
                MuseTextField(
                    value = compressPromptDraft,
                    onValueChange = { compressPromptDraft = it },
                    label = { Text(stringResource(R.string.settings_memory_custom_compress_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch { settings.saveCustomCompressPrompt(compressPromptDraft.trim().takeIf { it.isNotBlank() }) }
                showCompressPromptDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showCompressPromptDialog = false },
        )
    }

    // v2.x: 辅助模型编辑弹窗(小工具/大工具共用,ModelSwitchSheet 带 Provider 选择)
    editingUtilityTier?.let { tier ->
        val currentBinding = when (tier) {
            UtilityTier.SMALL -> utilityBinding
            UtilityTier.LARGE -> utilityLargeBinding
            else -> null
        }
        ModelSwitchSheet(
            providers = providers,
            activeProviderId = editingProviderId ?: activeProviderId,
            selectedModelId = currentBinding?.modelId,
            onPickProvider = { providerId -> editingProviderId = providerId },
            onPickModel = { modelId ->
                val providerId = editingProviderId ?: activeProviderId
                val binding = if (modelId != null && providerId != null) {
                    UtilityModelBinding(providerId = providerId, modelId = modelId)
                } else {
                    null
                }
                scope.launch { saveUtilityBinding(settings, tier, binding) }
                editingUtilityTier = null
                editingProviderId = null
            },
            // 辅助模型需要"显式绑定"与"未绑定(级联)"可区分;
            // 普通聊天的 ModelSwitchSheet 仍保留原来的清除绑定语义。
            onPickDefaultModel = {
                val providerId = editingProviderId ?: activeProviderId
                val defaultModelId = providers.firstOrNull { it.id == providerId }
                    ?.models?.firstOrNull()?.id
                val binding = if (providerId != null && defaultModelId != null) {
                    UtilityModelBinding(providerId = providerId, modelId = defaultModelId)
                } else {
                    null
                }
                scope.launch { saveUtilityBinding(settings, tier, binding) }
                editingUtilityTier = null
                editingProviderId = null
            },
            onRefreshModels = { /* 模型列表由 Provider 设置页维护 */ },
            isFetchingModels = false,
            fetchModelsError = null,
            onDismiss = {
                editingUtilityTier = null
                editingProviderId = null
            },
        )
    }
}

/** v2.x: 按档位保存辅助模型绑定(null=清除绑定,走级联)。 */
private suspend fun saveUtilityBinding(settings: SettingsRepository, tier: UtilityTier, binding: UtilityModelBinding?) {
    when (tier) {
        UtilityTier.SMALL -> settings.saveUtilityModelBinding(binding)
        UtilityTier.LARGE -> settings.saveUtilityLargeModelBinding(binding)
        else -> Unit
    }
}

/**
 * v2.x: 全局默认模型选择弹窗 — 按 Provider 分组列出全部模型,
 * 点击即切换激活 Provider 并设置全局默认模型(主对话/Agent 共用)。
 */
@Composable
private fun MainModelPickerDialog(
    providers: List<io.zer0.ai.core.ProviderConfig>,
    activeProviderId: String?,
    selectedModelId: String?,
    onPick: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_agent_select_model),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                providers.forEach { provider ->
                    if (provider.models.isNotEmpty()) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        provider.models.forEach { model ->
                            val isSelected = provider.id == activeProviderId && model.id == selectedModelId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(provider.id, model.id) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = model.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    // LAYOUT-01: Row 内文本取宽 + 限行，防放大字号下被右侧勾选图标挤成一列
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = MuseIcons.check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (providers.isEmpty() || providers.all { it.models.isEmpty() }) {
                    Text(
                        text = stringResource(R.string.settings_agent_no_models_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/** 尾部模型取值 — 右对齐单行值 + 标准箭头。
 *
 * v2.x: 替换原“胶囊内含长句 + 内嵌箭头”样式 — 长文本会挤压标题列,且内嵌箭头与行点击语义重复。
 * 已绑定 = 正常色;未绑定 = 弱化色("沿用/复用"提示);超长省略号。
 */
@Composable
private fun ModelValue(text: String, bound: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (bound) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        Spacer(Modifier.width(4.dp))
        ChevronRight()
    }
}
