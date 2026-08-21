@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength", "ComplexCondition", "UseCheckOrError")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.CircleMinus
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Search
import compose.icons.tablericons.Wand
import compose.icons.tablericons.X
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Surface
import io.zer0.muse.ui.common.form.MuseSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ModelRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun ModelsTab(
    config: ProviderConfig,
    modelsState: SnapshotStateList<Model>,
    isFetching: Boolean,
    onFetch: (Boolean) -> Unit,
    onAddModel: () -> Unit,
    // v1.0.8 (7.6): 模型健康检查回调 + 状态透传(从 ProviderEditPage 传入)
    onTestModel: (Model) -> Unit = {},
    modelTestStatuses: Map<String, ModelTestStatus> = emptyMap(),
) {
    var editingModel by remember { mutableStateOf<Model?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (modelsState.isEmpty()) {
            // P2-5: 用 weight(1f) 让空状态占满按钮下方剩余空间
            //  (Column 中 fillMaxSize 会拿到父级完整高度,而非剩余高度,导致溢出)
            EmptyModelsState(
                isFetching = isFetching,
                onFetch = onFetch,
                onAddModel = onAddModel,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = MusePaddings.contentGap),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { modelsState.clear() },
                            enabled = modelsState.isNotEmpty(),
                            shape = MuseShapes.pill,
                        ) {
                            Icon(compose.icons.TablerIcons.CircleMinus, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                            Spacer(Modifier.size(MusePaddings.contentGap))
                            Text(stringResource(io.zer0.muse.R.string.settings_provider_deselect_all), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = { onFetch(true) },
                            enabled = !isFetching,
                            shape = MuseShapes.pill,
                        ) {
                            if (isFetching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(TablerIcons.Refresh, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                            }
                            Spacer(Modifier.size(MusePaddings.contentGap))
                            Text(stringResource(R.string.settings_provider_refresh_models))
                        }
                    }
                }
                items(modelsState.toList(), key = { "${it.providerId}:${it.id}" }) { model ->
                    ProviderModelRow(
                        model = model,
                        providerType = config.type,
                        providerName = config.displayName,
                        isAdded = true,
                        onAction = { editingModel = model },
                        // v1.0.8 (7.6): 传入健康检查回调 + 当前状态(按 model.id 查询)
                        onTest = { onTestModel(model) },
                        testStatus = modelTestStatuses[model.id] ?: ModelTestStatus.Idle,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }

    editingModel?.let { model ->
        ModelAbilityEditorDialog(
            model = model,
            onDismiss = { editingModel = null },
            onSave = { updated ->
                val idx = modelsState.indexOfFirst { it.id == updated.id }
                if (idx >= 0) modelsState[idx] = updated
                editingModel = null
            },
            onDelete = {
                modelsState.removeAll { it.id == model.id }
                editingModel = null
            },
        )
    }

}

@Composable
internal fun ModelAbilityEditorDialog(
    model: Model,
    onDismiss: () -> Unit,
    onSave: (Model) -> Unit,
    onDelete: () -> Unit,
) {
    var supportsTools by remember { mutableStateOf(model.abilities.contains(ModelAbility.TOOL) || model.abilities.isEmpty()) }
    var supportsReasoning by remember { mutableStateOf(model.abilities.contains(ModelAbility.REASONING) || model.abilities.isEmpty()) }
    var supportsStreaming by remember { mutableStateOf(model.supportsStreaming) }
    var supportsImageOutput by remember { mutableStateOf(model.supportsImageOutput()) }
    var supportsVision by remember { mutableStateOf(model.supportsVisionInput()) }
    var supportsVideo by remember { mutableStateOf(model.supportsVideo) }
    var contextWindow by remember { mutableStateOf(model.contextWindow?.toString() ?: "") }
    var maxOutputTokens by remember { mutableStateOf(model.maxOutputTokens?.toString() ?: "") }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_edit_model_abilities),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = model.id,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    // v1.97: 一键自动检测能力(基于 ModelRegistry token 匹配)
                    TextButton(
                        onClick = {
                            val abilities = ModelRegistry.lookupAbilities(model.id)
                            val inputMods = ModelRegistry.lookupInputModalities(model.id)
                            val outputMods = ModelRegistry.lookupOutputModalities(model.id)
                            supportsTools = ModelAbility.TOOL in abilities
                            supportsReasoning = ModelAbility.REASONING in abilities
                            supportsVision = "image" in inputMods
                            supportsImageOutput = "image" in outputMods
                            // 未显式设置 contextWindow 时,顺带回填注册表兜底值
                            if (contextWindow.isBlank()) {
                                ModelContextWindowRegistry.lookup(model.id)?.let {
                                    contextWindow = it.toString()
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = TablerIcons.Wand,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(MusePaddings.tightGap))
                        Text(
                            text = stringResource(R.string.settings_provider_auto_detect),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Spacer(Modifier.size(MusePaddings.contentGap))
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_tools), supportsTools) { supportsTools = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_reasoning), supportsReasoning) { supportsReasoning = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_streaming), supportsStreaming) { supportsStreaming = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_image_output), supportsImageOutput) { supportsImageOutput = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_vision), supportsVision) { supportsVision = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_video), supportsVideo) { supportsVideo = it }
                Spacer(Modifier.size(MusePaddings.itemGap))
                MuseTextField(
                    value = contextWindow,
                    onValueChange = { contextWindow = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_provider_context_window)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.size(MusePaddings.contentGap))
                MuseTextField(
                    value = maxOutputTokens,
                    onValueChange = { maxOutputTokens = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_provider_max_output_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.size(MusePaddings.itemGap))
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_remove_model),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.settings_common_save),
        onConfirm = {
            val newAbilities = buildSet {
                if (supportsTools) add(ModelAbility.TOOL)
                if (supportsReasoning) add(ModelAbility.REASONING)
            }
            val newOutput = buildSet {
                add("text")
                if (supportsImageOutput) add("image")
                if (supportsVideo) add("video")
            }
            val newInput = buildSet {
                add("text")
                if (supportsVision) add("image")
            }
            onSave(
                model.copy(
                    abilities = newAbilities,
                    supportsStreaming = supportsStreaming,
                    supportsVision = supportsVision,
                    supportsVideo = supportsVideo,
                    outputModalities = newOutput,
                    inputModalities = newInput,
                    contextWindow = contextWindow.toIntOrNull(),
                    maxOutputTokens = maxOutputTokens.toIntOrNull(),
                )
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
internal fun AbilitySwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MuseSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun EmptyModelsState(
    isFetching: Boolean,
    onFetch: (Boolean) -> Unit,
    onAddModel: () -> Unit,
    // P2-5: 外部传入 modifier,允许调用方在 Column 中用 weight(1f) 让其占满剩余空间
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_provider_no_models),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.settings_provider_no_models_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(MuseIconSizes.icon))
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            TextButton(
                onClick = { onFetch(true) },
                enabled = !isFetching,
                shape = MuseShapes.pill,
            ) {
                if (isFetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(TablerIcons.Refresh, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                }
                Spacer(Modifier.size(MusePaddings.contentGap))
                Text(stringResource(R.string.settings_provider_refresh_models))
            }
            TextButton(
                onClick = onAddModel,
                shape = MuseShapes.pill,
            ) {
                Icon(TablerIcons.Plus, contentDescription = null, modifier = Modifier.size(MuseIconSizes.iconSmall))
                Spacer(Modifier.size(MusePaddings.contentGap))
                Text(stringResource(R.string.settings_provider_add_new_model))
            }
        }
    }
}

/**
 * 拉取上游模型成功后弹出的 iOS 风格底部 Sheet。
 *
 * 改进点:
 *  - 不再手动输入上下文(K),统一用 [ModelContextWindowRegistry] 自动推断
 *  - 复选框改为 iOS 式行点击 + 右侧对勾
 *  - 搜索栏、分组标题、底部按钮均使用暖色/iOS 风格
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FetchedModelsPickerSheet(
    models: List<Model>,
    existingIds: Set<String>,
    providerType: ProviderType,
    providerName: String,
    onDismiss: () -> Unit,
    onConfirm: (List<Model>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var groupExpanded by remember { mutableStateOf(true) }

    val filtered = remember(query, models) {
        if (query.isBlank()) models else models.filter { it.id.contains(query, ignoreCase = true) }
    }

    // 选中数量用 derivedStateOf 缓存,避免 confirmText 每次重组都重新 count
    val selectedCount by remember {
        derivedStateOf { filtered.count { selected[it.id] == true } }
    }

    // MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_select_models),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_provider_models_count, models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.size(MusePaddings.itemGap))

                // iOS 风格搜索栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MuseShapes.small,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = TablerIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(R.string.settings_provider_search_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        // 隐藏式 TextField,保持 iOS 搜索栏的视觉纯净
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            singleLine = true,
                        )
                    }
                    if (query.isNotBlank()) {
                        MuseTactileButton(
                            icon = TablerIcons.X,
                            onClick = { query = "" },
                            contentDescription = stringResource(R.string.settings_provider_clear),
                            tint = MaterialTheme.colorScheme.outline,
                            iconSize = MuseIconSizes.iconSmall,
                        )
                    }
                }

                // 全选/清空
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = MusePaddings.contentGap),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_select_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { filtered.forEach { selected[it.id] = true } },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.settings_provider_clear),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { filtered.forEach { selected[it.id] = false } },
                    )
                }

                // 分组标题(可折叠)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { groupExpanded = !groupExpanded }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val brandColor = providerBrandColor(providerType, providerName)
                    Box(
                        modifier = Modifier
                            .size(MuseIconSizes.iconLarge)
                            .background(brandColor.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = providerBrandIcon(providerType, providerName),
                            contentDescription = null,
                            tint = brandColor,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                    Text(
                        text = providerName.ifBlank { stringResource(R.string.settings_provider_model_list) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (groupExpanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                        contentDescription = if (groupExpanded) stringResource(R.string.settings_common_collapse) else stringResource(R.string.settings_common_expand),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 模型列表(改为 Column,MuseDialog 内容区自带滚动)
                if (groupExpanded) {
                    filtered.forEach { m ->
                        val isSelected = selected[m.id] ?: false
                        val effectiveTokens = m.contextWindow ?: ModelContextWindowRegistry.lookup(m.id)
                        val contextText = formatContextWindow(effectiveTokens, stringResource(R.string.settings_provider_unknown))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected[m.id] = !isSelected }
                                .padding(vertical = MusePaddings.itemGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                        ) {
                            // 左侧选中态:iOS 风格对勾圆圈
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = TablerIcons.Check,
                                        contentDescription = stringResource(R.string.settings_provider_selected),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = m.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (m.id in existingIds) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.settings_provider_already_exists),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                if (m.name != m.id) {
                                    Text(
                                        text = m.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                ModelAbilityChips(model = m)
                            }

                            // 上下文窗口标签
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MuseShapes.small,
                            ) {
                                Text(
                                    text = contextText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        },
        confirmText = stringResource(R.string.settings_provider_add_with_count, selectedCount),
        onConfirm = {
            onConfirm(
                filtered.filter { selected[it.id] == true }.map { m ->
                    m.copy(
                        contextWindow = m.contextWindow ?: ModelContextWindowRegistry.lookup(m.id)
                    )
                }
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/** 将 token 数格式化为 K/M 显示(如 128000 -> "128K", 1000000 -> "1M")。null 返回 [unknownLabel]。 */
internal fun formatContextWindow(tokens: Int?, unknownLabel: String): String {
    if (tokens == null) return unknownLabel
    return when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1000 -> "${tokens / 1000}K"
        else -> tokens.toString()
    }
}

/**
 * 解析 Google Service Account JSON 文件内容。
 *
 * Google Service Account JSON 标准字段(仅取所需三项):
 *  - `private_key`: PEM PKCS#8 字符串
 *  - `client_email`: 服务账号邮箱
 *  - `project_id`: GCP 项目 ID
 *
 * @return Triple(email, privateKey, projectId);任一关键字段缺失返回 null
 */
internal fun parseServiceAccountJson(jsonText: String): Triple<String, String, String>? {
    return runCatching {
        val obj = AppJson.decodeFromString(JsonObject.serializer(), jsonText)
        val email = obj["client_email"]?.jsonPrimitive?.contentOrNull ?: return null
        val key = obj["private_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val projectId = obj["project_id"]?.jsonPrimitive?.contentOrNull ?: ""
        Triple(email, key, projectId)
    }.getOrNull()
}

/**
 * 按简单的点分 JSON Path 从 JSON 字符串中提取值。
 *
 * 支持对象字段与数组下标(如 $.data.total_usage 或 $.data.items.0.value)。
 * 解析失败或路径不存在返回 null。
 */
internal fun extractJsonPath(json: String, path: String): String? {
    val keys = path.trim()
        .removePrefix("$")
        .removePrefix(".")
        .split(".")
        .filter { it.isNotBlank() }
    val root = runCatching {
        Json.parseToJsonElement(json)
    }.getOrNull() ?: return null
    var current: JsonElement = root
    for (key in keys) {
        current = when (current) {
            is JsonObject -> current[key] ?: return null
            is JsonArray -> {
                val index = key.toIntOrNull() ?: return null
                current.getOrNull(index) ?: return null
            }
            else -> return current.toString()
        }
    }
    return when (current) {
        is JsonPrimitive -> current.contentOrNull ?: current.toString()
        else -> current.toString()
    }
}

/** 把 Map 格式化为每行 "Key: Value" 的多行文本。 */
internal fun formatCustomHeaders(map: Map<String, String>): String {
    return map.entries.joinToString("\n") { "${it.key}: ${it.value}" }
}

/** 把每行 "Key: Value" 解析为 Map,格式不合法的行被忽略。 */
internal fun parseCustomHeaders(text: String): Map<String, String> {
    return text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx == -1) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isEmpty()) return@mapNotNull null
            key to value
        }
        .toMap()
}

/** 把 Map 格式化为 JSON 字符串,失败返回空字符串。 */
internal fun formatCustomBody(map: Map<String, JsonElement>): String {
    if (map.isEmpty()) return ""
    return runCatching {
        AppJson.encodeToString(JsonObject.serializer(), JsonObject(map))
    }.getOrDefault("")
}

/** 把 JSON 字符串解析为 Map,失败返回空 Map。 */
internal fun parseCustomBody(text: String): Map<String, JsonElement> {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return emptyMap()
    return runCatching {
        AppJson.parseToJsonElement(trimmed).jsonObject.toMap()
    }.getOrDefault(emptyMap())
}
