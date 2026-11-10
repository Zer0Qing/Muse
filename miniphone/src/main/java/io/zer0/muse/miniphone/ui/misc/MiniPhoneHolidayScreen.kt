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
import io.zer0.muse.miniphone.data.db.entity.MpHolidayEventEntity
import io.zer0.muse.miniphone.data.misc.MpHolidayStore
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 节日与纪念日页：切换「每年重复 / 只发生一次」，填日子与名字就能记一条；列表里能删。
 *
 * 纯渲染页面，状态与动作都由 Flow + 回调驱动，不碰 Room。
 * 模式决定 dateKey 的写法：每年重复填 `MM-dd`（year=0），只发生一次填 `yyyy-MM-dd` 并带上正年份，
 * 二者都不合规就交给门面拒绝，界面不擅自补一个假年份。
 */
@Composable
internal fun MiniPhoneHolidayScreen(
    events: Flow<List<MpHolidayEventEntity>>,
    onAdd: (dateKey: String, name: String, note: String, year: Int) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val rows by events.collectAsStateWithLifecycle(emptyList())
    var onceMode by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().testTag(MiniPhoneMiscTags.Holiday.PAGE).background(colors.background)) {
        MiscHeader(stringResource(R.string.mp_holiday_title), MiniPhoneMiscTags.Holiday.BACK, onBack)
        MiscChipRow(
            options = listOf(
                stringResource(R.string.mp_holiday_recurring) to MODE_RECURRING,
                stringResource(R.string.mp_holiday_once) to MODE_ONCE,
            ),
            selected = if (onceMode) MODE_ONCE else MODE_RECURRING,
            tagFor = {
                if (it == MODE_ONCE) MiniPhoneMiscTags.Holiday.MODE_ONCE else MiniPhoneMiscTags.Holiday.MODE_RECURRING
            },
        ) { onceMode = it == MODE_ONCE }
        Spacer(Modifier.size(4.dp))
        HolidayAddPanel(onceMode, onAdd)
        if (rows.isEmpty()) {
            MiscEmpty(stringResource(R.string.mp_holiday_empty), MiniPhoneMiscTags.Holiday.EMPTY)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).testTag(MiniPhoneMiscTags.Holiday.LIST),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { event ->
                    HolidayEventRow(event, onDelete = onDelete)
                }
            }
        }
    }
}

@Composable
private fun HolidayAddPanel(onceMode: Boolean, onAdd: (dateKey: String, name: String, note: String, year: Int) -> Unit) {
    val colors = MiniPhoneTheme.colors
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val canAdd = name.isNotBlank() && date.isNotBlank()
    val dateHint = stringResource(if (onceMode) R.string.mp_holiday_date_once_hint else R.string.mp_holiday_date_hint)

    fun commit() {
        if (!canAdd) return
        val yearValue = if (onceMode) year.trim().toIntOrNull() ?: 0 else 0
        onAdd(date.trim(), name, note, yearValue)
        name = ""
        date = ""
        year = ""
        note = ""
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(stringResource(R.string.mp_holiday_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Holiday.NAME_INPUT),
        )
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            placeholder = { Text(dateHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Holiday.DATE_INPUT),
        )
        if (onceMode) {
            Spacer(Modifier.size(6.dp))
            OutlinedTextField(
                value = year,
                onValueChange = { year = it },
                placeholder = { Text(stringResource(R.string.mp_holiday_year_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Holiday.YEAR_INPUT),
            )
        }
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            placeholder = { Text(stringResource(R.string.mp_holiday_note_hint)) },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Holiday.NOTE_INPUT),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.mp_holiday_add),
            style = MiniPhoneTheme.typography.body.copy(color = if (canAdd) colors.accent else colors.textSecondary),
            modifier = Modifier.clip(CircleShape).clickable(enabled = canAdd) { commit() }
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag(MiniPhoneMiscTags.Holiday.ADD),
        )
    }
}

@Composable
private fun HolidayEventRow(event: MpHolidayEventEntity, onDelete: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Holiday.event(event.id))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = event.name, style = typography.body.copy(color = colors.textPrimary), modifier = Modifier.weight(1f))
            Text(text = event.dateKey, style = typography.caption.copy(color = colors.textSecondary))
        }
        Text(
            text = stringResource(
                if (MpHolidayStore.isRecurring(event)) R.string.mp_holiday_recurring else R.string.mp_holiday_once,
            ),
            style = typography.caption.copy(color = colors.accent),
            modifier = Modifier.padding(top = 2.dp),
        )
        if (event.note.isNotBlank()) {
            Text(
                text = event.note,
                style = typography.caption.copy(color = colors.textSecondary),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        MiscAction(
            label = stringResource(R.string.mp_memo_delete),
            tag = MiniPhoneMiscTags.Holiday.delete(event.id),
            enabled = true,
        ) { onDelete(event.id) }
    }
}

private const val MODE_RECURRING = "recurring"
private const val MODE_ONCE = "once"
