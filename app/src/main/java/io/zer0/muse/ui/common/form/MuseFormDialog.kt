@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import androidx.compose.ui.res.stringResource

/**
 * 统一的短表单弹窗。
 *
 * 用于少量字段的编辑/选择。内容统一左对齐、控件之间保持固定间距，
 * 确认按钮支持禁用态；较长表单应升级为 [MuseBottomSheet] 或独立页面。
 */
@Composable
fun MuseFormDialog(
    onDismissRequest: () -> Unit,
    title: String,
    subtitle: String? = null,
    confirmText: String = stringResource(R.string.common_confirm),
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    dismissText: String? = stringResource(R.string.common_cancel),
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    )
                }
                content()
            }
        },
        confirmText = confirmText,
        confirmEnabled = confirmEnabled,
        onConfirm = onConfirm,
        dismissText = dismissText,
        onDismiss = onDismiss,
        destructive = destructive,
    )
}
