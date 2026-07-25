package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.export.ExportFormat
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.SegmentedControl

/**
 * 导出格式选择对话框(Markdown / HTML / PDF 三选一)。
 *
 * 设计要点:
 *  - iOS 风格分段选择器([SegmentedControl])做格式切换
 *  - 选中后点击底部"导出"按钮触发回调 [onFormatSelected]
 *  - 每种格式下方显示简短说明,帮助用户理解差异
 *
 * 用法:
 * ```
 * if (showDialog) {
 *     ExportFormatPickerDialog(
 *         onDismiss = { showDialog = false },
 *         onFormatSelected = { format ->
 *             showDialog = false
 *             // 调用对应导出方法
 *         },
 *     )
 * }
 * ```
 *
 * @param onDismiss 关闭对话框回调(取消 / 外部点击)
 * @param onFormatSelected 用户确认导出格式后的回调
 */
@Composable
fun ExportFormatPickerDialog(
    onDismiss: () -> Unit,
    onFormatSelected: (ExportFormat) -> Unit,
) {
    // 当前选中的格式索引,默认 Markdown
    var selectedIndex by remember { mutableStateOf(0) }
    val formats = listOf(ExportFormat.MARKDOWN, ExportFormat.HTML, ExportFormat.PDF)
    val labels = listOf("Markdown", "HTML", "PDF")

    MuseDialog(
        onDismissRequest = onDismiss,
        title = "选择导出格式",
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // iOS 风格分段选择器
                SegmentedControl(
                    options = labels,
                    selectedIndex = selectedIndex,
                    onSelectedChange = { selectedIndex = it },
                )
                // 当前格式的简短说明
                Text(
                    text = formatDescription(formats[selectedIndex]),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = "导出",
        onConfirm = {
            onFormatSelected(formats[selectedIndex])
        },
        dismissText = "取消",
        onDismiss = onDismiss,
    )
}

/** 返回各导出格式的简短中文说明。 */
private fun formatDescription(format: ExportFormat): String = when (format) {
    ExportFormat.MARKDOWN -> "Markdown(.md):纯文本格式,便于粘贴到笔记软件或再次编辑"
    ExportFormat.HTML -> "HTML(.html):单文件,内联样式与图片,可直接用浏览器打开分享"
    ExportFormat.PDF -> "PDF(.pdf):A4 分页文档,适合打印或正式归档"
}
