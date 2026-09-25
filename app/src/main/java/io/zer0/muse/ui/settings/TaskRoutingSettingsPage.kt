package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SettingsRepository.TaskRoutingConfig
import io.zer0.muse.data.SettingsRepository.TaskType
import io.zer0.muse.ui.ModelSwitchSheet
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * B0-04 / v2.0 重设计: 任务模型路由设置页。
 *
 * 开启后发送消息时会根据输入内容自动检测任务类型,并切换到对应的绑定模型。
 * 改版要点:
 *  - 每个任务类型一行:图标 + 类型名 + 触发场景说明,右侧胶囊直接显示当前绑定模型
 *    ("主模型"表示未绑定、沿当前模型);
 *  - 底部提供"重置全部绑定",避免逐个进面板清除;
 *  - 绑定交互仍走 [ModelSwitchSheet],支持选 Provider + 模型。
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
    val context = LocalContext.current
    var editingType by remember { mutableStateOf<TaskType?>(null) }
    var editingProviderId by remember { mutableStateOf<String?>(null) }
    val defaultModelText = stringResource(R.string.settings_task_routing_primary_model)

    fun update(block: (TaskRoutingConfig) -> TaskRoutingConfig) {
        scope.launch { settings.saveTaskRoutingConfig(block(config)) }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_task_routing_title),
        onBack = onBack,
    ) {
        item { SectionLabel(stringResource(R.string.settings_task_routing_general)) }
        item {
            SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
                SettingsSwitchRow(
                    icon = MuseIcons.taskRouting,
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
            SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
                TaskType.values().forEachIndexed { index, type ->
                    if (index > 0) SettingsGroupDivider()
                    val modelId = config.modelIdFor(type)
                    val bound = !modelId.isNullOrBlank()
                    SettingsItemRow(
                        icon = taskTypeIcon(type),
                        title = stringResource(taskTypeTitleRes(type)),
                        subtitle = stringResource(taskTypeDescRes(type)),
                        onClick = {
                            editingType = type
                            editingProviderId = config.providerIdFor(type)
                                ?: providers.firstOrNull { provider ->
                                    provider.models.any { it.id == config.modelIdFor(type) }
                                }?.id
                                ?: activeProviderId
                        },
                    ) {
                        ModelPill(
                            text = if (bound) {
                                taskTypeModelLabel(
                                    type = type,
                                    config = config,
                                    providers = providers,
                                    defaultText = defaultModelText,
                                )
                            } else {
                                defaultModelText
                            },
                            bound = bound,
                        )
                    }
                }
            }
        }

        item {
            SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
                SettingsItemRow(
                    icon = MuseIcons.refresh,
                    title = stringResource(R.string.settings_task_routing_reset),
                    subtitle = null,
                    onClick = {
                        update { current ->
                            current.copy(
                                chatModelId = null,
                                reasoningModelId = null,
                                codeModelId = null,
                                creativeModelId = null,
                                analysisModelId = null,
                                chatProviderId = null,
                                reasoningProviderId = null,
                                codeProviderId = null,
                                creativeProviderId = null,
                                analysisProviderId = null,
                            )
                        }
                        MuseToast.show(context.getString(R.string.settings_task_routing_reset_done))
                    },
                )
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
            // 任务路由需要“显式绑定 Provider 首个模型”和“未绑定”可区分;
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

/** 任务类型的绑定状态胶囊:已绑定显示模型名(主色浅底),未绑定显示“主模型”(灰底)。 */
@Composable
private fun ModelPill(text: String, bound: Boolean) {
    Surface(
        shape = MuseShapes.huge,
        color = if (bound) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (bound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp),
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                // v2.0: 补尾随箭头 — 原纯文字标签可点性弱,用户不知道行尾可换模型
                imageVector = MuseIcons.arrowRight,
                contentDescription = null,
                tint = if (bound) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun taskTypeTitleRes(type: TaskType): Int = when (type) {
    TaskType.CHAT -> R.string.settings_task_routing_chat
    TaskType.REASONING -> R.string.settings_task_routing_reasoning
    TaskType.CODE -> R.string.settings_task_routing_code
    TaskType.CREATIVE -> R.string.settings_task_routing_creative
    TaskType.ANALYSIS -> R.string.settings_task_routing_analysis
}

private fun taskTypeDescRes(type: TaskType): Int = when (type) {
    TaskType.CHAT -> R.string.settings_task_routing_desc_chat
    TaskType.REASONING -> R.string.settings_task_routing_desc_reasoning
    TaskType.CODE -> R.string.settings_task_routing_desc_code
    TaskType.CREATIVE -> R.string.settings_task_routing_desc_creative
    TaskType.ANALYSIS -> R.string.settings_task_routing_desc_analysis
}

private fun taskTypeIcon(type: TaskType): ImageVector = when (type) {
    TaskType.CHAT -> MuseIcons.chat
    TaskType.REASONING -> MuseIcons.bulb
    TaskType.CODE -> MuseIcons.code
    TaskType.CREATIVE -> MuseIcons.stars
    TaskType.ANALYSIS -> MuseIcons.chartBar
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
