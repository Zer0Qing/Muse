package io.zer0.muse.ui.artifact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.chat.SmartImage
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.markdown.CjkLatinSpacing
import io.zer0.muse.ui.markdown.CodeHighlighter
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.markdown.RichContentCard
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill

/** 阅读器底部附加动作（如删除、重命名、取消收藏）。 */
data class ReaderExtraAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** 根据文件名或扩展名推断适合的产物渲染形态。 */
fun inferRenderModeFromFileName(fileName: String): Pair<ArtifactRenderMode, String?> {
    val ext = fileName.substringAfterLast('.', "").lowercase().trim()
    return when (ext) {
        "html", "htm" -> ArtifactRenderMode.RichWeb to "html"
        "svg" -> ArtifactRenderMode.RichWeb to "svg"
        "chart" -> ArtifactRenderMode.RichWeb to "chart"
        "mermaid", "mmd" -> ArtifactRenderMode.RichWeb to "mermaid"
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> ArtifactRenderMode.Image to ext
        "md", "markdown" -> ArtifactRenderMode.Markdown to "markdown"
        "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx",
        "json", "xml", "yaml", "yml", "sh", "bash", "zsh", "css",
        "scss", "sql", "c", "cpp", "cc", "h", "hpp", "go", "rs",
        "dart", "swift", "php", "rb", "toml", "ini", "gradle", "properties",
        -> ArtifactRenderMode.Code to ext
        else -> ArtifactRenderMode.PlainText to ext.takeIf { it.isNotBlank() }
    }
}

/**
 * 全屏沉浸式长文本 / 文档 / 代码 / 富内容阅读器。
 *
 * 替代原有的多处局促居中弹窗：
 *  - 边到边全屏阅读视野 + 顶部精细阅读进度条；
 *  - 支持渲染/源码切换（Markdown/HTML/SVG/Chart/Mermaid）；
 *  - 代码与纯文本支持行号展示、自动换行切换、字体大小切换及全文划词选择；
 *  - 底部常驻动作栏收口复制、另存、分享、外部打开、跳回原对话等操作。
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun FullScreenDocumentReader(
    title: String,
    content: String,
    mode: ArtifactRenderMode,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    language: String? = null,
    citationUrls: List<String> = emptyList(),
    showSourceInitially: Boolean = false,
    bannerNote: String? = null,
    onCopy: ((String) -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onOpenExternal: (() -> Unit)? = null,
    onOpenFullscreenWeb: (() -> Unit)? = null,
    onOpenSession: (() -> Unit)? = null,
    onOpenImageFullscreen: (() -> Unit)? = null,
    onHtmlPreview: ((String) -> Unit)? = null,
    extraActions: List<ReaderExtraAction> = emptyList(),
) {
    var showSource by rememberSaveable(title, mode) { mutableStateOf(showSourceInitially) }
    var wordWrap by rememberSaveable { mutableStateOf(true) }
    // 0 = 标准(bodyMedium), 1 = 舒适大字(bodyLarge), 2 = 紧凑(bodySmall)
    var textScaleTier by rememberSaveable { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()
    val scrollProgress by remember {
        derivedStateOf {
            if (scrollState.maxValue <= 0) 0f else (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)
        }
    }

    val lineCount = remember(content) { content.lineSequence().count() }
    val computedSubtitle = subtitle?.takeIf { it.isNotBlank() } ?: when (mode) {
        ArtifactRenderMode.Code -> stringResource(R.string.artifact_reader_meta_lines, lineCount, content.length)
        ArtifactRenderMode.Image -> null
        else -> stringResource(R.string.artifact_reader_meta_chars, content.length)
    }

    val activeTextStyle = when (textScaleTier) {
        1 -> MaterialTheme.typography.bodyLarge
        2 -> MaterialTheme.typography.bodySmall
        else -> MaterialTheme.typography.bodyMedium
    }

    val effectiveLanguage = language?.takeIf { it.isNotBlank() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 顶部阅读工具栏 ──────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = MuseSpacing.xs, vertical = MuseSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseTactileButton(
                        icon = MuseIcons.arrowLeft,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.action_back),
                    )
                    Spacer(Modifier.width(MuseSpacing.xs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title.ifBlank { stringResource(R.string.artifact_untitled) },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!computedSubtitle.isNullOrBlank()) {
                            Text(
                                text = computedSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // 上下文阅读开关：源码切换 / 换行切换 / 字号切换 / 全屏网页
                    if (mode == ArtifactRenderMode.Markdown || mode == ArtifactRenderMode.RichWeb) {
                        MuseTactileButton(
                            icon = if (showSource) MuseIcons.eye else MuseIcons.code,
                            onClick = { showSource = !showSource },
                            contentDescription = stringResource(
                                if (showSource) R.string.artifact_view_rendered else R.string.artifact_view_source,
                            ),
                            tint = if (showSource) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (mode == ArtifactRenderMode.Code || (showSource && mode != ArtifactRenderMode.Image)) {
                        ReaderToolbarPill(
                            text = stringResource(
                                if (wordWrap) R.string.artifact_reader_word_wrap_on else R.string.artifact_reader_word_wrap_off,
                            ),
                            active = !wordWrap,
                            onClick = { wordWrap = !wordWrap },
                        )
                    }
                    if (mode != ArtifactRenderMode.Image && !(mode == ArtifactRenderMode.RichWeb && !showSource)) {
                        ReaderToolbarPill(
                            text = stringResource(R.string.artifact_reader_font_size),
                            active = textScaleTier != 0,
                            onClick = { textScaleTier = (textScaleTier + 1) % 3 },
                        )
                    }
                    if (mode == ArtifactRenderMode.RichWeb && onOpenFullscreenWeb != null) {
                        MuseTactileButton(
                            icon = MuseIcons.maximize,
                            onClick = onOpenFullscreenWeb,
                            contentDescription = stringResource(R.string.artifact_fullscreen),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (mode == ArtifactRenderMode.Image && onOpenImageFullscreen != null) {
                        MuseTactileButton(
                            icon = MuseIcons.maximize,
                            onClick = onOpenImageFullscreen,
                            contentDescription = stringResource(R.string.artifact_fullscreen),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 顶部细阅读进度条（仅长文可滚动时出现）
                if (scrollState.maxValue > 0 && mode != ArtifactRenderMode.Image && !(mode == ArtifactRenderMode.RichWeb && !showSource)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MuseSpacing.xxs)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(scrollProgress.coerceIn(0f, 1f))
                                .height(MuseSpacing.xxs)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                } else {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = MusePaddings.dividerThickness,
                    )
                }

                if (!bannerNote.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = bannerNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.sm),
                        )
                    }
                }

                // ── 主体阅读视口 ──────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    when {
                        mode == ArtifactRenderMode.Image -> {
                            val imageClickModifier = if (onOpenImageFullscreen != null) {
                                Modifier.clickable(onClick = onOpenImageFullscreen)
                            } else {
                                Modifier
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(MusePaddings.screen)
                                    .then(imageClickModifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                SmartImage(
                                    model = content.trim(),
                                    contentDescription = title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        mode == ArtifactRenderMode.RichWeb && !showSource -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(MuseSpacing.sm),
                            ) {
                                RichContentCard(
                                    language = richPreviewLanguage(effectiveLanguage.orEmpty(), effectiveLanguage)
                                        ?: effectiveLanguage
                                        ?: "html",
                                    content = content,
                                    onHtmlPreview = { previewHtml ->
                                        onHtmlPreview?.invoke(previewHtml) ?: onOpenFullscreenWeb?.invoke()
                                    },
                                    showPreviewButton = true,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        mode == ArtifactRenderMode.Markdown && !showSource -> {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.lg),
                                ) {
                                    MarkdownText(
                                        text = content,
                                        style = activeTextStyle,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        citationUrls = citationUrls,
                                        onHtmlPreview = onHtmlPreview ?: {},
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(MuseSpacing.xxl))
                                }
                            }
                        }

                        mode == ArtifactRenderMode.Code || showSource -> {
                            val highlighted = CodeHighlighter.highlight(content, effectiveLanguage)
                            val lines = remember(content) { content.lines() }
                            val lineNumberBlock = remember(lines.size) {
                                (1..lines.size).joinToString("\n")
                            }
                            val monoStyle = activeTextStyle.copy(fontFamily = MuseMonoFontFamily)
                            val horizontalScrollState = rememberScrollState()
                            SelectionContainer {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.md),
                                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                                ) {
                                    Text(
                                        text = lineNumberBlock,
                                        style = monoStyle,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    val contentModifier = if (wordWrap) {
                                        Modifier.weight(1f)
                                    } else {
                                        Modifier
                                            .weight(1f)
                                            .horizontalScroll(horizontalScrollState)
                                    }
                                    Text(
                                        text = highlighted,
                                        style = monoStyle,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = contentModifier,
                                    )
                                }
                            }
                        }

                        else -> {
                            val displayPlain = remember(content) { CjkLatinSpacing.format(content) }
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.lg),
                                ) {
                                    Text(
                                        text = displayPlain,
                                        style = activeTextStyle,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(MuseSpacing.xxl))
                                }
                            }
                        }
                    }
                }

                // ── 底部固定操作栏 ──────────────────────────────────────
                val hasBottomActions = onCopy != null || onSave != null || onShare != null ||
                    onOpenExternal != null || onOpenSession != null || extraActions.isNotEmpty()
                if (hasBottomActions) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = MusePaddings.dividerThickness,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            onCopy?.let { copyCb ->
                                ReaderBottomAction(
                                    icon = MuseIcons.copy,
                                    label = stringResource(R.string.common_copy),
                                    onClick = { copyCb(content) },
                                )
                            }
                            onSave?.let { saveCb ->
                                ReaderBottomAction(
                                    icon = MuseIcons.deviceFloppy,
                                    label = stringResource(R.string.artifact_save_file),
                                    onClick = saveCb,
                                )
                            }
                            onShare?.let { shareCb ->
                                ReaderBottomAction(
                                    icon = MuseIcons.share,
                                    label = stringResource(R.string.common_share),
                                    onClick = shareCb,
                                )
                            }
                            onOpenExternal?.let { extCb ->
                                ReaderBottomAction(
                                    icon = MuseIcons.externalLink,
                                    label = stringResource(R.string.artifact_open_external),
                                    onClick = extCb,
                                )
                            }
                            onOpenSession?.let { sessionCb ->
                                ReaderBottomAction(
                                    icon = MuseIcons.messages,
                                    label = stringResource(R.string.artifact_jump_to_chat),
                                    onClick = sessionCb,
                                )
                            }
                            extraActions.forEach { action ->
                                ReaderBottomAction(
                                    icon = action.icon,
                                    label = action.label,
                                    destructive = action.destructive,
                                    onClick = action.onClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderToolbarPill(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.pill,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        },
        modifier = Modifier.padding(end = MuseSpacing.xs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
            maxLines = 1,
        )
    }
}

@Composable
private fun ReaderBottomAction(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
        modifier = Modifier
            .clip(MuseShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
