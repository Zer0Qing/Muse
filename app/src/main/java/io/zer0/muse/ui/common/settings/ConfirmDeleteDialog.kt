package io.zer0.muse.ui.common.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog

/**
 * 删除确认对话框(iOS 风格删除确认)。
 *
 * 在 AssistantScreen / LorebookScreen / QuickMessageScreen / PromptInjectionScreen
 * 等 4 个页面重复使用的"确定删除 X 吗"对话框。
 *
 * @param title 对话框标题(如"删除 Lorebook")
 * @param itemName 被删除项名称(显示在"确定删除 \"X\" 吗?")
 * @param onConfirm 确认删除回调(已包含删除逻辑 + 关闭对话框)
 * @param onDismiss 取消回调(关闭对话框)
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = { Text(stringResource(R.string.common_confirm_delete_message, itemName)) },
        confirmText = stringResource(R.string.common_delete),
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
        destructive = true,
    )
}
