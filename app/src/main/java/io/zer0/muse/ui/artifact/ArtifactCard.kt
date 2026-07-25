package io.zer0.muse.ui.artifact

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 产物卡片 —— iOS 风格圆角卡片,显示类型图标、标题与内容预览。
 */
@Composable
fun ArtifactCard(
    artifact: ArtifactEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // M-AC1 修复: preview 用 remember(artifact.content) 缓存,避免每次重组都执行 lineSequence()
    val preview = remember(artifact.content) {
        artifact.content.lineSequence().firstOrNull()?.take(60)
            ?: artifact.content.take(60)
    }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .width(180.dp)
            // L-AC2 修复: clickable 加 role=Role.Button 无障碍语义
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
            },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = artifactTypeIcon(artifact.type),
                    contentDescription = artifact.type,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = artifact.title.ifBlank { "未命名" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun artifactTypeIcon(type: String) = when (type.lowercase()) {
    "code" -> Icons.Default.Code
    "image" -> Icons.Default.Image
    "html", "svg" -> Icons.Default.Language
    else -> Icons.AutoMirrored.Filled.Article
}
