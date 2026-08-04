package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.prompttemplate.PromptTemplate
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * B0-07: Prompt 模板管理页。
 *
 * 支持新建 / 编辑 / 删除 / 上下排序;内置模板不可删除但可复制为自定义模板。
 * 保存走 [SettingsRepository.savePromptTemplates],重启后保留。
 */
@Composable
fun PromptTemplateManagerPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val templates by settings.promptTemplatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<PromptTemplate?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PromptTemplate?>(null) }
    var editorName by remember { mutableStateOf("") }
    var editorCategory by remember { mutableStateOf("") }
    var editorContent by remember { mutableStateOf("") }

    fun persist(updated: List<PromptTemplate>) {
        scope.launch { settings.savePromptTemplates(updated) }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.prompt_template_manager_title),
        onBack = onBack,
    ) {
        item { SectionLabel(stringResource(R.string.prompt_template_manager_list)) }
        item {
            SettingsGroup {
                if (templates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_template_manager_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(MusePaddings.cardInner),
                    )
                } else {
                    templates.forEachIndexed { index, template ->
                        if (index > 0) SettingsGroupDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.bubbleInner),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = template.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (template.builtIn) {
                                        Surface(
                                            shape = MuseShapes.pill,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.prompt_template_manager_built_in),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = template.category.ifBlank { "-" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = {
                                    val list = templates.toMutableList()
                                    val tmp = list[index - 1]
                                    list[index - 1] = list[index]
                                    list[index] = tmp
                                    persist(list)
                                },
                                enabled = index > 0,
                                modifier = Modifier.size(MuseIconSizes.touchTarget),
                            ) {
                                Icon(
                                    imageVector = TablerIcons.ArrowUp,
                                    contentDescription = stringResource(R.string.prompt_template_manager_move_up),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                            IconButton(
                                onClick = {
                                    val list = templates.toMutableList()
                                    val tmp = list[index + 1]
                                    list[index + 1] = list[index]
                                    list[index] = tmp
                                    persist(list)
                                },
                                enabled = index < templates.lastIndex,
                                modifier = Modifier.size(MuseIconSizes.touchTarget),
                            ) {
                                Icon(
                                    imageVector = TablerIcons.ArrowDown,
                                    contentDescription = stringResource(R.string.prompt_template_manager_move_down),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                            IconButton(
                                onClick = {
                                    editing = template
                                    isNew = false
                                    editorName = template.name
                                    editorCategory = template.category
                                    editorContent = template.content
                                },
                                modifier = Modifier.size(MuseIconSizes.touchTarget),
                            ) {
                                Icon(
                                    imageVector = TablerIcons.Edit,
                                    contentDescription = stringResource(R.string.prompt_template_manager_edit),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                            if (template.builtIn) {
                                IconButton(
                                    onClick = {
                                        val copy = template.copy(
                                            id = "custom_${System.currentTimeMillis()}",
                                            builtIn = false,
                                            name = "${template.name} (copy)",
                                        )
                                        persist(templates + copy)
                                    },
                                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                                ) {
                                    Icon(
                                        imageVector = TablerIcons.Copy,
                                        contentDescription = stringResource(R.string.prompt_template_manager_copy),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { deleting = template },
                                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                                ) {
                                    Icon(
                                        imageVector = TablerIcons.Trash,
                                        contentDescription = stringResource(R.string.prompt_template_manager_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            TextButton(
                onClick = {
                    editing = PromptTemplate(
                        id = "",
                        name = "",
                        category = "",
                        content = "",
                        builtIn = false,
                    )
                    isNew = true
                    editorName = ""
                    editorCategory = ""
                    editorContent = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.bubbleInner),
            ) {
                Icon(
                    imageVector = TablerIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.prompt_template_manager_add),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    editing?.let { template ->
        MuseDialog(
            onDismissRequest = { editing = null },
            title = stringResource(
                if (isNew) R.string.prompt_template_manager_add
                else R.string.prompt_template_manager_edit
            ),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseTextField(
                        value = editorName,
                        onValueChange = { editorName = it },
                        label = { Text(stringResource(R.string.prompt_template_manager_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MuseTextField(
                        value = editorCategory,
                        onValueChange = { editorCategory = it },
                        label = { Text(stringResource(R.string.prompt_template_manager_category)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MuseTextField(
                        value = editorContent,
                        onValueChange = { editorContent = it },
                        label = { Text(stringResource(R.string.prompt_template_manager_content)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                    )
                }
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                val name = editorName.trim()
                val content = editorContent.trim()
                if (name.isNotBlank() && content.isNotBlank()) {
                    val saved = if (isNew) {
                        template.copy(
                            id = "custom_${System.currentTimeMillis()}",
                            name = name,
                            category = editorCategory.trim(),
                            content = content,
                        )
                    } else {
                        template.copy(
                            name = name,
                            category = editorCategory.trim(),
                            content = content,
                        )
                    }
                    persist(
                        if (isNew) templates + saved
                        else templates.map { if (it.id == saved.id) saved else it }
                    )
                }
                editing = null
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { editing = null },
        )
    }

    deleting?.let { template ->
        MuseDialog(
            onDismissRequest = { deleting = null },
            title = stringResource(R.string.prompt_template_manager_delete),
            content = {
                Text(
                    text = stringResource(R.string.prompt_template_manager_delete_confirm, template.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                persist(templates.filterNot { it.id == template.id })
                deleting = null
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { deleting = null },
        )
    }
}
