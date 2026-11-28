@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "ReturnCount",
    "MaxLineLength",
    "ComplexCondition",
)

package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ModelRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
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
    onTestModel: (Model) -> Unit = {},
    modelTestStatuses: Map<String, ModelTestStatus> = emptyMap(),
    canFetch: Boolean = !isFetching,
) {
    var editingModel by remember { mutableStateOf<Model?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    val allModels = modelsState.toList()
    val filteredModels = remember(allModels, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            allModels
        } else {
            allModels.filter { m ->
                m.id.contains(q, ignoreCase = true) || m.name.contains(q, ignoreCase = true)
            }
        }
    }

    if (showClearConfirm) {
        MuseDialog(
            onDismissRequest = { showClearConfirm = false },
            title = stringResource(R.string.settings_provider_clear_models),
            content = {
                Text(
                    text = stringResource(R.string.settings_provider_models_count, modelsState.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                modelsState.clear()
                searchQuery = ""
                showClearConfirm = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showClearConfirm = false },
            destructive = true,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (modelsState.isEmpty()) {
            EmptyModelsState(
                isFetching = isFetching,
                canFetch = canFetch,
                onFetch = onFetch,
                onAddModel = onAddModel,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = MusePaddings.pageMargin,
                    end = MusePaddings.pageMargin,
                    top = MuseSpacing.sm,
                    bottom = MuseSpacing.xxxl,
                ),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
            ) {
                item(key = "models_toolbar") {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.settings_provider_refresh_models),
                            onClick = { onFetch(true) },
                            enabled = canFetch && !isFetching,
                            loading = isFetching,
                            leadingIcon = MuseIcons.refresh,
                            variant = IosCapsuleButtonVariant.Secondary,
                            size = MuseButtonSize.Compact,
                            fillWidth = false,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.settings_provider_add_new_model),
                            onClick = onAddModel,
                            leadingIcon = MuseIcons.plus,
                            variant = IosCapsuleButtonVariant.Secondary,
                            size = MuseButtonSize.Compact,
                            fillWidth = false,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.settings_provider_clear_models),
                            onClick = { showClearConfirm = true },
                            variant = IosCapsuleButtonVariant.Text,
                            size = MuseButtonSize.Compact,
                            fillWidth = false,
                        )
                    }
                }

                // 模型数量较多时自动出现实时搜索框，避免在中转站长列表里盲翻
                if (allModels.size >= 8) {
                    item(key = "models_search") {
                        ModelsSearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                        )
                    }
                }

                if (filteredModels.isEmpty()) {
                    item(key = "models_no_match") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MuseSpacing.xxl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_preset_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item(key = "models_group") {
                        CardGroup {
                            filteredModels.forEach { model ->
                                item(key = "${model.providerId}:${model.id}") {
                                    ProviderModelRow(
                                        model = model,
                                        providerType = config.type,
                                        providerName = config.displayName,
                                        isAdded = true,
                                        onAction = { editingModel = model },
                                        onTest = { onTestModel(model) },
                                        testStatus = modelTestStatuses[model.id] ?: ModelTestStatus.Idle,
                                        showLogo = false,
                                    )
                                }
                            }
                        }
                    }
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
private fun ModelsSearchField(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(MusePaddings.searchBarHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MuseSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_provider_search_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    },
                )
            }
            if (query.isNotEmpty()) {
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = { onQueryChange("") },
                    contentDescription = stringResource(R.string.common_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    compact = true,
                )
            }
        }
    }
}

@Composable
internal fun ModelAbilityEditorDialog(model: Model, onDismiss: () -> Unit, onSave: (Model) -> Unit, onDelete: () -> Unit) {
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = model.id,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.wand,
                        onClick = {
                            val abilities = ModelRegistry.lookupAbilities(model.id)
                            val inputMods = ModelRegistry.lookupInputModalities(model.id)
                            val outputMods = ModelRegistry.lookupOutputModalities(model.id)
                            supportsTools = ModelAbility.TOOL in abilities
                            supportsReasoning = ModelAbility.REASONING in abilities
                            supportsVision = "image" in inputMods
                            supportsImageOutput = "image" in outputMods
                            if (contextWindow.isBlank()) {
                                ModelContextWindowRegistry.lookup(model.id)?.let {
                                    contextWindow = it.toString()
                                }
                            }
                        },
                        contentDescription = stringResource(R.string.settings_provider_edit_model_abilities),
                        compact = true,
                    )
                }

                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_tools), supportsTools) { supportsTools = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_reasoning), supportsReasoning) { supportsReasoning = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_streaming), supportsStreaming) { supportsStreaming = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_vision), supportsVision) { supportsVision = it }
                AbilitySwitchRow(
                    stringResource(R.string.settings_provider_ability_image_output),
                    supportsImageOutput,
                ) { supportsImageOutput = it }
                AbilitySwitchRow(stringResource(R.string.settings_provider_ability_video), supportsVideo) { supportsVideo = it }

                MuseTextField(
                    value = contextWindow,
                    onValueChange = { contextWindow = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_provider_context_window)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                MuseTextField(
                    value = maxOutputTokens,
                    onValueChange = { maxOutputTokens = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_provider_max_output_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_provider_remove_model),
                    onClick = onDelete,
                    destructive = true,
                    size = MuseButtonSize.Compact,
                    fillWidth = false,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        },
        confirmText = stringResource(R.string.common_save),
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
                    userEdited = true,
                ),
            )
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

@Composable
internal fun AbilitySwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = MuseSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    modifier: Modifier = Modifier,
    canFetch: Boolean = !isFetching,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = MuseSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MuseEmptyState(
            icon = MuseIcons.atom,
            title = stringResource(R.string.settings_provider_no_models),
        )
        Spacer(Modifier.height(MuseSpacing.lg))
        Row(
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.settings_provider_refresh_models),
                onClick = { onFetch(true) },
                enabled = canFetch && !isFetching,
                loading = isFetching,
                leadingIcon = MuseIcons.refresh,
                variant = IosCapsuleButtonVariant.Primary,
                size = MuseButtonSize.Medium,
                fillWidth = false,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.settings_provider_add_new_model),
                onClick = onAddModel,
                leadingIcon = MuseIcons.plus,
                variant = IosCapsuleButtonVariant.Secondary,
                size = MuseButtonSize.Medium,
                fillWidth = false,
            )
        }
    }
}

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

    val selectedCount by remember {
        derivedStateOf { filtered.count { selected[it.id] == true } }
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_select_models),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.settings_provider_models_count, models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ModelsSearchField(
                    query = query,
                    onQueryChange = { query = it },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = stringResource(R.string.settings_provider_select_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { filtered.forEach { selected[it.id] = true } },
                    )
                    Spacer(Modifier.width(MuseSpacing.lg))
                    Text(
                        text = stringResource(R.string.common_clear),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { filtered.forEach { selected[it.id] = false } },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { groupExpanded = !groupExpanded }
                        .padding(vertical = MuseSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    ProviderLogo(
                        type = providerType,
                        name = providerName,
                        size = MuseIconSizes.iconLarge,
                        cornerRadius = 8.dp,
                    )
                    Text(
                        text = providerName.ifBlank { stringResource(R.string.settings_provider_model_list) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (groupExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = stringResource(
                            if (groupExpanded) R.string.settings_common_collapse else R.string.common_expand,
                        ),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                HorizontalDivider(
                    thickness = MuseBorderWidth.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
                )

                if (groupExpanded) {
                    filtered.forEach { m ->
                        val isSelected = selected[m.id] ?: false
                        val effectiveTokens = m.contextWindow ?: ModelContextWindowRegistry.lookup(m.id)
                        val contextText = formatContextWindow(effectiveTokens, stringResource(R.string.settings_provider_unknown))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected[m.id] = !isSelected }
                                .padding(vertical = MuseSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = MuseAlpha.handle)
                                        },
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = MuseIcons.check,
                                        contentDescription = stringResource(R.string.settings_provider_selected),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = m.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (m.id in existingIds) {
                                        Spacer(Modifier.width(MuseSpacing.sm))
                                        Text(
                                            text = stringResource(R.string.settings_provider_already_exists),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                ModelAbilityChips(model = m)
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MuseShapes.small,
                            ) {
                                Text(
                                    text = contextText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
                                )
                            }
                        }
                        HorizontalDivider(
                            thickness = MuseBorderWidth.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.settings_provider_add_with_count, selectedCount),
        onConfirm = {
            onConfirm(
                filtered.filter { selected[it.id] == true }.map { m ->
                    m.copy(
                        contextWindow = m.contextWindow ?: ModelContextWindowRegistry.lookup(m.id),
                    )
                },
            )
        },
        dismissText = stringResource(R.string.common_cancel),
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
 * 提取 `client_email` / `private_key` / `project_id`；任一关键字段缺失返回 null。
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
 * 按简单点分 JSON Path 从 JSON 字符串中提取值，解析失败或路径不存在返回 null。
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
