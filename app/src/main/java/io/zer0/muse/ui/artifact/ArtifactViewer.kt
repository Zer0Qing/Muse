package io.zer0.muse.ui.artifact

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.screen.HtmlPreviewScreen
import io.zer0.muse.util.ShareIntentHelper
import io.zer0.muse.util.launchSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 富内容预览语言判定(svg / html / chart / mermaid) — type 与 language 双查。
 */
fun richPreviewLanguage(type: String, language: String?): String? {
    val richTypes = listOf("svg", "html", "chart", "mermaid")
    return when {
        type.lowercase() in richTypes -> type.lowercase()
        language?.lowercase() in richTypes -> language?.lowercase()
        else -> null
    }
}

/** 另存为时，标题清洗后为空的兜底文件名。 */
internal const val DEFAULT_ARTIFACT_FILE_NAME = "artifact"

/**
 * 生成安全的导出文件名：把标题里的路径分隔符/特殊字符替换成下划线，截断并拼接扩展名。
 */
internal fun artifactExportFileName(title: String, extension: String = ".txt"): String {
    val sanitized = title.trim()
        .map { ch -> if (ch.isLetterOrDigit() || ch in "-_") ch else '_' }
        .joinToString("")
        .trim('_')
        .take(40)
    return "${sanitized.ifBlank { DEFAULT_ARTIFACT_FILE_NAME }}$extension"
}

/** 按产物类型与语言决定导出扩展名，正确处理 `rich_card` 与 `image`。 */
internal fun artifactExportExtension(type: String, language: String?): String =
    artifactExportExtensionWithContent(type, language, content = null)

internal fun artifactExportExtensionWithContent(type: String, language: String?, content: String?): String {
    val byType = mapOf(
        "table" to ".md",
        "markdown" to ".md",
        "json" to ".json",
        "csv" to ".csv",
        "html" to ".html",
        "svg" to ".svg",
        "chart" to ".json",
        "mermaid" to ".mmd",
    )
    val byLanguage = mapOf(
        "kotlin" to ".kt",
        "kt" to ".kt",
        "python" to ".py",
        "py" to ".py",
        "javascript" to ".js",
        "js" to ".js",
        "typescript" to ".ts",
        "ts" to ".ts",
        "java" to ".java",
        "json" to ".json",
        "xml" to ".xml",
        "html" to ".html",
        "svg" to ".svg",
        "chart" to ".json",
        "mermaid" to ".mmd",
        "mmd" to ".mmd",
        "sql" to ".sql",
        "bash" to ".sh",
        "sh" to ".sh",
        "shell" to ".sh",
        "yaml" to ".yml",
        "yml" to ".yml",
        "css" to ".css",
        "cpp" to ".cpp",
        "c" to ".c",
        "h" to ".h",
        "go" to ".go",
        "rust" to ".rs",
        "rs" to ".rs",
        "dart" to ".dart",
        "swift" to ".swift",
        "php" to ".php",
        "ruby" to ".rb",
        "rb" to ".rb",
        "toml" to ".toml",
        "md" to ".md",
        "markdown" to ".md",
    )
    val normalizedType = type.lowercase().trim()
    val normalizedLang = language?.lowercase()?.trim()
    if (normalizedType == "image") {
        val raw = content?.trim().orEmpty()
        return when {
            raw.startsWith("data:image/jpeg", ignoreCase = true) ||
                raw.endsWith(".jpg", true) ||
                raw.endsWith(".jpeg", true) -> ".jpg"
            raw.startsWith("data:image/webp", ignoreCase = true) || raw.endsWith(".webp", true) -> ".webp"
            raw.startsWith("data:image/gif", ignoreCase = true) || raw.endsWith(".gif", true) -> ".gif"
            else -> ".png"
        }
    }
    if (normalizedType == "rich_card") {
        return byLanguage[normalizedLang] ?: ".html"
    }
    return byType[normalizedType]
        ?: (if (normalizedType == "code") byLanguage[normalizedLang] else null)
        ?: ".txt"
}

/** 导出与分享 MIME；支持双查 `rich_card` 与 `image`。 */
internal fun artifactExportMime(type: String, language: String? = null): String {
    val normType = type.lowercase().trim()
    val effective = if (normType == "rich_card") {
        language?.lowercase()?.trim().orEmpty()
    } else {
        normType
    }
    return when (effective) {
        "table", "markdown" -> "text/markdown"
        "json", "chart" -> "application/json"
        "csv" -> "text/csv"
        "html" -> "text/html"
        "svg" -> "image/svg+xml"
        "image" -> "image/*"
        else -> "text/plain"
    }
}

/** data-URI 图片解码出真实字节；非 data-URI 返回 null，交给 Coil / 文件路径读取。 */
internal fun artifactImageBytes(content: String): ByteArray? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("data:") || !trimmed.contains("base64,")) return null
    val payload = trimmed.substringAfter("base64,")
    return runCatching { java.util.Base64.getMimeDecoder().decode(payload) }.getOrNull()
}

private suspend fun resolveArtifactBytes(context: Context, artifact: ArtifactEntity, mode: ArtifactRenderMode): ByteArray =
    withContext(Dispatchers.IO) {
        if (mode == ArtifactRenderMode.Image) {
            artifactImageBytes(artifact.content)?.let { return@withContext it }
            val trimmed = artifact.content.trim()
            if (trimmed.startsWith("file://") || trimmed.startsWith("/")) {
                val f = File(trimmed.removePrefix("file://"))
                if (f.isFile) return@withContext f.readBytes()
            }
            if (trimmed.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(trimmed))?.use { return@withContext it.readBytes() }
            }
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val conn = (java.net.URL(trimmed).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                }
                conn.inputStream.use { return@withContext it.readBytes() }
            }
        }
        artifact.content.toByteArray(Charsets.UTF_8)
    }

/** 外部打开产物：URL 直接 ACTION_VIEW；其余落到 cacheDir 后经 FileProvider 授权打开。 */
internal suspend fun openArtifactExternal(context: Context, artifact: ArtifactEntity) {
    val mode = artifactRenderMode(artifact.type, artifact.language)
    runCatching {
        val content = artifact.content.trim()
        val intent = if (content.startsWith("http://") || content.startsWith("https://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(content))
        } else {
            val ext = artifactExportExtensionWithContent(artifact.type, artifact.language, artifact.content)
            val mime = artifactExportMime(artifact.type, artifact.language)
            val bytes = resolveArtifactBytes(context, artifact, mode)
            val uri = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "artifact_open").apply { mkdirs() }
                val file = File(dir, artifactExportFileName(artifact.title, ext))
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { e ->
        val reason = e.message ?: e.javaClass.simpleName
        Logger.w("ArtifactViewer", "外部打开失败: $reason", e)
        MuseToast.show(context.getString(R.string.artifact_open_failed_with_reason, reason))
    }
}

private suspend fun shareArtifact(context: Context, artifact: ArtifactEntity, mode: ArtifactRenderMode) {
    val subject = artifact.title.ifBlank { context.getString(R.string.artifact_untitled) }
    if (mode == ArtifactRenderMode.Image || mode == ArtifactRenderMode.RichWeb || artifact.content.length > 64_000) {
        runCatching {
            val ext = artifactExportExtensionWithContent(artifact.type, artifact.language, artifact.content)
            val mime = artifactExportMime(artifact.type, artifact.language)
            val bytes = resolveArtifactBytes(context, artifact, mode)
            val uri = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "artifact_share").apply { mkdirs() }
                val file = File(dir, artifactExportFileName(subject, ext))
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ShareIntentHelper.startChooserSafely(
                context = context,
                shareIntent = shareIntent,
                chooserTitle = context.getString(R.string.artifact_share_chooser),
            )
        }.onFailure { e ->
            Logger.w("ArtifactViewer", "分享产物失败: ${e.message}", e)
            MuseToast.show(context.getString(R.string.artifact_open_failed_with_reason, e.message ?: ""))
        }
        return
    }
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, artifact.content)
    }
    ShareIntentHelper.startChooserSafely(
        context = context,
        shareIntent = shareIntent,
        chooserTitle = context.getString(R.string.artifact_share_chooser),
    )
}

/**
 * 产物全屏查看器 —— 使用统一 [FullScreenDocumentReader] 替代旧弹窗，
 * 并按类型分流图片（[FullScreenMediaViewer]）与富网页（[HtmlPreviewScreen]）。
 */
@Composable
fun ArtifactViewerDialog(
    artifact: ArtifactEntity,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenSession: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode = remember(artifact.type, artifact.language) { artifactRenderMode(artifact.type, artifact.language) }
    var fullscreenWeb by remember(artifact.id) { mutableStateOf(false) }
    var fullscreenImage by remember(artifact.id) { mutableStateOf(false) }
    var customPreviewHtml by remember(artifact.id) { mutableStateOf<String?>(null) }

    val exportExtension = remember(artifact.type, artifact.language, artifact.content) {
        artifactExportExtensionWithContent(artifact.type, artifact.language, artifact.content)
    }
    val exportMime = remember(artifact.type, artifact.language) {
        artifactExportMime(artifact.type, artifact.language)
    }
    val exportFileName = remember(artifact.title, exportExtension) {
        artifactExportFileName(artifact.title, exportExtension)
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(if (exportMime == "image/*") "image/png" else exportMime),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                var failureReason: String? = null
                val saved = runCatching {
                    val bytes = resolveArtifactBytes(context, artifact, mode)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(bytes)
                        } != null
                    }
                }
                    .onFailure { e ->
                        failureReason = e.message ?: e.javaClass.simpleName
                        Logger.w("ArtifactViewer", "导出产物失败 uri=$uri | 原因: $failureReason", e)
                    }
                    .getOrDefault(false)
                MuseToast.show(
                    if (saved) {
                        context.getString(R.string.artifact_saved)
                    } else {
                        failureReason?.let { context.getString(R.string.artifact_save_failed_with_reason, it) }
                            ?: context.getString(R.string.artifact_save_failed)
                    },
                )
            }
        }
    }

    if (fullscreenImage && mode == ArtifactRenderMode.Image) {
        FullScreenMediaViewer(
            images = listOf(artifact.content.trim()),
            initialIndex = 0,
            onDismiss = { fullscreenImage = false },
            onSaveImage = { saveLauncher.launchSafely(exportFileName, context) },
            onShareImage = { scope.launch { shareArtifact(context, artifact, mode) } },
        )
        return
    }

    if (fullscreenWeb) {
        val richLang = richPreviewLanguage(artifact.type, artifact.language) ?: "html"
        Dialog(
            onDismissRequest = { fullscreenWeb = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            MuseDialogWindowEffect(forceFullScreen = true)
            HtmlPreviewScreen(
                html = artifact.content,
                language = richLang,
                title = artifact.title.ifBlank { null },
                onBack = { fullscreenWeb = false },
            )
        }
        return
    }

    customPreviewHtml?.let { previewHtml ->
        Dialog(
            onDismissRequest = { customPreviewHtml = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            MuseDialogWindowEffect(forceFullScreen = true)
            HtmlPreviewScreen(
                html = previewHtml,
                title = artifact.title.ifBlank { null },
                onBack = { customPreviewHtml = null },
            )
        }
        return
    }

    val extraActions = buildList {
        if (onRename != null) {
            add(
                ReaderExtraAction(
                    label = stringResource(R.string.artifact_rename),
                    icon = MuseIcons.edit,
                    onClick = { onRename(artifact.title) },
                ),
            )
        }
        if (onDelete != null) {
            add(
                ReaderExtraAction(
                    label = stringResource(R.string.common_delete),
                    icon = MuseIcons.trash,
                    destructive = true,
                    onClick = onDelete,
                ),
            )
        }
    }

    val metaSubtitle = artifactMetaLabel(artifact.type, artifact.language)
    val readerLanguage = artifact.language
        ?.takeIf { it.isNotBlank() }
        ?: artifact.type.lowercase().takeIf { it == "json" }

    FullScreenDocumentReader(
        title = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) },
        subtitle = metaSubtitle,
        content = artifact.content,
        mode = mode,
        language = readerLanguage,
        onDismiss = onDismiss,
        onCopy = if (mode == ArtifactRenderMode.Image) null else onCopy,
        onSave = { saveLauncher.launchSafely(exportFileName, context) },
        onShare = { scope.launch { shareArtifact(context, artifact, mode) } },
        onOpenExternal = { scope.launch { openArtifactExternal(context, artifact) } },
        onOpenFullscreenWeb = if (mode == ArtifactRenderMode.RichWeb) {
            { fullscreenWeb = true }
        } else {
            null
        },
        onOpenSession = if (onOpenSession != null && artifact.sessionId.isNotBlank()) {
            {
                val sid = artifact.sessionId
                onDismiss()
                onOpenSession(sid)
            }
        } else {
            null
        },
        onOpenImageFullscreen = if (mode == ArtifactRenderMode.Image) {
            { fullscreenImage = true }
        } else {
            null
        },
        onHtmlPreview = { customPreviewHtml = it },
        extraActions = extraActions,
    )
}
