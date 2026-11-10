package io.zer0.muse.miniphone.ui.misc

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
import androidx.compose.foundation.text.KeyboardActions
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
import io.zer0.muse.miniphone.data.db.entity.MpRingtoneEntity
import io.zer0.muse.miniphone.data.misc.MpRingtoneStore
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 通话铃声页：选角色（含「通用」）与通话种类，填名字与素材地址即可加一段铃声；列表里能删。
 *
 * 纯渲染页面，状态与动作都由 Flow + 回调驱动，不碰 Room。
 * 取用走门面的 observeFor，它把空角色的通用铃声一并带出来 —— 选「通用」时看到的就是所有角色共用的那些。
 */
@Composable
internal fun MiniPhoneRingtoneScreen(
    ringtonesFor: (characterId: String, kind: String) -> Flow<List<MpRingtoneEntity>>,
    characters: List<MpCharacter>,
    onAdd: (characterId: String, name: String, uri: String, kind: String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    var selectedCharacter by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf(MpRingtoneStore.KIND_VOICE) }
    val rows by ringtonesFor(selectedCharacter, selectedKind).collectAsStateWithLifecycle(emptyList())
    val characterOptions = listOf(stringResource(R.string.mp_ringtone_general) to "") +
        characters.map { it.name to it.id }

    Column(modifier = Modifier.fillMaxSize().testTag(MiniPhoneMiscTags.Ringtone.PAGE).background(colors.background)) {
        MiscHeader(stringResource(R.string.mp_ringtone_title), MiniPhoneMiscTags.Ringtone.BACK, onBack)
        MiscChipRow(characterOptions, selectedCharacter, tagFor = {
            if (it.isEmpty()) MiniPhoneMiscTags.Ringtone.GENERAL else MiniPhoneMiscTags.Ringtone.character(it)
        }) { selectedCharacter = it }
        Spacer(Modifier.size(4.dp))
        MiscChipRow(
            options = listOf(
                stringResource(R.string.mp_ringtone_voice) to MpRingtoneStore.KIND_VOICE,
                stringResource(R.string.mp_ringtone_video) to MpRingtoneStore.KIND_VIDEO,
            ),
            selected = selectedKind,
            tagFor = { MiniPhoneMiscTags.Ringtone.kind(it) },
        ) { selectedKind = it }
        RingtoneAddPanel(onAdd = { name, uri -> onAdd(selectedCharacter, name, uri, selectedKind) })
        if (rows.isEmpty()) {
            MiscEmpty(stringResource(R.string.mp_ringtone_empty), MiniPhoneMiscTags.Ringtone.EMPTY)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { item ->
                    RingtoneItemRow(item, onDelete = onDelete)
                }
            }
        }
    }
}

@Composable
private fun RingtoneAddPanel(onAdd: (name: String, uri: String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    var name by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf("") }
    val canAdd = name.isNotBlank() && uri.isNotBlank()

    fun commit() {
        if (!canAdd) return
        onAdd(name, uri)
        name = ""
        uri = ""
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(stringResource(R.string.mp_ringtone_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Ringtone.NAME_INPUT),
        )
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = uri,
            onValueChange = { uri = it },
            placeholder = { Text(stringResource(R.string.mp_ringtone_uri_hint)) },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Ringtone.URI_INPUT),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.mp_ringtone_add),
            style = MiniPhoneTheme.typography.body.copy(color = if (canAdd) colors.accent else colors.textSecondary),
            modifier = Modifier.clip(CircleShape).clickable(enabled = canAdd) { commit() }
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag(MiniPhoneMiscTags.Ringtone.ADD),
        )
    }
}

@Composable
private fun RingtoneItemRow(item: MpRingtoneEntity, onDelete: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Ringtone.item(item.id))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = item.name, style = typography.body.copy(color = colors.textPrimary), modifier = Modifier.weight(1f))
            if (item.characterId.isEmpty()) {
                Text(
                    text = stringResource(R.string.mp_ringtone_general),
                    style = typography.caption.copy(color = colors.accent),
                )
            }
        }
        Text(
            text = ringtoneKindLabel(item.kind),
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.padding(top = 2.dp),
        )
        MiscAction(
            label = stringResource(R.string.mp_memo_delete),
            tag = MiniPhoneMiscTags.Ringtone.delete(item.id),
            enabled = true,
        ) { onDelete(item.id) }
    }
}

@Composable
private fun ringtoneKindLabel(kind: String): String = when (kind) {
    MpRingtoneStore.KIND_VIDEO -> stringResource(R.string.mp_ringtone_video)
    else -> stringResource(R.string.mp_ringtone_voice)
}
