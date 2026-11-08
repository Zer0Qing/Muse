package io.zer0.muse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.assistant.UserPersona
import io.zer0.muse.data.assistant.UserPersonaStore
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSettingsIcon
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.surface.CardGroup
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
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.user_persona_section), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                MuseCapsuleButton(text = stringResource(R.string.user_persona_use_global), onClick = { onSelect(null) })
            }
            personas.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (p.id == selectedId) "✓ " else "") + p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            p.text.take(60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    MuseCapsuleButton(text = stringResource(R.string.user_persona_select), onClick = { onSelect(p.id) })
                    MuseCapsuleButton(text = stringResource(R.string.action_edit), onClick = { editing = p; name = p.name; text = p.text })
                    MuseCapsuleButton(text = stringResource(R.string.action_delete), onClick = { onDelete(p.id) })
                }
            }
            Text(
                stringResource(if (editing == null) R.string.user_persona_new else R.string.action_edit),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
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
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                MuseCapsuleButton(
                    text = stringResource(R.string.action_save),
                    onClick = {
                        if (name.isNotBlank() && text.isNotBlank()) {
                            onSave(UserPersona(id = editing?.id ?: UUID.randomUUID().toString(), name = name.trim(), text = text.trim()))
                            editing = null; name = ""; text = ""
                        }
                    },
                )
            }
        }
    }
}
