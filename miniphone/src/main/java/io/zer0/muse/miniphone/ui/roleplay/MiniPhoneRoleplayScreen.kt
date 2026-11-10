package io.zer0.muse.miniphone.ui.roleplay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.entity.MpRpEndingEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpSaveEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpScriptEntity
import io.zer0.muse.miniphone.data.roleplay.MpRoleplayState
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 跑团页：剧本库、每个剧本下的存档与结局册。
 *
 * 纯渲染：数据来自外部 Flow，建/改/删、开局、推进和结案全部交给调用方。
 * 局面快照在这里只做可读展示，页面不把它回写成新的业务事实。
 */
@Composable
internal fun MiniPhoneRoleplayScreen(
    scripts: Flow<List<MpRpScriptEntity>>,
    characters: List<MpCharacter>,
    savesFor: (String) -> Flow<List<MpRpSaveEntity>>,
    endingsFor: (String) -> Flow<List<MpRpEndingEntity>>,
    onCreateScript: (String, String) -> Unit,
    onUpdateScript: (String, String, String) -> Unit,
    onDeleteScript: (String) -> Unit,
    onStartRun: (String, String, String) -> Unit,
    onAdvance: (String, String) -> Unit,
    onConclude: (String) -> Unit,
    onDeleteSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val rows by scripts.collectAsStateWithLifecycle(initialValue = emptyList())
    var draftTitle by remember { mutableStateOf("") }
    var draftBody by remember { mutableStateOf("") }
    var editingScriptId by remember { mutableStateOf<String?>(null) }

    fun resetEditor() {
        draftTitle = ""
        draftBody = ""
        editingScriptId = null
    }

    fun commitEditor() {
        if (draftTitle.isBlank() || draftBody.isBlank()) return
        val editing = editingScriptId
        if (editing == null) onCreateScript(draftTitle, draftBody) else onUpdateScript(editing, draftTitle, draftBody)
        resetEditor()
    }

    Column(
        modifier = Modifier.fillMaxSize().testTag(MiniPhoneRoleplayTags.PAGE).background(colors.background),
    ) {
        RoleplayHeader(onBack)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RoleplayEditor(
                    title = draftTitle,
                    body = draftBody,
                    editing = editingScriptId != null,
                    onTitleChange = { draftTitle = it },
                    onBodyChange = { draftBody = it },
                    onCommit = { commitEditor() },
                    onCancel = { resetEditor() },
                )
            }
            if (rows.isEmpty()) {
                item { RoleplayEmpty() }
            } else {
                items(rows, key = { it.id }) { script ->
                    RoleplayScriptCard(
                        script = script,
                        characters = characters,
                        savesFor = savesFor,
                        endingsFor = endingsFor,
                        onEdit = {
                            editingScriptId = script.id
                            draftTitle = script.title
                            draftBody = script.body
                        },
                        onDelete = onDeleteScript,
                        onStartRun = onStartRun,
                        onAdvance = onAdvance,
                        onConclude = onConclude,
                        onDeleteSave = onDeleteSave,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleplayHeader(onBack: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Back,
            contentDescription = stringResource(R.string.mp_rp_back),
            tint = colors.textPrimary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack)
                .padding(6.dp).size(22.dp).testTag(MiniPhoneRoleplayTags.BACK),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.mp_rp_title),
            style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
        )
    }
}

@Composable
private fun RoleplayEmpty() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp).testTag(MiniPhoneRoleplayTags.EMPTY),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.mp_rp_empty),
            style = MiniPhoneTheme.typography.caption,
            color = MiniPhoneTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun RoleplayEditor(
    title: String,
    body: String,
    editing: Boolean,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            placeholder = { Text(stringResource(R.string.mp_rp_script_title_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneRoleplayTags.TITLE_INPUT),
        )
        OutlinedTextField(
            value = body,
            onValueChange = onBodyChange,
            placeholder = { Text(stringResource(R.string.mp_rp_script_body_hint)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(MiniPhoneRoleplayTags.BODY_INPUT),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            RoleplayChip(
                label = stringResource(if (editing) R.string.mp_rp_update_script else R.string.mp_rp_create_script),
                tag = MiniPhoneRoleplayTags.COMMIT_SCRIPT,
                enabled = title.isNotBlank() && body.isNotBlank(),
                onClick = onCommit,
            )
            if (editing) {
                RoleplayChip(stringResource(R.string.mp_rp_cancel_edit), MiniPhoneRoleplayTags.CANCEL_EDIT) { onCancel() }
            }
        }
    }
}

@Composable
private fun RoleplayScriptCard(
    script: MpRpScriptEntity,
    characters: List<MpCharacter>,
    savesFor: (String) -> Flow<List<MpRpSaveEntity>>,
    endingsFor: (String) -> Flow<List<MpRpEndingEntity>>,
    onEdit: () -> Unit,
    onDelete: (String) -> Unit,
    onStartRun: (String, String, String) -> Unit,
    onAdvance: (String, String) -> Unit,
    onConclude: (String) -> Unit,
    onDeleteSave: (String) -> Unit,
) {
    var expanded by remember(script.id) { mutableStateOf(false) }
    var selectedCharacterId by remember(script.id, characters.firstOrNull()?.id) {
        mutableStateOf(characters.firstOrNull()?.id)
    }
    val saves by savesFor(script.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val endings by endingsFor(script.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography

    Column(
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(12.dp),
    ) {
        // 展开开关就在标题这一行上：定位标签跟着 clickable 走，点到这一行才真的会展开
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .testTag(MiniPhoneRoleplayTags.scriptRow(script.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = script.title,
                style = typography.body.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = MiniPhoneIcons.Discover,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = script.body,
            style = typography.caption.copy(color = colors.textSecondary),
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (characters.isEmpty()) {
                Text(
                    text = stringResource(R.string.mp_rp_no_characters),
                    style = typography.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.testTag(MiniPhoneRoleplayTags.noCharacters(script.id)),
                )
            } else {
                characters.forEach { character ->
                    RoleplayChip(
                        label = character.name,
                        tag = MiniPhoneRoleplayTags.character(script.id, character.id),
                        active = selectedCharacterId == character.id,
                    ) { selectedCharacterId = character.id }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            RoleplayChip(
                label = stringResource(R.string.mp_rp_start_run),
                tag = MiniPhoneRoleplayTags.startRun(script.id),
                enabled = selectedCharacterId != null,
            ) { selectedCharacterId?.let { onStartRun(script.id, it, script.title) } }
            RoleplayChip(stringResource(R.string.mp_rp_edit_script), MiniPhoneRoleplayTags.editScript(script.id)) { onEdit() }
            RoleplayChip(
                stringResource(R.string.mp_rp_delete_script),
                MiniPhoneRoleplayTags.deleteScript(script.id),
                accent = false,
            ) { onDelete(script.id) }
        }
        if (expanded) {
            RoleplaySaveSection(script.id, saves, onAdvance, onConclude, onDeleteSave)
            RoleplayEndingSection(script.id, endings)
        }
    }
}

@Composable
private fun RoleplaySaveSection(
    scriptId: String,
    saves: List<MpRpSaveEntity>,
    onAdvance: (String, String) -> Unit,
    onConclude: (String) -> Unit,
    onDeleteSave: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        RoleplaySectionTitle(stringResource(R.string.mp_rp_saves))
        if (saves.isEmpty()) {
            Text(
                text = stringResource(R.string.mp_rp_saves_empty),
                style = MiniPhoneTheme.typography.caption,
                color = MiniPhoneTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp).testTag(MiniPhoneRoleplayTags.savesEmpty(scriptId)),
            )
        } else {
            saves.forEach { save -> RoleplaySaveRow(save, onAdvance, onConclude, onDeleteSave) }
        }
    }
}

@Composable
private fun RoleplaySaveRow(
    save: MpRpSaveEntity,
    onAdvance: (String, String) -> Unit,
    onConclude: (String) -> Unit,
    onDeleteSave: (String) -> Unit,
) {
    var action by remember(save.id) { mutableStateOf("") }
    val state = remember(save.stateJson) { MpRoleplayState.fromJson(save.stateJson) }
    val narrative = state?.latestText?.takeIf { it.isNotBlank() }
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(MiniPhoneRoleplayTags.saveRow(save.id)),
    ) {
        Text(save.title, style = typography.body.copy(color = colors.textPrimary))
        Text(
            text = stringResource(R.string.mp_rp_turn, save.turn),
            style = typography.timestamp,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = narrative ?: stringResource(R.string.mp_rp_state_unreadable),
            style = typography.caption,
            color = colors.textPrimary,
            maxLines = 6,
            modifier = Modifier.padding(top = 4.dp),
        )
        state?.latestRoll?.let { roll ->
            Text(
                text = stringResource(R.string.mp_rp_roll, roll),
                style = typography.timestamp,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        OutlinedTextField(
            value = action,
            onValueChange = { action = it },
            placeholder = { Text(stringResource(R.string.mp_rp_action_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(MiniPhoneRoleplayTags.actionInput(save.id)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            RoleplayChip(stringResource(R.string.mp_rp_advance), MiniPhoneRoleplayTags.advance(save.id)) {
                onAdvance(save.id, action)
            }
            RoleplayChip(stringResource(R.string.mp_rp_conclude), MiniPhoneRoleplayTags.conclude(save.id)) {
                onConclude(save.id)
            }
            RoleplayChip(
                stringResource(R.string.mp_rp_delete_save),
                MiniPhoneRoleplayTags.deleteSave(save.id),
                accent = false,
            ) { onDeleteSave(save.id) }
        }
    }
}

@Composable
private fun RoleplayEndingSection(scriptId: String, endings: List<MpRpEndingEntity>) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        RoleplaySectionTitle(stringResource(R.string.mp_rp_endings))
        if (endings.isEmpty()) {
            Text(
                text = stringResource(R.string.mp_rp_endings_empty),
                style = MiniPhoneTheme.typography.caption,
                color = MiniPhoneTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp).testTag(MiniPhoneRoleplayTags.endingsEmpty(scriptId)),
            )
        } else {
            endings.forEach { ending ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp).testTag(MiniPhoneRoleplayTags.endingRow(ending.id)),
                ) {
                    Text(
                        text = ending.title,
                        style = MiniPhoneTheme.typography.body,
                        color = MiniPhoneTheme.colors.textPrimary,
                    )
                    Text(ending.text, style = MiniPhoneTheme.typography.caption, color = MiniPhoneTheme.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun RoleplaySectionTitle(value: String) {
    Text(value, style = MiniPhoneTheme.typography.body, color = MiniPhoneTheme.colors.textPrimary)
}

@Composable
private fun RoleplayChip(
    label: String,
    tag: String,
    active: Boolean = false,
    accent: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val color = when {
        !enabled -> colors.textSecondary
        active || accent -> colors.accent
        else -> colors.textSecondary
    }
    Text(
        text = label,
        style = MiniPhoneTheme.typography.caption.copy(color = color),
        modifier = Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
    )
}
