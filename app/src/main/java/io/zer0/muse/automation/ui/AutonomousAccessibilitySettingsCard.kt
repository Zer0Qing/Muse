package io.zer0.muse.automation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
        AutonomousActionRows(
            allowed = draft.allowedActions,
            onToggle = { action -> onDraftChange(draft.toggleAction(action)) },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.timer,
            title = stringResource(R.string.automation_autonomous_daily_limit),
            subtitle = stringResource(R.string.automation_autonomous_daily_limit_desc),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                MuseCapsuleButton(
                    text = "-",
                    onClick = { onDraftChange(draft.copy(maxRunsPerDay = (draft.maxRunsPerDay - 1).coerceAtLeast(1))) },
                    fillWidth = false,
                )
                // 数字放在两个按钮中间：以前这里是"按钮一行 + 数字另起一行"，
                // 两行标题都叫「每日最多执行」，用户会以为有两条独立的设置。
                Text(
                    text = draft.maxRunsPerDay.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(36.dp),
                )
                MuseCapsuleButton(
                    text = "+",
                    onClick = { onDraftChange(draft.copy(maxRunsPerDay = (draft.maxRunsPerDay + 1).coerceAtMost(100))) },
                    fillWidth = false,
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    SettingsGroup {
        AutonomousTaskSection(
            taskPreview = taskPreview,
            grant = grant,
            onConfirmTask = onConfirmTask,
            onClearTask = onClearTask,
        )
        SettingsGroupDivider()
        AutonomousPackagesSection(
            packageEditor = packageEditor,
            onPackageEditorChange = onPackageEditorChange,
        )
    }

    Spacer(Modifier.height(8.dp))
    MuseCapsuleButton(
        text = stringResource(R.string.automation_autonomous_save),
        onClick = onSave,
        leadingIcon = MuseIcons.check,
    )

    if (editingStart) {
        AutonomousTimePickerDialog(
            title = stringResource(R.string.automation_autonomous_start),
            initialMinute = draft.startMinute,
            onDismiss = { editingStart = false },
            onPicked = { minute -> onDraftChange(draft.copy(startMinute = minute)) },
        )
    }
    if (editingEnd) {
        AutonomousTimePickerDialog(
            title = stringResource(R.string.automation_autonomous_end),
            initialMinute = draft.endMinute,
            onDismiss = { editingEnd = false },
            onPicked = { minute -> onDraftChange(draft.copy(endMinute = minute)) },
        )
    }
}

/** 任务授权那一块：说清"这次要让助手自动做什么"，以及当前授权在不在。 */
@Composable
private fun AutonomousTaskSection(
    taskPreview: ChatViewModel.AutonomousAccessibilityTaskPreview?,
    grant: AutonomousAccessibilityGrant?,
    onConfirmTask: () -> Unit,
    onClearTask: () -> Unit,
) {
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
}

/** 允许自动化的包名编辑区（一行一个）。 */
@Composable
private fun AutonomousPackagesSection(packageEditor: String, onPackageEditorChange: (String) -> Unit) {
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

/**
 * 时间点选择对话框（起始与结束共用）。
 *
 * 只取"分钟数进、分钟数出"这一层抽象：起始/结束本来就同一件事的两个字段，
 * 两份 18 行的复制唯一区别就是字段名 —— 复制两份就意味着改一处忘一处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutonomousTimePickerDialog(title: String, initialMinute: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = { TimePicker(state = state) },
        onConfirm = {
            onPicked(state.hour * 60 + state.minute)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/**
 * 三个动作开关（读屏 / 点击 / 滚动）。
 *
 * 原来是一个 26 行的复制三遍：图标、标题、`checked`、`onCheckedChange` 全同，
 * 只有三个常量不同 —— 加第四个动作时必然忘抄一条分隔线或写错 checked 表达式。
 * 现在加动作只需往表里添一行。
 */
@Composable
private fun AutonomousActionRows(allowed: Set<AutomationAction>, onToggle: (AutomationAction) -> Unit) {
    data class Spec(val action: AutomationAction, val titleRes: Int, val icon: ImageVector)

    val specs = listOf(
        Spec(AutomationAction.READ_SCREEN, R.string.automation_autonomous_read, MuseIcons.bookOpen),
        Spec(AutomationAction.CLICK, R.string.automation_autonomous_click, MuseIcons.play),
        Spec(AutomationAction.SCROLL, R.string.automation_autonomous_scroll, MuseIcons.arrowUp),
        Spec(AutomationAction.INPUT, R.string.automation_autonomous_input, MuseIcons.edit),
    )
    specs.forEachIndexed { index, spec ->
        if (index > 0) SettingsGroupDivider()
        SettingsSwitchRow(
            icon = spec.icon,
            title = stringResource(spec.titleRes),
            checked = spec.action in allowed,
            onCheckedChange = { onToggle(spec.action) },
        )
    }
}
