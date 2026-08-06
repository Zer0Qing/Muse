@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.AgentCapability
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import kotlinx.coroutines.flow.map

// ──────────────────────────────────────────────────────────────────────────────
// 子页 5: 高级
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 高级子页 — 背景 / 自定义请求 / 标签。
 */
@Composable
fun AssistantAdvancedPage(
    assistantId: String,
    onBack: () -> Unit,
) {
    val assistant = rememberAssistant(assistantId)
    val update = rememberAssistantUpdater(assistantId)

    SettingsSubPageScaffold(title = stringResource(R.string.assistant_detail_advanced), onBack = onBack) {
        val a = assistant
        if (a == null) {
            item { Text(stringResource(R.string.assistant_detail_loading), color = MaterialTheme.colorScheme.outline) }
            return@SettingsSubPageScaffold
        }
        // 卡片组 1: 背景
        item {
            CardGroup {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.backgroundUrl,
                            onPersist = { v -> update { it.copy(backgroundUrl = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_background_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(
                                    R.string.assistant_detail_background_opacity,
                                    (a.backgroundOpacity * 100).toInt(),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            MuseSlider(
                                value = a.backgroundOpacity,
                                onValueChange = { v -> update { it.copy(backgroundOpacity = v) } },
                                valueRange = 0f..1f,
                                valueFormatter = { "${(it * 100).toInt()}%" },
                            )
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_detail_gradient_background)) },
                    supportingContent = { Text(stringResource(R.string.assistant_detail_gradient_background_desc)) },
                    trailingContent = {
                        MuseSwitch(
                            checked = a.useGradientBackground,
                            onCheckedChange = { v -> update { it.copy(useGradientBackground = v) } },
                        )
                    },
                )
            }
        }
        // 卡片组 2: 自定义请求
        item {
            CardGroup {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.customHeadersJson,
                            onPersist = { v -> update { it.copy(customHeadersJson = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_custom_headers_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        )
                    },
                )
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = a.customBodiesJson,
                            onPersist = { v -> update { it.copy(customBodiesJson = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_custom_bodies_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        )
                    },
                )
            }
        }
        // 卡片组 3: 标签
        item {
            CardGroup {
                item(
                    headlineContent = {
                        DebouncedTextField(
                            value = parseTagsForEdit(a.tagsJson),
                            onPersist = { v -> update { it.copy(tagsJson = v) } },
                            label = { Text(stringResource(R.string.assistant_detail_tags_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            transform = { serializeTagsForEdit(it) },
                        )
                    },
                )
            }
        }
        // 卡片组 3b: 多 Agent 能力标签
        item {
            CapabilityChipsSection(
                capabilitiesJson = a.capabilitiesJson,
                onCapabilitiesChange = { newJson -> update { it.copy(capabilitiesJson = newJson) } },
            )
        }
        // 卡片组 4: v1.97 正则替换规则
        item {
            RegexRulesSection(
                rulesJson = a.regexRulesJson,
                onRulesChange = { newJson -> update { it.copy(regexRulesJson = newJson) } },
            )
        }
    }
}

/**
 * v1.97: 正则替换规则编辑区。
 *
 * 显示规则列表(名称 + 范围 + 启用开关 + 编辑/删除按钮),
 * 点击「添加规则」或编辑现有规则时弹出 [RegexRuleEditDialog]。
 */
@Composable
private fun RegexRulesSection(
    rulesJson: String,
    onRulesChange: (String) -> Unit,
) {
    val rules = remember(rulesJson) {
        io.zer0.muse.transformer.RegexTransformer.parseRulesFromJson(rulesJson)
    }
    var editingRule by remember { mutableStateOf<io.zer0.muse.data.assistant.AssistantRegex?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.assistant_detail_regex_title)) },
            supportingContent = { Text(stringResource(R.string.assistant_detail_regex_desc)) },
        )
        if (rules.isEmpty()) {
            item(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.assistant_detail_regex_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                },
            )
        } else {
            rules.forEach { rule ->
                item(
                    key = rule.id,
                    headlineContent = {
                        Column {
                            Text(
                                text = rule.name.ifBlank { rule.findRegex },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${rule.findRegex} → ${rule.replaceString}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    supportingContent = {
                        Text(
                            text = when (rule.affectingScope) {
                                "user" -> stringResource(R.string.assistant_detail_regex_scope_user)
                                "assistant" -> stringResource(R.string.assistant_detail_regex_scope_assistant)
                                else -> stringResource(R.string.assistant_detail_regex_scope_both)
                            } + if (rule.visualOnly) " · 仅显示" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MuseSwitch(
                                checked = rule.enabled,
                                onCheckedChange = { v ->
                                    val updated = rules.map { if (it.id == rule.id) it.copy(enabled = v) else it }
                                    onRulesChange(io.zer0.muse.transformer.RegexTransformer.serializeRules(updated))
                                },
                            )
                            IconButton(onClick = { editingRule = rule }) {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = stringResource(R.string.assistant_detail_regex_edit_title),
                                )
                            }
                            IconButton(onClick = {
                                val updated = rules.filterNot { it.id == rule.id }
                                onRulesChange(io.zer0.muse.transformer.RegexTransformer.serializeRules(updated))
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.assistant_detail_regex_delete_cd),
                                )
                            }
                        }
                    },
                )
            }
        }
        // 添加规则按钮
        item(
            onClick = { showAddDialog = true },
            leadingContent = {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.assistant_detail_regex_add),
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }

    // 编辑现有规则
    editingRule?.let { rule ->
        RegexRuleEditDialog(
            rule = rule,
            isNew = false,
            onDismiss = { editingRule = null },
            onSave = { updated ->
                val newRules = rules.map { if (it.id == updated.id) updated else it }
                onRulesChange(io.zer0.muse.transformer.RegexTransformer.serializeRules(newRules))
                editingRule = null
            },
        )
    }
    // 新增规则
    if (showAddDialog) {
        RegexRuleEditDialog(
            rule = io.zer0.muse.data.assistant.AssistantRegex(),
            isNew = true,
            onDismiss = { showAddDialog = false },
            onSave = { newRule ->
                val newRules = rules + newRule
                onRulesChange(io.zer0.muse.transformer.RegexTransformer.serializeRules(newRules))
                showAddDialog = false
            },
        )
    }
}

/**
 * v1.97: 正则规则编辑弹窗。
 *
 * 字段:名称 / 查找正则 / 替换字符串 / 影响范围 / 仅显示替换 / 启用。
 * 保存时校验正则语法,无效则提示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegexRuleEditDialog(
    rule: io.zer0.muse.data.assistant.AssistantRegex,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (io.zer0.muse.data.assistant.AssistantRegex) -> Unit,
) {
    var name by remember(rule.id) { mutableStateOf(rule.name) }
    var findRegex by remember(rule.id) { mutableStateOf(rule.findRegex) }
    var replaceString by remember(rule.id) { mutableStateOf(rule.replaceString) }
    var scope by remember(rule.id) { mutableStateOf(rule.affectingScope) }
    var visualOnly by remember(rule.id) { mutableStateOf(rule.visualOnly) }
    var enabled by remember(rule.id) { mutableStateOf(rule.enabled) }
    var regexError by remember(rule.id) { mutableStateOf(false) }

    val scopeOptions = listOf(
        "both" to stringResource(R.string.assistant_detail_regex_scope_both),
        "user" to stringResource(R.string.assistant_detail_regex_scope_user),
        "assistant" to stringResource(R.string.assistant_detail_regex_scope_assistant),
    )

    MuseDialog(
        onDismissRequest = onDismiss,
        title = if (isNew) stringResource(R.string.assistant_detail_regex_add)
        else stringResource(R.string.assistant_detail_regex_edit_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MuseTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.assistant_detail_regex_name_label)) },
                    placeholder = { Text(stringResource(R.string.assistant_detail_regex_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = findRegex,
                    onValueChange = {
                        findRegex = it
                        // 实时校验正则语法
                        regexError = if (it.isBlank()) false
                        else runCatching { Regex(it) }.isFailure
                    },
                    label = { Text(stringResource(R.string.assistant_detail_regex_find_label)) },
                    placeholder = { Text(stringResource(R.string.assistant_detail_regex_find_hint)) },
                    singleLine = true,
                    isError = regexError,
                    supportingText = if (regexError) {
                        { Text(stringResource(R.string.assistant_detail_regex_invalid)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = replaceString,
                    onValueChange = { replaceString = it },
                    label = { Text(stringResource(R.string.assistant_detail_regex_replace_label)) },
                    placeholder = { Text(stringResource(R.string.assistant_detail_regex_replace_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.assistant_detail_regex_scope_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    scopeOptions.forEach { (value, label) ->
                        MuseChip(
                            selected = scope == value,
                            onClick = { scope = value },
                            label = label,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_detail_regex_visual_only),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MuseSwitch(checked = visualOnly, onCheckedChange = { visualOnly = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_detail_regex_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    MuseSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmText = stringResource(R.string.assistant_detail_done),
        onConfirm = {
            // 不允许保存语法错误的正则(空正则允许,但不会生效)
            if (regexError) return@MuseDialog
            onSave(
                rule.copy(
                    name = name.trim(),
                    findRegex = findRegex.trim(),
                    replaceString = replaceString,
                    affectingScope = scope,
                    visualOnly = visualOnly,
                    enabled = enabled,
                )
            )
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityChipsSection(
    capabilitiesJson: String,
    onCapabilitiesChange: (String) -> Unit,
) {
    val selected = remember(capabilitiesJson) {
        AgentCapability.parseCapabilitiesJson(capabilitiesJson).toSet()
    }
    var customInput by remember { mutableStateOf("") }

    CardGroup(
        title = { Text(stringResource(R.string.assistant_detail_capabilities_title)) },
    ) {
        item(
            headlineContent = {
                Text(
                    text = stringResource(R.string.assistant_detail_capabilities_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
        )
        item(
            headlineContent = {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentCapability.ALL_CAPABILITIES.forEach { capability ->
                        val isSelected = capability in selected
                        MuseChip(
                            selected = isSelected,
                            onClick = {
                                val current = AgentCapability.parseCapabilitiesJson(capabilitiesJson)
                                val updated = if (isSelected) current - capability else current + capability
                                onCapabilitiesChange(AgentCapability.toJson(updated))
                            },
                            label = AgentCapability.displayName(capability),
                        )
                    }
                }
            },
        )
        item(
            headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        label = { Text(stringResource(R.string.assistant_detail_custom_capability)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val id = customInput.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
                            if (id.isNotBlank() && id !in selected) {
                                val current = AgentCapability.parseCapabilitiesJson(capabilitiesJson)
                                onCapabilitiesChange(AgentCapability.toJson(current + id))
                            }
                            customInput = ""
                        },
                    ) {
                        Text(stringResource(R.string.assistant_detail_add))
                    }
                }
            },
        )
    }
}

/** 把 tagsJson (["a","b"]) 转为逗号分隔字符串便于编辑。 */
private fun parseTagsForEdit(json: String): String {
    val raw = json.trim()
    if (raw.isBlank() || raw == "[]") return ""
    return raw.removeSurrounding("[", "]")
        .split(",")
        .map { it.trim().removeSurrounding("\"") }
        .filter { it.isNotEmpty() }
        .joinToString(", ")
}

/** 把逗号分隔字符串转回 tagsJson (["a","b"])。 */
private fun serializeTagsForEdit(text: String): String {
    val ids = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (ids.isEmpty()) return "[]"
    return ids.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }
}
