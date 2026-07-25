package io.zer0.muse.ui.artifact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 产物查看器弹窗 —— 显示标题、完整内容与复制按钮。
 */
@Composable
fun ArtifactViewerDialog(
    artifact: ArtifactEntity,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val isMonospace = when (artifact.type.lowercase()) {
        "code" -> true
        else -> false
    }
    // v1.62: svg/html 用 RichContentCard 渲染(WebView),不再显示纯代码
    val isRichContent = artifact.type.lowercase() in listOf("svg", "html", "chart", "mermaid")

    MuseDialog(
        onDismissRequest = onDismiss,
        title = null,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = artifactTypeIcon(artifact.type),
                        contentDescription = artifact.type,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = artifact.title.ifBlank { "未命名" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // 内容区
                Surface(
                    color = if (isMonospace) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = MuseShapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    if (isRichContent) {
                        // v1.62: svg/html/chart/mermaid 用 RichContentCard 渲染
                        // 弹窗内无 navController,禁用全屏预览按钮(避免无效点击)
                        io.zer0.muse.ui.markdown.RichContentCard(
                            language = artifact.type.lowercase(),
                            content = artifact.content,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            showPreviewButton = false,
                        )
                    } else {
                        Text(
                            text = artifact.content,
                            style = if (isMonospace) {
                                MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = MuseMonoFontFamily,
                                )
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                // v1.0.7 修复:去掉嵌套 verticalScroll(MuseDialog content 已自带滚动)
                                .fillMaxWidth()
                                .padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmText = "复制",
        // L6 已知限制: 复制未标注敏感(artifact 内容可能含敏感信息),暂不区分;后续可结合内容检测判断。
        onConfirm = { onCopy(artifact.content) },
        dismissText = null,
    )
}
