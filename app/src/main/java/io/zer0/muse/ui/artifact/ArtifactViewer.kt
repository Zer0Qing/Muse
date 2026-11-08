package io.zer0.muse.ui.artifact

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.HtmlPreviewScreen
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.markdown.CodeHighlighter
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.ShareIntentHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.0.1: 富内容预览语言判定(svg / html / chart / mermaid) — type 与 language 双查。
 * 供查看器与「全屏浏览器形态」入口共用同一判据。
 */
fun richPreviewLanguage(type: String, language: String?): String? {
    val richTypes = listOf("svg", "html", "chart", "mermaid")
    return when {
        type.lowercase() in richTypes -> type.lowercase()
        language?.lowercase() in richTypes -> language?.lowercase()
        else -> null
    }
}

/** 另存为文本时,标题清洗后为空时的兜底文件名。 */
internal const val DEFAULT_ARTIFACT_FILE_NAME = "artifact"

/**
 * 由产物标题生成 SAF 另存文件名(非法字符替换为下划线,保留中英文与数字)。
 *
 * @param extension 带点的扩展名,如 ".md"；不传时退回 ".txt"
 */
internal fun artifactExportFileName(title: String, extension: String = ".txt"): String {
    val sanitized = title.trim()
        .map { ch -> if (ch.isLetterOrDigit() || ch in "-_") ch else '_' }
        .joinToString("")
        .trim('_')
        .take(40)
    return "${sanitized.ifBlank { DEFAULT_ARTIFACT_FILE_NAME }}$extension"
}

/**
 * 按产物类型与语言决定导出扩展名。
 *
 * 表格与 Markdown 给 .md（GFM 表格在多数编辑器里能直接渲染），
 * JSON 给 .json，代码按 language 走，其余退回 .txt。
 */
internal fun artifactExportExtension(type: String, language: String?): String {
    val byType =
        mapOf(
            "table" to ".md",
            "markdown" to ".md",
            "json" to ".json",
            "csv" to ".csv",
            "html" to ".html",
            "svg" to ".svg",
        )
    val byLanguage =
        mapOf(
            "kotlin" to ".kt",
            "python" to ".py",
            "javascript" to ".js",
            "js" to ".js",
            "typescript" to ".ts",
            "ts" to ".ts",
            "java" to ".java",
            "json" to ".json",
            "xml" to ".xml",
            "html" to ".html",
            "sql" to ".sql",
            "bash" to ".sh",
            "sh" to ".sh",
            "shell" to ".sh",
            "yaml" to ".yml",
            "yml" to ".yml",
            "css" to ".css",
        )
    val normalizedType = type.lowercase()
    // 单出口：先按类型查，类型是 code 时再按语言查，都没有就退回 .txt
    return byType[normalizedType]
        ?: (if (normalizedType == "code") byLanguage[language?.lowercase()] else null)
        ?: ".txt"
}

/** 导出用的 MIME。SAF 的 CreateDocument 需要它来挑默认应用。 */
internal fun artifactExportMime(type: String): String = when (type.lowercase()) {
    "table", "markdown" -> "text/markdown"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "html" -> "text/html"
    "svg" -> "image/svg+xml"
    else -> "text/plain"
}

/**
 * 37：data-URI 图片解码出真实字节；非 data-URI（URL/本地路径）返回 null，交给 Coil 直接加载。
 * 纯函数，便于钉住「不要把图片地址本身当成图渲染」。
 */
internal fun artifactImageBytes(content: String): ByteArray? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("data:") || !trimmed.contains("base64,")) return null
    val payload = trimmed.substringAfter("base64,")
    return runCatching { java.util.Base64.getMimeDecoder().decode(payload) }.getOrNull()
}

/**
 * 37：外部打开产物。URL 直接 ACTION_VIEW；其余（html/svg/代码/文本）落到 cache 文件经
 * FileProvider 授权后按 MIME 打开，让产物「能拿去别的 App 用」，而不是困在预览框里。
 */
internal fun openArtifactExternal(context: Context, artifact: ArtifactEntity) {
    runCatching {
        val content = artifact.content.trim()
        val intent = if (content.startsWith("http://") || content.startsWith("https://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(content))
        } else {
            val ext = artifactExportExtension(artifact.type, artifact.language)
            val dir = File(context.cacheDir, "artifact_open").apply { mkdirs() }
            val file = File(dir, artifactExportFileName(artifact.title, ext))
            file.writeText(artifact.content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, artifactExportMime(artifact.type))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { e ->
        Logger.w("ArtifactViewer", "外部打开失败: ${e.message ?: e.javaClass.simpleName}", e)
        MuseToast.show(context.getString(R.string.artifact_open_failed))
    }
}

/**
 * 37：产物查看器 —— 按类型给真正的查看形态 + 一组实在的动作（复制/保存/分享/外部打开，
 * 网页另给源码切换与全屏，图片另给全屏）。取代「展开后只有一个什么都干不了的预览框」。
 */
@Composable
fun ArtifactViewerDialog(artifact: ArtifactEntity, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode = remember(artifact.type, artifact.language) { artifactRenderMode(artifact.type, artifact.language) }
    val actions = remember(mode) { artifactActions(mode) }
    var showSource by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val exportExtension =
        remember(artifact.type, artifact.language) { artifactExportExtension(artifact.type, artifact.language) }
    val exportMime = remember(artifact.type) { artifactExportMime(artifact.type) }
    val exportFileName =
        remember(artifact.title, exportExtension) { artifactExportFileName(artifact.title, exportExtension) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(exportMime),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(artifact.content.toByteArray(Charsets.UTF_8))
                        } != null
                    }
                        .onFailure { e ->
                            Logger.w("ArtifactViewer", "导出产物失败 uri=$uri | 原因: ${e.message ?: e.javaClass.simpleName}", e)
                        }
                        .getOrDefault(false)
                }
                MuseToast.show(context.getString(if (saved) R.string.artifact_saved else R.string.artifact_save_failed))
            }
        }
    }

    // 全屏：网页走浏览器形态，图片走纯图查看。
    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            if (mode == ArtifactRenderMode.Image) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = artifact.content.trim(),
                        contentDescription = artifact.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                HtmlPreviewScreen(
                    html = artifact.content,
                    title = artifact.title.ifBlank { null },
                    onBack = { fullscreen = false },
                )
            }
        }
        return
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = null,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = artifactTypeIcon(artifact.type),
                        contentDescription = artifactTypeLabel(artifact.type),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MuseTactileButton(
                        icon = MuseIcons.x,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.artifact_close),
                        size = MuseIconSizes.touchTarget,
                        iconSize = 20.dp,
                    )
                }
                Surface(
                    color = if (mode == ArtifactRenderMode.Code) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = MuseShapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                ) {
                    ArtifactContent(artifact = artifact, mode = mode, showSource = showSource)
                }
                ArtifactActionBar(
                    actions = actions,
                    showSource = showSource,
                    onAction = { action ->
                        when (action) {
                            ArtifactAction.Copy -> onCopy(artifact.content)
                            ArtifactAction.Save -> saveLauncher.launch(exportFileName)
                            ArtifactAction.Share -> shareArtifact(context, artifact)
                            ArtifactAction.OpenExternal -> openArtifactExternal(context, artifact)
                            ArtifactAction.ToggleSource -> showSource = !showSource
                            ArtifactAction.Fullscreen -> fullscreen = true
                        }
                    },
                )
            }
        },
        confirmText = stringResource(R.string.artifact_copy),
        onConfirm = { onCopy(artifact.content) },
        dismissText = null,
    )
}

/** 按渲染形态给出内容区。图片用 Coil 真渲染，不再退化成原始文本。 */
@Composable
private fun ArtifactContent(artifact: ArtifactEntity, mode: ArtifactRenderMode, showSource: Boolean) {
    when (mode) {
        ArtifactRenderMode.Image -> {
            val model = remember(artifact.content) {
                artifactImageBytes(artifact.content)?.let { java.nio.ByteBuffer.wrap(it) } ?: artifact.content.trim()
            }
            AsyncImage(
                model = model,
                contentDescription = artifact.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(4.dp),
            )
        }
        ArtifactRenderMode.RichWeb -> {
            if (showSource) {
                ScrollableMonoText(artifact.content)
            } else {
                io.zer0.muse.ui.markdown.RichContentCard(
                    language = richPreviewLanguage(artifact.type, artifact.language) ?: artifact.type.lowercase(),
                    content = artifact.content,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    showPreviewButton = false,
                )
            }
        }
        ArtifactRenderMode.Markdown -> {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                io.zer0.muse.ui.markdown.MarkdownText(text = artifact.content, modifier = Modifier.fillMaxWidth())
            }
        }
        ArtifactRenderMode.Code -> {
            val highlighted = CodeHighlighter.highlight(artifact.content, artifact.language)
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MuseMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ArtifactRenderMode.PlainText -> ScrollableMonoText(artifact.content, mono = false)
    }
}

@Composable
private fun ScrollableMonoText(text: String, mono: Boolean = true) {
    Text(
        text = text,
        style = if (mono) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = MuseMonoFontFamily)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp),
    )
}

/** 统一动作条：按可用动作渲染，固定顺序，杜绝「展开后什么都干不了」。 */
@Composable
private fun ArtifactActionBar(actions: Set<ArtifactAction>, showSource: Boolean, onAction: (ArtifactAction) -> Unit) {
    // 复制由弹窗的主按钮承担，动作条不再重复一颗复制。
    val ordered = listOf(
        ArtifactAction.Save,
        ArtifactAction.Share,
        ArtifactAction.OpenExternal,
        ArtifactAction.ToggleSource,
        ArtifactAction.Fullscreen,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ordered.filter { it in actions }.forEach { action ->
            val (icon, descRes) = when (action) {
                ArtifactAction.Copy -> MuseIcons.copy to R.string.artifact_copy
                ArtifactAction.Save -> MuseIcons.deviceFloppy to R.string.artifact_save_text
                ArtifactAction.Share -> MuseIcons.share to R.string.action_share
                ArtifactAction.OpenExternal -> MuseIcons.externalLink to R.string.artifact_open_external
                ArtifactAction.ToggleSource -> MuseIcons.code to
                    (if (showSource) R.string.artifact_view_rendered else R.string.artifact_view_source)
                ArtifactAction.Fullscreen -> MuseIcons.maximize to R.string.artifact_fullscreen
            }
            MuseTactileButton(
                icon = icon,
                onClick = { onAction(action) },
                contentDescription = stringResource(descRes),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = MuseIconSizes.touchTarget,
                iconSize = 20.dp,
            )
        }
    }
}

private fun shareArtifact(context: Context, artifact: ArtifactEntity) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, artifact.title.ifBlank { context.getString(R.string.artifact_untitled) })
        putExtra(Intent.EXTRA_TEXT, artifact.content)
    }
    ShareIntentHelper.startChooserSafely(
        context = context,
        shareIntent = shareIntent,
        chooserTitle = context.getString(R.string.artifact_share_chooser),
    )
}
