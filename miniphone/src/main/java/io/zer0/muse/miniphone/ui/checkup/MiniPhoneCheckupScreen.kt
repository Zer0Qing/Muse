package io.zer0.muse.miniphone.ui.checkup

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
import io.zer0.muse.miniphone.data.checkup.MpCheckupDirection
import io.zer0.muse.miniphone.data.checkup.MpCheckupIntensity
import io.zer0.muse.miniphone.data.checkup.MpCheckupStore
import io.zer0.muse.miniphone.data.db.entity.MpCheckupCaseEntity
import io.zer0.muse.miniphone.data.db.entity.MpCheckupEvidenceEntity
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 查岗页：选一个角色、方向与强度就能立案；案卷列表里能看到结论与逐条证据。
 *
 * 状态与文案都在界面层按当前语言渲染，数据层只存记号与原文。
 */
@Composable
internal fun MiniPhoneCheckupScreen(
    cases: Flow<List<MpCheckupCaseEntity>>,
    characters: List<MpCharacter>,
    evidenceFor: (String) -> Flow<List<MpCheckupEvidenceEntity>>,
    onStart: (String, MpCheckupDirection, MpCheckupIntensity) -> Unit,
    onInterpret: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val rows by cases.collectAsStateWithLifecycle(initialValue = emptyList())
    Column(
        modifier = Modifier.fillMaxSize().testTag(MiniPhoneCheckupTags.PAGE).background(colors.background),
    ) {
        CheckupHeader(onBack)
        CheckupStartPanel(characters, onStart)
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).testTag(MiniPhoneCheckupTags.EMPTY),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.mp_checkup_empty),
                    style = MiniPhoneTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { case ->
                    CheckupCaseRow(case, evidenceFor, onInterpret, onDelete)
                }
            }
        }
    }
}

@Composable
private fun CheckupHeader(onBack: () -> Unit) {
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
                .padding(6.dp).size(22.dp).testTag(MiniPhoneCheckupTags.BACK),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.mp_checkup_title),
            style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
        )
    }
}

@Composable
private fun CheckupStartPanel(characters: List<MpCharacter>, onStart: (String, MpCheckupDirection, MpCheckupIntensity) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableStateOf(MpCheckupDirection.OUTGOING) }
    var intensity by remember { mutableStateOf(MpCheckupIntensity.STANDARD) }
    val colors = MiniPhoneTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            characters.forEach { character ->
                CheckupChip(
                    label = character.name,
                    active = selected == character.id,
                    tag = MiniPhoneCheckupTags.character(character.id),
                ) { selected = character.id }
            }
        }
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckupChip(
                stringResource(R.string.mp_checkup_out),
                direction == MpCheckupDirection.OUTGOING,
                MiniPhoneCheckupTags.DIRECTION_OUT,
            ) { direction = MpCheckupDirection.OUTGOING }
            CheckupChip(
                stringResource(R.string.mp_checkup_in),
                direction == MpCheckupDirection.INCOMING,
                MiniPhoneCheckupTags.DIRECTION_IN,
            ) { direction = MpCheckupDirection.INCOMING }
        }
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckupChip(
                stringResource(R.string.mp_checkup_light),
                intensity == MpCheckupIntensity.LIGHT,
                MiniPhoneCheckupTags.INTENSITY_LIGHT,
            ) { intensity = MpCheckupIntensity.LIGHT }
            CheckupChip(
                stringResource(R.string.mp_checkup_standard),
                intensity == MpCheckupIntensity.STANDARD,
                MiniPhoneCheckupTags.INTENSITY_STANDARD,
            ) { intensity = MpCheckupIntensity.STANDARD }
            CheckupChip(
                stringResource(R.string.mp_checkup_deep),
                intensity == MpCheckupIntensity.DEEP,
                MiniPhoneCheckupTags.INTENSITY_DEEP,
            ) { intensity = MpCheckupIntensity.DEEP }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.mp_checkup_start),
            style = MiniPhoneTheme.typography.body.copy(color = colors.accent),
            modifier = Modifier.clip(CircleShape)
                .clickable(enabled = selected != null) { selected?.let { onStart(it, direction, intensity) } }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(MiniPhoneCheckupTags.START),
        )
    }
}

@Composable
private fun CheckupChip(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
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
private fun CheckupCaseRow(
    case: MpCheckupCaseEntity,
    evidenceFor: (String) -> Flow<List<MpCheckupEvidenceEntity>>,
    onInterpret: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val statusLabel = when (case.status) {
        MpCheckupStore.STATUS_DONE -> stringResource(R.string.mp_checkup_done)
        MpCheckupStore.STATUS_FAILED -> stringResource(R.string.mp_checkup_failed)
        else -> stringResource(R.string.mp_checkup_running)
    }
    val directionLabel = if (MpCheckupDirection.fromWire(case.direction) == MpCheckupDirection.INCOMING) {
        stringResource(R.string.mp_checkup_in)
    } else {
        stringResource(R.string.mp_checkup_out)
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneCheckupTags.caseRow(case.id))
            .clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "$directionLabel · $statusLabel",
            style = typography.body.copy(color = colors.textPrimary),
        )
        Text(
            text = case.summary.ifBlank { stringResource(R.string.mp_checkup_no_summary) },
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            CheckupChip(
                stringResource(R.string.mp_checkup_evidence),
                active = false,
                tag = MiniPhoneCheckupTags.interpret(case.id),
            ) { onInterpret(case.id) }
            CheckupChip(
                stringResource(R.string.mp_memo_delete),
                active = false,
                tag = MiniPhoneCheckupTags.delete(case.id),
            ) { onDelete(case.id) }
        }
        if (expanded) CheckupEvidenceList(case.id, evidenceFor)
    }
}

@Composable
private fun CheckupEvidenceList(caseId: String, evidenceFor: (String) -> Flow<List<MpCheckupEvidenceEntity>>) {
    val rows by evidenceFor(caseId).collectAsStateWithLifecycle(initialValue = emptyList())
    val colors = MiniPhoneTheme.colors
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.mp_checkup_no_evidence),
            style = MiniPhoneTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { item ->
            val label = when (item.kind) {
                MpCheckupStore.KIND_CALL_VOICE ->
                    stringResource(R.string.mp_calls_voice) + " · " +
                        stringResource(R.string.mp_checkup_seconds, item.excerpt.toIntOrNull() ?: 0)
                MpCheckupStore.KIND_CALL_VIDEO ->
                    stringResource(R.string.mp_calls_video) + " · " +
                        stringResource(R.string.mp_checkup_seconds, item.excerpt.toIntOrNull() ?: 0)
                else -> item.excerpt
            }
            Column(Modifier.testTag(MiniPhoneCheckupTags.evidence(item.id))) {
                Text(text = label, style = MiniPhoneTheme.typography.caption, color = colors.textPrimary)
                if (item.verdict.isNotBlank()) {
                    Text(
                        text = item.verdict,
                        style = MiniPhoneTheme.typography.caption,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}
