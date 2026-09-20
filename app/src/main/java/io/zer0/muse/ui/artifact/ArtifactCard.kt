package io.zer0.muse.ui.artifact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 产物卡片 —— iOS 风格圆角卡片,显示类型图标、标题与内容预览。
 *
 * Phase 2 补齐: 标题下方补充类型/来源(语言)元信息,均取自 [ArtifactEntity] 已有字段。
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
    val meta = artifactMetaLabel(type = artifact.type, language = artifact.language)

    // Phase 2: 统一走项目容器基元 [MuseSurface](自带按压反馈 + Button role),
    // 保留原 Surface 的圆角/底色/tonalElevation 与固定 180dp 宽度。
    // L-AC2 修复的 mergeDescendants 无障碍语义继续保留。
    MuseSurface(
        onClick = onClick,
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .width(180.dp)
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
                    // 类型由下方 meta 文本(本地化)表达,图标不再重复播报
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) }, // 前端修复 (i18n-3)
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // Phase 2: 类型 + 来源(语言)元信息,帮助区分同名卡片
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
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

/** 列表超过该数量时,尾部显示 "+N" 折叠卡片(由 [ArtifactCardList] 使用)。 */
internal const val MAX_VISIBLE_ARTIFACT_CARDS = 5

/** 产物类型 + 来源(代码语言)组合文案,如 "代码 · Kotlin"。 */
@Composable
internal fun artifactMetaLabel(type: String, language: String?): String {
    val typeLabel = artifactTypeLabel(type)
    val source = language?.takeIf { it.isNotBlank() } ?: return typeLabel
    return "$typeLabel · $source"
}

/** 产物类型的本地化展示名(未知类型回退为原始 type,空类型显示"其他")。 */
@Composable
internal fun artifactTypeLabel(type: String): String = when (type.lowercase()) {
    "code" -> stringResource(R.string.artifact_type_code)
    "image" -> stringResource(R.string.artifact_type_image)
    "html" -> stringResource(R.string.artifact_type_html)
    "svg" -> stringResource(R.string.artifact_type_svg)
    "chart" -> stringResource(R.string.artifact_type_chart)
    "mermaid" -> stringResource(R.string.artifact_type_mermaid)
    "text", "markdown" -> stringResource(R.string.artifact_type_text)
    else -> type.ifBlank { stringResource(R.string.artifact_type_other) }
}

/** 超出 [MAX_VISIBLE_ARTIFACT_CARDS] 时尾部的 "+N" 折叠卡片,点击展开全部。 */
@Composable
internal fun ArtifactOverflowCard(
    hiddenCount: Int,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.artifact_more_count, hiddenCount)
    MuseSurface(
        onClick = onClick,
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .size(72.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+$hiddenCount",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
