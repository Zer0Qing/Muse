package io.zer0.muse.automation.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.automation.core.AutomationAction
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import io.zer0.muse.ui.chat.ChatViewModel
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing

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

    // 所有行收进同一张卡片：行与行之间的发丝分隔线由 CardGroup 统一补，
    // 各行自己不再画线，增删一行也不会漏掉或重复一条分隔线。
    CardGroup {
        AutonomousIntroItem()
        switchItem(
            key = "autonomous_enabled",
            title = stringResource(R.string.automation_autonomous_enabled),
            subtitle = stringResource(R.string.automation_autonomous_enabled_desc),
            checked = draft.enabled,
            onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
            leadingContent = { AutonomousLeadingIcon(MuseIcons.shield) },
        )
        // 时段行用普通 item 而不是 navigationItem：点开的是时间选择对话框，不是新页面，
        // 右侧挂箭头会承诺一次页面跳转，用户点完发现只弹了个对话框。
        item(
            key = "autonomous_start",
            onClick = { editingStart = true },
            leadingContent = { AutonomousLeadingIcon(MuseIcons.clock) },
            headlineContent = {
                Text(
                    text = stringResource(R.string.automation_autonomous_start),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { TimeValueText(draft.startMinute) },
        )
        item(
            key = "autonomous_end",
            onClick = { editingEnd = true },
            leadingContent = { AutonomousLeadingIcon(MuseIcons.clock) },
            headlineContent = {
                Text(
                    text = stringResource(R.string.automation_autonomous_end),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { TimeValueText(draft.endMinute) },
        )
        AutonomousActionItems(
            allowed = draft.allowedActions,
            onToggle = { action -> onDraftChange(draft.toggleAction(action)) },
        )
        // 标题、说明与步进器同处一行：说明文字有近 30 字，若只塞进 item 的 trailing 槽，
        // 文字列会被两侧控件压到十来个字宽。多字段块走 content，才能让说明占满剩余宽度。
        content(key = "autonomous_daily_limit") {
            DailyRunLimitRow(
                maxRunsPerDay = draft.maxRunsPerDay,
                onRunsChange = { onDraftChange(draft.copy(maxRunsPerDay = it)) },
            )
        }
        content(key = "autonomous_task") {
            AutonomousTaskSection(
                taskPreview = taskPreview,
                grant = grant,
                onConfirmTask = onConfirmTask,
                onClearTask = onClearTask,
            )
        }
        content(key = "autonomous_packages") {
            AutonomousPackagesSection(
                packageEditor = packageEditor,
                onPackageEditorChange = onPackageEditorChange,
            )
        }
    }

    Spacer(Modifier.height(MusePaddings.cardGap))
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

/**
 * 卡片首行：说明「按时段自主使用无障碍」到底允许什么。
 *
 * 不可点击，所以用静态 item 而不是 switchItem —— 给一段纯说明挂上开关语义，
 * 用户会以为点一下就启用了。
 */
@Composable
private fun CardGroupScope.AutonomousIntroItem() {
    item(
        key = "autonomous_intro",
        leadingContent = { AutonomousLeadingIcon(MuseIcons.shield) },
        headlineContent = {
            Text(
                text = stringResource(R.string.automation_autonomous_title),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            // 这段是「自主操作到底被允许做什么」的边界说明，宁可整段换行也不能截断 ——
            // 少读一行就会以为输入/发布类动作也在这个白名单里。宽度已由 MuseListItem
            // 的 weight(1f) 文字列限制，不必再靠 maxLines 兜底。
            Text(text = stringResource(R.string.automation_autonomous_desc))
        },
    )
}

/**
 * 卡片行首图标。
 *
 * 取 24dp 档：卡片内分隔线的缩进按「行内边距 + 24dp 图标 + 行内边距」计算，
 * 换成更小的档位会让每条分隔线相对文字列左移，看起来像没对齐。
 */
@Composable
private fun AutonomousLeadingIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(MuseIconSizes.icon),
    )
}

/** 行尾的当前时刻（`HH:mm`）。 */
@Composable
private fun TimeValueText(minuteOfDay: Int) {
    Text(
        text = formatMinuteOfDay(minuteOfDay),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 每日执行上限：说明、`-`、数值、`+` 同处一行。
 *
 * 数值夹在两个按钮中间（而不是另起一行显示），否则两行都写着「每日最多执行」，
 * 用户会以为存在两条互相独立的设置。
 */
@Composable
private fun DailyRunLimitRow(maxRunsPerDay: Int, onRunsChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 本行带行首图标，边距取与 MuseListItem 同源的一组：
            // 用 cardInner 会让这一行的图标比上下两侧行左移 2dp，一眼能看出错位。
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.inputPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutonomousLeadingIcon(MuseIcons.timer)
        // 图标右侧留同宽间距，文字起点才与同卡其余行的标题列重合。
        Spacer(Modifier.width(MusePaddings.screen))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.automation_autonomous_daily_limit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.automation_autonomous_daily_limit_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(MusePaddings.contentGap))
        // 加减保留字面 「-」/「+」 而不是换成纯图标按钮：这一行靠「12」居中的三段式读法
        // 让人一眼看出「中间是当前值、左右能改」，两个图标会让数字失去参照物。
        MuseCapsuleButton(
            text = "-",
            onClick = { onRunsChange((maxRunsPerDay - 1).coerceAtLeast(1)) },
            fillWidth = false,
        )
        Spacer(Modifier.width(MuseSpacing.xs))
        // 数值取最小宽度而不是固定宽度：1 → 100 变化时按钮不跳动，三位数仍能自然撑开。
        Text(
            text = maxRunsPerDay.toString(),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = MuseSpacing.xxxl),
        )
        Spacer(Modifier.width(MuseSpacing.xs))
        MuseCapsuleButton(
            text = "+",
            onClick = { onRunsChange((maxRunsPerDay + 1).coerceAtMost(100)) },
            fillWidth = false,
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
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
        Spacer(Modifier.height(MusePaddings.contentGap))
        MuseCapsuleButton(
            text = stringResource(R.string.automation_autonomous_confirm_task),
            onClick = onConfirmTask,
            enabled = taskPreview != null,
            leadingIcon = MuseIcons.check,
        )
        if (grant != null) {
            Spacer(Modifier.height(MuseSpacing.smTight))
            MuseCapsuleButton(
                text = stringResource(R.string.common_cancel),
                onClick = onClearTask,
                variant = IosCapsuleButtonVariant.Secondary,
                leadingIcon = MuseIcons.x,
            )
        }
    }
}

/** 允许自动化的包名编辑区（一行一个）。 */
@Composable
private fun AutonomousPackagesSection(packageEditor: String, onPackageEditorChange: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Text(
            text = stringResource(R.string.automation_autonomous_packages),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.automation_autonomous_packages_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MuseSpacing.smTight))
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
@Composable
private fun AutonomousTimePickerDialog(title: String, initialMinute: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    io.zer0.muse.ui.common.form.MuseTimePickerDialog(
        title = title,
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        onDismiss = onDismiss,
        onConfirm = { hour, minute -> onPicked(hour * 60 + minute) },
    )
}

/**
 * 四个动作开关（读屏 / 点击 / 滚动 / 输入）。
 *
 * 原来是一个 26 行的复制四遍：图标、标题、`checked`、`onCheckedChange` 全同，
 * 只有四个常量不同 —— 加第五个动作时必然忘抄一条分隔线或写错 checked 表达式。
 * 现在加动作只需往 [ActionSpec] 里添一行；行间分隔线由 [CardGroup] 自动补。
 */
@Composable
private fun CardGroupScope.AutonomousActionItems(allowed: Set<AutomationAction>, onToggle: (AutomationAction) -> Unit) {
    ActionSpec.entries.forEach { spec ->
        switchItem(
            key = spec.action.name,
            title = stringResource(spec.titleRes),
            checked = spec.action in allowed,
            onCheckedChange = { onToggle(spec.action) },
            leadingContent = { AutonomousLeadingIcon(spec.icon) },
        )
    }
}

/** [AutonomousActionItems] 的数据表：动作、文案资源与图标的唯一对应关系。 */
private enum class ActionSpec(
    val action: AutomationAction,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    READ_SCREEN(AutomationAction.READ_SCREEN, R.string.automation_autonomous_read, MuseIcons.bookOpen),
    CLICK(AutomationAction.CLICK, R.string.automation_autonomous_click, MuseIcons.play),
    SCROLL(AutomationAction.SCROLL, R.string.automation_autonomous_scroll, MuseIcons.arrowUp),
    INPUT(AutomationAction.INPUT, R.string.automation_autonomous_input, MuseIcons.edit),
}
