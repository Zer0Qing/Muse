package io.zer0.muse.ui.artifact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.CodeHighlighter
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard

/**
 * 产物卡片 —— v2.0.1 大卡范式（全宽卡片：头部 / 预览区 / 信息行）。
 *
 * - 网页类（html / svg / chart / mermaid）：占位条提示可点按预览网页，
 *   点击后进全屏浏览器形态（SettingsSearchBridge 同一套全屏预览）；
 * - 代码类：等宽字体前 6 行片段；
 * - 文本 / 其他：前 4 行文本预览。
 *
 * 整卡可点（打开查看器 / 全屏浏览器）；分享、另存等操作在查看器内提供。
 * 旧版为 180dp 固定宽小卡，信息薄、与"文件"心智不符（用户反馈"太丑/毛坯"）。
 */
@Composable
fun ArtifactCard(
    artifact: ArtifactEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = artifactMetaLabel(type = artifact.type, language = artifact.language)
    val isWeb = richPreviewLanguage(artifact.type, artifact.language) != null
    val isCode = artifact.type.lowercase() == "code"
    val charCount = artifact.content.length

    MuseSurface(
        onClick = onClick,
        shape = MuseShapes.largeCard,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
            },
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 头部：类型图标 + 标题 + 类型徽标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = artifactTypeIcon(artifact.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 预览区
            if (isWeb) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MuseShapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.languages,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.artifact_open_web_preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v2.0.1: 内容摘要 — 去标签后的首段文本(快速了解页面内容)
                val plainPreview = remember(artifact.content) {
                    io.zer0.muse.util.stripHtmlComprehensive(artifact.content)
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(80)
                }
                if (plainPreview.isNotBlank()) {
                    Text(
                        text = plainPreview,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                val previewText = remember(artifact.content, isCode) {
                    artifact.content.lines().take(if (isCode) 6 else 4).joinToString("\n")
                }
                // v2.0.1: 代码片段走语法高亮（复用 CodeHighlighter）
                val highlightedPreview = if (isCode) {
                    CodeHighlighter.highlight(previewText, artifact.language)
                } else {
                    null
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MuseShapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = highlightedPreview ?: androidx.compose.ui.text.AnnotatedString(previewText),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (isCode) MuseMonoFontFamily else null,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isCode) 6 else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 信息行
            Text(
                text = stringResource(R.string.artifact_char_count, charCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 列表超过该数量时,尾部显示 "+N" 折叠卡片(由 [ArtifactCardList] 使用)。 */
internal const val MAX_VISIBLE_ARTIFACT_CARDS = 3

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

/** 超出 [MAX_VISIBLE_ARTIFACT_CARDS] 时尾部的 "+N" 折叠条,点击展开全部。 */
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
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun artifactTypeIcon(type: String) = when (type.lowercase()) {
    "code" -> MuseIcons.code
    "image" -> MuseIcons.image
    "html", "svg" -> MuseIcons.languages
    else -> MuseIcons.fileText
}
