package io.zer0.muse.miniphone.ui.relation

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
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
import io.zer0.muse.miniphone.data.db.entity.MpRelationEdgeEntity
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 关系网页：选一个角色当主角，同时看到两个方向 ——
 * 「他怎么看别人」（以他起点的边）与「别人怎么看他」（指向他的边），两区分开，不混为一谈。
 *
 * 纯渲染：边由外部 Flow 注入，设置/删除/自述都是回调，本页不碰库也不起协程。
 * 记录面板记的是「主角怎么看对象」；要记反向看法，把主角换成对方再记一条。
 */
@Composable
internal fun MiniPhoneRelationScreen(
    edges: Flow<List<MpRelationEdgeEntity>>,
    characters: List<MpCharacter>,
    onSet: (String, String, String) -> Unit,
    onDescribe: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val rows by edges.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedId by remember { mutableStateOf(characters.firstOrNull()?.id) }
    val selected = selectedId
    Column(
        modifier = Modifier.fillMaxSize().testTag(MiniPhoneRelationTags.PAGE).background(colors.background),
    ) {
        RelationHeader(onBack)
        if (selected == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).testTag(MiniPhoneRelationTags.EMPTY),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.mp_relation_no_characters),
                    style = MiniPhoneTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        } else {
            val nameOf: (String) -> String = { id -> characters.firstOrNull { it.id == id }?.name ?: id }
            RelationCharacterPicker(characters, selected) { selectedId = it }
            RelationSetPanel(characters, selected, onSet, onDescribe)
            LazyColumn(
                modifier = Modifier.weight(1f).testTag(MiniPhoneRelationTags.LIST),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    RelationEdgeSection(
                        sectionTag = MiniPhoneRelationTags.FROM_SECTION,
                        title = stringResource(R.string.mp_relation_from_section),
                        emptyTag = MiniPhoneRelationTags.FROM_EMPTY,
                        emptyText = stringResource(R.string.mp_relation_from_empty),
                        edges = rows.filter { it.fromId == selected },
                        otherId = { it.toId },
                        nameOf = nameOf,
                        onDelete = onDelete,
                    )
                }
                item {
                    RelationEdgeSection(
                        sectionTag = MiniPhoneRelationTags.TO_SECTION,
                        title = stringResource(R.string.mp_relation_to_section),
                        emptyTag = MiniPhoneRelationTags.TO_EMPTY,
                        emptyText = stringResource(R.string.mp_relation_to_empty),
                        edges = rows.filter { it.toId == selected && it.fromId != selected },
                        otherId = { it.fromId },
                        nameOf = nameOf,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationHeader(onBack: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Back,
            contentDescription = stringResource(R.string.mp_room_back),
            tint = colors.textPrimary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack)
                .padding(6.dp).size(22.dp).testTag(MiniPhoneRelationTags.BACK),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.mp_relation_title),
            style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
        )
    }
}

@Composable
private fun RelationCharacterPicker(characters: List<MpCharacter>, selectedId: String, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp),
    ) {
        characters.forEach { character ->
            RelationChip(
                label = character.name,
                active = selectedId == character.id,
                tag = MiniPhoneRelationTags.character(character.id),
            ) { onSelect(character.id) }
        }
    }
}

@Composable
private fun RelationSetPanel(
    characters: List<MpCharacter>,
    selectedId: String,
    onSet: (String, String, String) -> Unit,
    onDescribe: (String, String) -> Unit,
) {
    var targetId by remember(selectedId) { mutableStateOf<String?>(null) }
    var draft by remember(selectedId) { mutableStateOf("") }
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography

    fun commit() {
        val target = targetId ?: return
        if (draft.isBlank()) return
        onSet(selectedId, target, draft)
        draft = ""
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.mp_relation_set_title),
            style = typography.body.copy(color = colors.textPrimary),
        )
        Text(
            text = stringResource(R.string.mp_relation_target_hint),
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            characters.filter { it.id != selectedId }.forEach { character ->
                RelationChip(
                    label = character.name,
                    active = targetId == character.id,
                    tag = MiniPhoneRelationTags.target(character.id),
                ) { targetId = character.id }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(R.string.mp_relation_label_hint)) },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(MiniPhoneRelationTags.LABEL_INPUT),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Text(
                text = stringResource(R.string.mp_relation_set),
                style = typography.body.copy(color = colors.accent),
                modifier = Modifier.clip(CircleShape)
                    .clickable(enabled = targetId != null) { commit() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(MiniPhoneRelationTags.SET),
            )
            Text(
                text = stringResource(R.string.mp_relation_describe),
                style = typography.body.copy(color = colors.accent),
                modifier = Modifier.clip(CircleShape)
                    .clickable(enabled = targetId != null) { targetId?.let { onDescribe(selectedId, it) } }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(MiniPhoneRelationTags.DESCRIBE),
            )
        }
    }
}

@Composable
private fun RelationEdgeSection(
    sectionTag: String,
    title: String,
    emptyTag: String,
    emptyText: String,
    edges: List<MpRelationEdgeEntity>,
    otherId: (MpRelationEdgeEntity) -> String,
    nameOf: (String) -> String,
    onDelete: (String) -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().testTag(sectionTag)) {
        Text(text = title, style = typography.body.copy(color = colors.textPrimary))
        if (edges.isEmpty()) {
            Text(
                text = emptyText,
                style = typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp).testTag(emptyTag),
            )
        } else {
            edges.forEach { edge -> RelationEdgeRow(edge, nameOf(otherId(edge)), onDelete) }
        }
    }
}

@Composable
private fun RelationEdgeRow(edge: MpRelationEdgeEntity, otherName: String, onDelete: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneRelationTags.edgeRow(edge.id)).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "$otherName · ${edge.label}",
                style = typography.body.copy(color = colors.textPrimary),
            )
            if (edge.note.isNotBlank()) {
                Text(
                    text = edge.note,
                    style = typography.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.mp_memo_delete),
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.clip(CircleShape).clickable { onDelete(edge.id) }
                .padding(horizontal = 10.dp, vertical = 6.dp).testTag(MiniPhoneRelationTags.delete(edge.id)),
        )
    }
}

@Composable
private fun RelationChip(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Text(
        text = label,
        style = MiniPhoneTheme.typography.caption.copy(
            color = if (active) colors.accent else colors.textSecondary,
        ),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
    )
}
