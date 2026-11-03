package io.zer0.muse.automation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.automation.core.AutomationAction
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutonomousAccessibilitySettingsCard(
    draft: AutonomousAccessibilityDraftState,
    packageEditor: String,
    taskPreview: ChatViewModel.AutonomousAccessibilityTaskPreview?,
    grant: AutonomousAccessibilityGrant?,
    onDraftChange: (AutonomousAccessibilityDraftState) -> Unit,
    onPackageEditorChange: (String) -> Unit,
    onSave: () -> Unit,
    onConfirmTask: () -> Unit,
    onClearTask: () -> Unit,
) {
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

    SettingsGroup {
        SettingsItemRow(
            icon = MuseIcons.shield,
            title = stringResource(R.string.automation_autonomous_title),
            subtitle = stringResource(R.string.automation_autonomous_desc),
        )
        SettingsGroupDivider()
        SettingsSwitchRow(
            icon = MuseIcons.shield,
            title = stringResource(R.string.automation_autonomous_enabled),
            subtitle = stringResource(R.string.automation_autonomous_enabled_desc),
            checked = draft.enabled,
            onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.clock,
            title = stringResource(R.string.automation_autonomous_start),
            subtitle = formatMinuteOfDay(draft.startMinute),
            onClick = { editingStart = true },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.clock,
            title = stringResource(R.string.automation_autonomous_end),
            subtitle = formatMinuteOfDay(draft.endMinute),
            onClick = { editingEnd = true },
        )
        SettingsGroupDivider()
        SettingsSwitchRow(
            icon = MuseIcons.bookOpen,
            title = stringResource(R.string.automation_autonomous_read),
            checked = AutomationAction.READ_SCREEN in draft.allowedActions,
            onCheckedChange = {
                onDraftChange(draft.toggleAction(AutomationAction.READ_SCREEN))
            },
        )
        SettingsGroupDivider()
        SettingsSwitchRow(
            icon = MuseIcons.play,
            title = stringResource(R.string.automation_autonomous_click),
            checked = AutomationAction.CLICK in draft.allowedActions,
            onCheckedChange = {
                onDraftChange(draft.toggleAction(AutomationAction.CLICK))
            },
        )
        SettingsGroupDivider()
        SettingsSwitchRow(
            icon = MuseIcons.arrowUp,
            title = stringResource(R.string.automation_autonomous_scroll),
            checked = AutomationAction.SCROLL in draft.allowedActions,
            onCheckedChange = {
                onDraftChange(draft.toggleAction(AutomationAction.SCROLL))
            },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.shieldCheck,
            title = stringResource(R.string.automation_autonomous_input_locked),
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.timer,
            title = stringResource(R.string.automation_autonomous_daily_limit),
            subtitle = stringResource(R.string.automation_autonomous_daily_limit_desc),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MuseCapsuleButton(
                    text = "-",
                    onClick = { onDraftChange(draft.copy(maxRunsPerDay = (draft.maxRunsPerDay - 1).coerceAtLeast(1))) },
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = "+",
                    onClick = { onDraftChange(draft.copy(maxRunsPerDay = (draft.maxRunsPerDay + 1).coerceAtMost(100))) },
                    fillWidth = false,
                )
            }
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.timer,
            title = stringResource(R.string.automation_autonomous_daily_limit),
            subtitle = draft.maxRunsPerDay.toString(),
        )
    }

    Spacer(Modifier.height(8.dp))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.automation_autonomous_task_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (taskPreview != null) {
                Text(
                    text = stringResource(R.string.automation_autonomous_task_text, taskPreview.message.take(180)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text =
                if (grant == null) {
                    stringResource(R.string.automation_autonomous_no_grant)
                } else {
                    stringResource(R.string.automation_autonomous_active_until)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            MuseCapsuleButton(
                text = stringResource(R.string.automation_autonomous_confirm_task),
                onClick = onConfirmTask,
                enabled = taskPreview != null,
                leadingIcon = MuseIcons.check,
            )
            if (grant != null) {
                Spacer(Modifier.height(6.dp))
                MuseCapsuleButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onClearTask,
                    variant = io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Secondary,
                    leadingIcon = MuseIcons.x,
                )
            }
        }
        SettingsGroupDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = stringResource(R.string.automation_autonomous_packages),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.automation_autonomous_packages_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            MuseTextField(
                value = packageEditor,
                onValueChange = onPackageEditorChange,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    MuseCapsuleButton(
        text = stringResource(R.string.automation_autonomous_save),
        onClick = onSave,
        leadingIcon = MuseIcons.check,
    )

    if (editingStart) {
        val state =
            rememberTimePickerState(
                initialHour = draft.startMinute / 60,
                initialMinute = draft.startMinute % 60,
                is24Hour = true,
            )
        MuseDialog(
            onDismissRequest = { editingStart = false },
            title = stringResource(R.string.automation_autonomous_start),
            content = { TimePicker(state = state) },
            onConfirm = {
                onDraftChange(draft.copy(startMinute = state.hour * 60 + state.minute))
                editingStart = false
            },
            onDismiss = { editingStart = false },
        )
    }
    if (editingEnd) {
        val state =
            rememberTimePickerState(
                initialHour = draft.endMinute / 60,
                initialMinute = draft.endMinute % 60,
                is24Hour = true,
            )
        MuseDialog(
            onDismissRequest = { editingEnd = false },
            title = stringResource(R.string.automation_autonomous_end),
            content = { TimePicker(state = state) },
            onConfirm = {
                onDraftChange(draft.copy(endMinute = state.hour * 60 + state.minute))
                editingEnd = false
            },
            onDismiss = { editingEnd = false },
        )
    }
}
