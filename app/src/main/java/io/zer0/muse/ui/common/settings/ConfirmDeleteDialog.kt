package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 删除确认对话框(iOS 风格删除确认)— 全站**唯一**的删除确认组件(MEM-04)。
 *
 * 统一句式(三行制):
 *  1. 标题:动作(如"删除 Lorebook")
 *  2. `确定删除「{对象名}」?` — **点名对象**
 *  3. 后果说明 [consequence] — 说明"删了会怎样";未提供时退化为通用"此操作不可撤销。"
 * 主键固定 destructive(error 红),次键为取消。
 *
 * MEM-04 统一说明:此前三套措辞并存 —— 本组件([R.string.common_confirm_delete_message])、
 * `MemoryScreen` 的 `memory_delete_confirm_message`、`MomentCard` 的 `moment_delete_confirm`;
 * 现在后两处也改用本组件并各自给出点名文案 + 后果说明,不再各写一套句式。
 *
 * 用法:
 * ```kotlin
 * ConfirmDeleteDialog(
 *     title = stringResource(R.string.assistant_delete_title),
 *     itemName = assistant.name,
 *     consequence = stringResource(R.string.assistant_delete_consequence),
 *     onConfirm = { repo.delete(assistant.id) },
 *     onDismiss = { target = null },
 * )
 * ```
 *
 * @param title 对话框标题(如"删除 Lorebook")
 * @param itemName 被删除项名称(**必须点名**,显示在"确定删除「X」?")
 * @param onConfirm 确认删除回调(已包含删除逻辑 + 关闭对话框)
 * @param onDismiss 取消回调(关闭对话框)
 * @param consequence 后果说明(点名对象后必填,如"其对话与记忆也会删除");为空时用通用文案
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    consequence: String? = null,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.common_confirm_delete_message, itemName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(MusePaddings.tightGap))
                Text(
                    text = consequence
                        ?: stringResource(R.string.common_confirm_delete_consequence_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmText = stringResource(R.string.common_delete),
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
        destructive = true,
    )
}
