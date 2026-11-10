package io.zer0.muse.miniphone.ui.roam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.entity.MpRoamNpcEntity
import io.zer0.muse.miniphone.data.db.entity.MpRoamStateEntity
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 漫游页：选一个角色、输入一个虚拟地点就能出发；下面列出谁在哪，点进一处就能在这一带遇见路人。
 *
 * 纯渲染 —— 位置与路人都由外部 Flow 喂进来，出发/遇见/清空只回调，不碰数据库也不碰宿主。
 * 地点是虚构的，没有任何真实地图或定位。生成路人的成败在门面里处理，这里只呈现结果，
 * 空态如实显示「还没出门」和「这一带没遇到谁」，不替没发生的事编一句文案。
 */
@Composable
internal fun MiniPhoneRoamScreen(
    locations: Flow<List<MpRoamStateEntity>>,
    characters: List<MpCharacter>,
    npcsFor: (String) -> Flow<List<MpRoamNpcEntity>>,
    onDepart: (String, String) -> Unit,
    onEncounter: (String, String, String) -> Unit,
    onClearNpcs: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val dimens = MiniPhoneTheme.dimens
    val rows by locations.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedCharacter by remember { mutableStateOf<String?>(null) }
    val namesById = remember(characters) { characters.associate { it.id to it.name } }
    val selected = rows.firstOrNull { it.characterId == selectedCharacter } ?: rows.firstOrNull()
    Column(
        modifier = Modifier.fillMaxSize().testTag(MiniPhoneRoamTags.PAGE).background(colors.background),
    ) {
        RoamHeader(onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            RoamDepartPanel(characters, dimens.pageMargin, onDepart)
            Spacer(Modifier.size(10.dp))
            if (rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.pageMargin, vertical = 40.dp)
                        .testTag(MiniPhoneRoamTags.EMPTY),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.mp_roam_empty), style = typography.caption, color = colors.textSecondary)
                }
            } else {
                rows.forEach { state ->
                    RoamLocationRow(
                        state = state,
                        characterName = namesById[state.characterId] ?: state.characterId,
                        selected = selected?.characterId == state.characterId,
                        onClick = { selectedCharacter = state.characterId },
                    )
                }
                if (selected != null) {
                    Spacer(Modifier.size(8.dp))
                    RoamNpcPanel(selected, dimens.pageMargin, npcsFor, onEncounter, onClearNpcs)
                }
            }
        }
    }
}

@Composable
private fun RoamHeader(onBack: () -> Unit) {
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
                .padding(6.dp).size(22.dp).testTag(MiniPhoneRoamTags.BACK),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.mp_roam_title),
            style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
        )
    }
}

@Composable
private fun RoamDepartPanel(characters: List<MpCharacter>, pageMargin: Dp, onDepart: (String, String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    var selected by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = pageMargin, vertical = 4.dp)) {
        Text(stringResource(R.string.mp_roam_who), style = typography.caption, color = colors.textSecondary)
        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            characters.forEach { character ->
                RoamChip(character.name, selected == character.id, MiniPhoneRoamTags.character(character.id)) {
                    selected = character.id
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(R.string.mp_roam_location_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(MiniPhoneRoamTags.LOCATION_INPUT),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.mp_roam_depart),
                style = typography.body.copy(color = colors.accent),
                modifier = Modifier.clip(CircleShape)
                    .clickable(enabled = selected != null && draft.isNotBlank()) {
                        selected?.let { onDepart(it, draft.trim()) }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(MiniPhoneRoamTags.DEPART),
            )
        }
    }
}

@Composable
private fun RoamChip(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
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

@Composable
private fun RoamLocationRow(state: MpRoamStateEntity, characterName: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneRoamTags.locationRow(state.characterId))
            .clickable(onClick = onClick).padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
    ) {
        Text(characterName, style = typography.body.copy(color = if (selected) colors.accent else colors.textPrimary))
        Text(
            text = state.locationName,
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.padding(top = 2.dp),
        )
        if (state.note.isNotBlank()) {
            Text(
                text = state.note,
                style = typography.caption.copy(color = colors.textSecondary),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun RoamNpcPanel(
    state: MpRoamStateEntity,
    pageMargin: Dp,
    npcsFor: (String) -> Flow<List<MpRoamNpcEntity>>,
    onEncounter: (String, String, String) -> Unit,
    onClearNpcs: (String) -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val npcs by npcsFor(state.locationId).collectAsStateWithLifecycle(initialValue = emptyList())
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = pageMargin).testTag(MiniPhoneRoamTags.NPC_PANEL),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.mp_roam_npc_title),
                style = typography.caption.copy(color = colors.textSecondary),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.mp_roam_encounter),
                style = typography.body.copy(color = colors.accent),
                modifier = Modifier.clip(CircleShape)
                    .clickable { onEncounter(state.characterId, state.locationId, state.locationName) }
                    .padding(horizontal = 10.dp, vertical = 6.dp).testTag(MiniPhoneRoamTags.ENCOUNTER),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.mp_roam_clear),
                style = typography.caption.copy(color = colors.textSecondary),
                modifier = Modifier.clip(CircleShape).clickable { onClearNpcs(state.locationId) }
                    .padding(horizontal = 10.dp, vertical = 6.dp).testTag(MiniPhoneRoamTags.CLEAR),
            )
        }
        if (npcs.isEmpty()) {
            Text(
                text = stringResource(R.string.mp_roam_no_npc),
                style = typography.caption.copy(color = colors.textSecondary),
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            npcs.forEach { npc -> RoamNpcRow(npc) }
        }
    }
}

@Composable
private fun RoamNpcRow(npc: MpRoamNpcEntity) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneRoamTags.npc(npc.id)).padding(top = 6.dp),
    ) {
        Text(npc.name, style = typography.body.copy(color = colors.textPrimary))
        if (npc.persona.isNotBlank()) {
            Text(
                text = npc.persona,
                style = typography.caption.copy(color = colors.textSecondary),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
