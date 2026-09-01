package io.zer0.muse.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SettingsRepository.TaskRoutingConfig
import io.zer0.muse.data.SettingsRepository.TaskType
import io.zer0.muse.ui.ModelSwitchSheet
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * B0-04: 任务模型路由设置页。
 *
 * 开启后发送消息时会根据输入内容自动检测任务类型，并切换到对应的绑定模型。
 * 每个任务类型单独绑定一个模型，未绑定则沿用当前主模型。
 */
@Composable
fun TaskRoutingSettingsPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val config by settings.taskRoutingConfigFlow.collectAsStateWithLifecycle(
        initialValue = TaskRoutingConfig(),
    )
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var editingType by remember { mutableStateOf<TaskType?>(null) }
    var editingProviderId by remember { mutableStateOf<String?>(null) }
    val defaultModelText = stringResource(R.string.settings_task_routing_default_model)

    fun update(block: (TaskRoutingConfig) -> TaskRoutingConfig) {
        scope.launch { settings.saveTaskRoutingConfig(block(config)) }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_task_routing_title),
        onBack = onBack,
    ) {
        item { SectionLabel(stringResource(R.string.settings_task_routing_general)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = TablerIcons.Adjustments,
                    title = stringResource(R.string.settings_task_routing_enable),
                    subtitle = stringResource(R.string.settings_task_routing_enable_subtitle),
                    checked = config.enabled,
                    onCheckedChange = { enabled ->
                        update { it.copy(enabled = enabled) }
                    },
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_task_routing_models)) }
        item {
            SettingsGroup {
                TaskType.values().forEachIndexed { index, type ->
                    if (index > 0) SettingsGroupDivider()
                    SettingsItemRow(
                        icon = taskTypeIcon(type),
                        title = stringResource(taskTypeTitleRes(type)),
                        subtitle = taskTypeModelLabel(
                            type = type,
                            config = config,
                            providers = providers,
                            defaultText = defaultModelText,
                        ),
                        onClick = {
                            editingType = type
                            editingProviderId = config.providerIdFor(type)
                                ?: providers.firstOrNull { provider ->
                                    provider.models.any { it.id == config.modelIdFor(type) }
                                }?.id
                                ?: activeProviderId
                        },
                    ) {
                        ChevronRight()
                    }
                }
            }
        }
    }

    editingType?.let { type ->
        ModelSwitchSheet(
            providers = providers,
            activeProviderId = editingProviderId ?: activeProviderId,
            selectedModelId = config.modelIdFor(type),
            onPickProvider = { providerId -> editingProviderId = providerId },
            onPickModel = { modelId ->
                update {
                    it.withRoute(
                        type = type,
                        modelId = modelId,
                        providerId = editingProviderId.takeIf { modelId != null },
                    )
                }
                editingType = null
                editingProviderId = null
            },
            // 任务路由需要“显式绑定 Provider 首个模型”和“未绑定”可区分；
            // 普通聊天的 ModelSwitchSheet 仍保留原来的清除绑定语义。
            onPickDefaultModel = {
                val providerId = editingProviderId ?: activeProviderId
                val defaultModelId = providers.firstOrNull { it.id == providerId }
                    ?.models?.firstOrNull()?.id
                update {
                    it.withRoute(
                        type = type,
                        modelId = defaultModelId,
                        providerId = providerId.takeIf { defaultModelId != null },
                    )
                }
                editingType = null
                editingProviderId = null
            },
            onRefreshModels = { /* 模型列表由 Provider 设置页维护 */ },
            isFetchingModels = false,
            fetchModelsError = null,
            onDismiss = {
                editingType = null
                editingProviderId = null
            },
        )
    }
}

private fun taskTypeTitleRes(type: TaskType): Int = when (type) {
    TaskType.CHAT -> R.string.settings_task_routing_chat
    TaskType.REASONING -> R.string.settings_task_routing_reasoning
    TaskType.CODE -> R.string.settings_task_routing_code
    TaskType.CREATIVE -> R.string.settings_task_routing_creative
    TaskType.ANALYSIS -> R.string.settings_task_routing_analysis
}

private fun taskTypeIcon(type: TaskType): ImageVector = when (type) {
    TaskType.CHAT -> TablerIcons.MessageCircle
    TaskType.REASONING -> TablerIcons.Bulb
    TaskType.CODE -> TablerIcons.Code
    TaskType.CREATIVE -> TablerIcons.Stars
    TaskType.ANALYSIS -> TablerIcons.ChartBar
}

private fun TaskRoutingConfig.modelIdFor(type: TaskType): String? = when (type) {
    TaskType.CHAT -> chatModelId
    TaskType.REASONING -> reasoningModelId
    TaskType.CODE -> codeModelId
    TaskType.CREATIVE -> creativeModelId
    TaskType.ANALYSIS -> analysisModelId
}

private fun TaskRoutingConfig.providerIdFor(type: TaskType): String? = when (type) {
    TaskType.CHAT -> chatProviderId
    TaskType.REASONING -> reasoningProviderId
    TaskType.CODE -> codeProviderId
    TaskType.CREATIVE -> creativeProviderId
    TaskType.ANALYSIS -> analysisProviderId
}

private fun TaskRoutingConfig.withRoute(
    type: TaskType,
    modelId: String?,
    providerId: String?,
): TaskRoutingConfig = when (type) {
    TaskType.CHAT -> copy(chatModelId = modelId, chatProviderId = providerId)
    TaskType.REASONING -> copy(reasoningModelId = modelId, reasoningProviderId = providerId)
    TaskType.CODE -> copy(codeModelId = modelId, codeProviderId = providerId)
    TaskType.CREATIVE -> copy(creativeModelId = modelId, creativeProviderId = providerId)
    TaskType.ANALYSIS -> copy(analysisModelId = modelId, analysisProviderId = providerId)
}

private fun taskTypeModelLabel(
    type: TaskType,
    config: TaskRoutingConfig,
    providers: List<ProviderConfig>,
    defaultText: String,
): String {
    val modelId = config.modelIdFor(type)
    if (modelId.isNullOrBlank()) return defaultText
    val providerId = config.providerIdFor(type)
    val provider = providers.firstOrNull { it.id == providerId }
    val model = provider?.models?.firstOrNull { it.id == modelId }
        ?: providers.flatMap { it.models }.firstOrNull { it.id == modelId }
    val modelName = model?.name?.ifBlank { model.id } ?: modelId
    return if (provider != null) {
        "${provider.displayName.ifBlank { provider.id }} / $modelName"
    } else {
        modelName
    }
}
