@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.theme.MuseButtonSize

/**
 * 表单型设置子页骨架。
 *
 * 在 [SettingsSubPageScaffold] 之上补齐表单页的三件固定行为：
 *  - 顶栏右侧一枚紧凑「保存」按钮（有改动时为主色，无改动时为次要色），页面里不再放底部通栏保存条；
 *  - 有未保存改动时，返回键 / 手势返回先弹「放弃修改」确认，避免误退丢编辑；
 *  - 保存与放弃之后的跳转由调用方在 [onSave] / [onDismiss] 里决定。
 *
 * @param dirty 当前表单相对已保存值是否有改动
 * @param onSave 点击保存
 * @param onDismiss 确认离开（无改动直接返回，或用户选择放弃修改）
 * @param saveEnabled 保存按钮是否可点（例如必填项校验未过时传 false）
 * @param extraActions 保存按钮左侧的附加顶栏操作
 */
@Composable
fun SettingsFormScaffold(
    title: String,
    dirty: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    saveEnabled: Boolean = true,
    extraActions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val handleBack: () -> Unit = {
        if (dirty) showDiscardConfirm = true else onDismiss()
    }
    BackHandler(onBack = handleBack)
    if (showDiscardConfirm) {
        MuseDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = stringResource(R.string.settings_unsaved_changes_title),
            content = { Text(stringResource(R.string.settings_unsaved_changes_content)) },
            confirmText = stringResource(R.string.settings_unsaved_discard),
            onConfirm = {
                showDiscardConfirm = false
                onDismiss()
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showDiscardConfirm = false },
            destructive = true,
        )
    }
    SettingsSubPageScaffold(
        title = title,
        onBack = handleBack,
        headerActions = {
            extraActions?.invoke(this)
            MuseCapsuleButton(
                text = stringResource(R.string.common_save),
                onClick = onSave,
                enabled = saveEnabled,
                variant = if (dirty) IosCapsuleButtonVariant.Primary else IosCapsuleButtonVariant.Secondary,
                size = MuseButtonSize.Compact,
                fillWidth = false,
            )
        },
        content = content,
    )
}
