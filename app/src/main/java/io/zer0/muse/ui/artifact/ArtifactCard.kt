package io.zer0.muse.ui.artifact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.chat.SmartImage
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.CodeHighlighter
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.largeCard
import io.zer0.muse.util.stripHtmlComprehensive

/**
 * 产物卡片 —— 按形态区分预览（图片缩略图 / 富网页提示 / 代码高亮片段 / 文本摘要）。
 */
@Composable
fun ArtifactCard(
    artifact: ArtifactEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metaExtra: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    val meta = artifactMetaLabel(type = artifact.type, language = artifact.language)
    val richLang = richPreviewLanguage(artifact.type, artifact.language)
    val mode = remember(artifact.type, artifact.language) { artifactRenderMode(artifact.type, artifact.language) }
    val charCount = artifact.content.length

    MuseSurface(
        onClick = onClick,
        shape = MuseShapes.largeCard,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = MuseElevation.card,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
            },
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                Icon(
                    imageVector = artifactTypeIcon(artifact.type, artifact.language),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
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
                if (trailingAction != null) {
                    trailingAction()
                }
            }

            ArtifactCardPreview(artifact, mode, richLang)
            ArtifactCardMeta(mode, charCount, metaExtra)
        }
    }
}

@Composable
private fun ArtifactCardPreview(artifact: ArtifactEntity, mode: ArtifactRenderMode, richLang: String?) {
    when (mode) {
        ArtifactRenderMode.Image -> {
            val model = remember(artifact.content) { artifact.content.trim() }
            SmartImage(
                model = model,
                contentDescription = artifact.title.ifBlank { stringResource(R.string.artifact_type_image) },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MusePaddings.maxInlineWidth)
                    .clip(MuseShapes.small),
            )
        }
        ArtifactRenderMode.RichWeb -> ArtifactCardRichPreview(artifact, richLang)
        ArtifactRenderMode.Code,
        ArtifactRenderMode.Markdown,
        ArtifactRenderMode.PlainText,
        -> ArtifactCardTextPreview(artifact, mode)
    }
}

@Composable
private fun ArtifactCardRichPreview(artifact: ArtifactEntity, richLang: String?) {
    val promptLabelRes = when (richLang) {
        "chart" -> R.string.artifact_open_chart_preview
        "mermaid" -> R.string.artifact_open_mermaid_preview
        else -> R.string.artifact_open_web_preview
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.mdTight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        Icon(
            imageVector = artifactTypeIcon(artifact.type, artifact.language),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MuseIconSizes.iconSmallTiny),
        )
        Text(
            text = stringResource(promptLabelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (richLang == "html" || richLang == "svg") {
        val plainPreview = remember(artifact.content) {
            stripHtmlComprehensive(artifact.content).replace(Regex("\\s+"), " ").trim().take(80)
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
    }
}

@Composable
private fun ArtifactCardTextPreview(artifact: ArtifactEntity, mode: ArtifactRenderMode) {
    val isCode = mode == ArtifactRenderMode.Code
    val codeLanguage = artifact.language?.takeIf(String::isNotBlank)
        ?: artifact.type.lowercase().takeIf { it == "json" }
    val previewText = remember(artifact.content, isCode) {
        if (isCode) artifact.content.lines().take(6).joinToString("\n") else artifact.content.toPreviewText()
    }
    val highlightedPreview = if (isCode) CodeHighlighter.highlight(previewText, codeLanguage) else null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = MuseSpacing.md, vertical = MuseSpacing.mdTight),
    ) {
        Text(
            text = highlightedPreview ?: AnnotatedString(previewText),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (isCode) MuseMonoFontFamily else null),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isCode) 6 else 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun String.toPreviewText(): String = lines().asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .filterNot { it.matches(Regex("^[|\\s:-]{3,}$")) }
    .map { line ->
        if (line.startsWith("|")) {
            line.trim('|').split("|").map(String::trim).filter(String::isNotEmpty).joinToString(" · ")
        } else {
            line.removePrefix("#").trim()
        }
    }
    .filter(String::isNotEmpty)
    .take(4)
    .joinToString("\n")

@Composable
private fun ArtifactCardMeta(mode: ArtifactRenderMode, charCount: Int, metaExtra: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (mode == ArtifactRenderMode.Image) {
                stringResource(R.string.artifact_type_image)
            } else {
                stringResource(R.string.artifact_char_count, charCount)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (!metaExtra.isNullOrBlank()) {
            Text(
                text = metaExtra,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 列表超过该数量时，尾部显示 "+N" 折叠卡片(由 [ArtifactCardList] 使用)。 */
internal const val MAX_VISIBLE_ARTIFACT_CARDS = 3

/** 产物类型 + 语言组合文案；自动入库的 `rich_card` 按真实语言展示，避免裸露内部标识。 */
@Composable
internal fun artifactMetaLabel(type: String, language: String?): String {
    val normType = type.lowercase().trim()
    val normLang = language?.lowercase()?.trim().orEmpty()
    if (normType == "rich_card") {
        val cardSuffix = stringResource(R.string.artifact_type_card)
        return when (normLang) {
            "html" -> "${stringResource(R.string.artifact_type_html)}$cardSuffix"
            "svg" -> "SVG $cardSuffix"
            "chart" -> "${stringResource(R.string.artifact_type_chart)}$cardSuffix"
            "mermaid" -> "${stringResource(R.string.artifact_type_mermaid)}$cardSuffix"
            else -> cardSuffix
        }
    }
    val typeLabel = artifactTypeLabel(type)
    val source = language?.takeIf { it.isNotBlank() } ?: return typeLabel
    if (source.equals(normType, ignoreCase = true) || typeLabel.equals(source, ignoreCase = true)) {
        return typeLabel
    }
    return "$typeLabel · $source"
}

/** 产物类型的本地化展示名。 */
@Composable
internal fun artifactTypeLabel(type: String): String = when (type.lowercase().trim()) {
    "code" -> stringResource(R.string.artifact_type_code)
    "image" -> stringResource(R.string.artifact_type_image)
    "html" -> stringResource(R.string.artifact_type_html)
    "svg" -> stringResource(R.string.artifact_type_svg)
    "chart" -> stringResource(R.string.artifact_type_chart)
    "mermaid" -> stringResource(R.string.artifact_type_mermaid)
    "rich_card" -> stringResource(R.string.artifact_type_card)
    "document" -> stringResource(R.string.artifact_type_document)
    "table" -> stringResource(R.string.artifact_type_table)
    "json" -> stringResource(R.string.artifact_type_json)
    "csv" -> stringResource(R.string.artifact_type_csv)
    "text", "markdown" -> stringResource(R.string.artifact_type_text)
    else -> type.ifBlank { stringResource(R.string.artifact_type_other) }
}

/** 超出 [MAX_VISIBLE_ARTIFACT_CARDS] 时尾部的 "+N" 折叠条，点击展开全部。 */
@Composable
internal fun ArtifactOverflowCard(hiddenCount: Int, onClick: () -> Unit) {
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
                .padding(vertical = MuseSpacing.mdTight),
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
internal fun artifactTypeIcon(type: String, language: String? = null): ImageVector {
    val richLang = richPreviewLanguage(type, language)
    if (richLang == "html" || richLang == "svg") return MuseIcons.globe
    if (richLang == "chart" || richLang == "mermaid") return MuseIcons.chartBar
    return when (type.lowercase().trim()) {
        "code", "json" -> MuseIcons.code
        "image" -> MuseIcons.image
        else -> MuseIcons.fileText
    }
}
