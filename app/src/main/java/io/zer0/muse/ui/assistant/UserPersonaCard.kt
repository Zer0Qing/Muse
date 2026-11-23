package io.zer0.muse.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.data.assistant.UserPersona
import io.zer0.muse.data.assistant.UserPersonaStore
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSettingsIcon
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.UUID

/**
 * 35: 助手详情页的「用户面具」卡。
 *
 * 展示该助手当前选中的面具；点开底部面板可挑选库里的面具、新建/编辑/删除。
 * 不选任何面具时回落到全局用户画像（注入侧 SystemPromptAssembler 同源）。
 */
@Composable
internal fun UserPersonaCard(assistantId: String, modifier: Modifier = Modifier) {
    val store: UserPersonaStore = koinInject()
    val scope = rememberCoroutineScope()
    var sheetOpen by remember { mutableStateOf(false) }
    val personas by produceState(initialValue = emptyList<UserPersona>(), store) {
        store.personasFlow.collect { value = it }
    }
    val selected by produceState<UserPersona?>(initialValue = null, store, assistantId, personas) {
        value = store.selectedFor(assistantId)
    }

    CardGroup(modifier = modifier, title = { Text(stringResource(R.string.user_persona_section)) }) {
        item(
            onClick = { sheetOpen = true },
            leadingContent = { MuseSettingsIcon(MuseIcons.user) },
            headlineContent = { Text(stringResource(R.string.user_persona_current)) },
            supportingContent = { Text(selected?.name ?: stringResource(R.string.user_persona_none)) },
            trailingContent = { ChevronRight() },
        )
    }

    if (sheetOpen) {
        UserPersonaSheet(
            personas = personas,
            selectedId = selected?.id,
            onSelect = { id -> scope.launch { store.select(assistantId, id) } },
            onSave = { p -> scope.launch { store.upsert(p) } },
            onDelete = { id -> scope.launch { store.delete(id) } },
            onDismiss = { sheetOpen = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserPersonaSheet(
    personas: List<UserPersona>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onSave: (UserPersona) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<UserPersona?>(null) }
    var deleteTarget by remember { mutableStateOf<UserPersona?>(null) }
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete),
            itemName = target.name,
            onConfirm = {
                deleteTarget = null
                onDelete(target.id)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MuseSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Text(stringResource(R.string.user_persona_section), style = MaterialTheme.typography.titleMedium)
            MuseCapsuleButton(
                text = stringResource(R.string.user_persona_use_global),
                onClick = { onSelect(null) },
                variant = IosCapsuleButtonVariant.Secondary,
            )
            personas.forEach { p ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = MuseSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Text((if (p.id == selectedId) "✓ " else "") + p.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        p.text.take(60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.user_persona_select),
                            onClick = { onSelect(p.id) },
                            variant = IosCapsuleButtonVariant.Secondary,
                            size = MuseButtonSize.Compact,
                            fillWidth = false,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.common_edit),
                            onClick = {
                                editing = p
                                name = p.name
                                text = p.text
                            },
                            variant = IosCapsuleButtonVariant.Text,
                            size = MuseButtonSize.Compact,
                            fillWidth = false,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.common_delete),
                            onClick = { deleteTarget = p },
                            destructive = true,
                            size = MuseButtonSize.Compact,
                            fillWidth = false,
                        )
                    }
                }
            }
            Text(
                stringResource(if (editing == null) R.string.user_persona_new else R.string.common_edit),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = MuseSpacing.sm),
            )
            MuseTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.user_persona_name)) },
            )
            MuseTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.user_persona_text)) },
                minLines = 3,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    if (name.isNotBlank() && text.isNotBlank()) {
                        onSave(UserPersona(id = editing?.id ?: UUID.randomUUID().toString(), name = name.trim(), text = text.trim()))
                        editing = null
                        name = ""
                        text = ""
                    }
                },
            )
        }
    }
}
