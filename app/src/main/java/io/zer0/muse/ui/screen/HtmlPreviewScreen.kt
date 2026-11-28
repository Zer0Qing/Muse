package io.zer0.muse.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.zer0.muse.R
import io.zer0.muse.ui.artifact.artifactExportExtension
import io.zer0.muse.ui.artifact.artifactExportFileName
import io.zer0.muse.ui.artifact.artifactExportMime
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewContainer
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.markdown.CodeHighlighter
import io.zer0.muse.ui.markdown.RichContentWebViewClient
import io.zer0.muse.ui.markdown.prepareRichPreview
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HtmlPreviewByIdScreen(previewId: String, onBack: () -> Unit, title: String? = null) {
    val context = LocalContext.current
    val initialHtml = remember(previewId) {
        HtmlPreviewStore.getCached(previewId)
    }
    val htmlState = androidx.compose.runtime.produceState(initialValue = initialHtml, key1 = previewId) {
        if (value == null) {
            value = HtmlPreviewStore.loadOrNull(context, previewId).orEmpty()
        }
    }
    val loaded = htmlState.value
    if (loaded != null) {
        HtmlPreviewScreen(
            html = loaded,
            onBack = onBack,
            title = title,
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            MuseTopBar(
                title = title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.html_preview_title),
                onBack = onBack,
            )
        }
    }
}

/**
 * 富内容全屏预览页（统一服务 HTML / SVG / Chart / Mermaid）。
 *
 * 自动注入与聊天卡片一致的主题 CSS 变量与安全拦截策略（[prepareRichPreview]），
 * 支持切换源码视图、刷新、FileProvider 外部浏览器打开、分享与异步导出。
 */
@Composable
fun HtmlPreviewScreen(html: String, onBack: () -> Unit, title: String? = null, language: String = "html") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    var showSource by rememberSaveable(html, language) { mutableStateOf(false) }

    val colorScheme = MaterialTheme.colorScheme
    val errorPrefix = stringResource(R.string.markdown_chart_render_failed_prefix)
    val rawDataPrefix = stringResource(R.string.artifact_raw_data_prefix)
    val (assembledHtml, baseUrl) = remember(html, language, colorScheme, errorPrefix, rawDataPrefix) {
        prepareRichPreview(language, html, colorScheme, errorPrefix, rawDataPrefix)
    }
    val normalizedLang = language.lowercase().trim().ifBlank { "html" }
    val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.html_preview_title)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MuseTopBar(
                title = resolvedTitle,
                onBack = onBack,
                actions = {
                    MuseTactileButton(
                        icon = if (showSource) MuseIcons.eye else MuseIcons.code,
                        contentDescription = stringResource(
                            if (showSource) R.string.artifact_view_rendered else R.string.artifact_view_source,
                        ),
                        tint = if (showSource) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { showSource = !showSource },
                    )
                    if (!showSource) {
                        MuseTactileButton(
                            icon = MuseIcons.refresh,
                            contentDescription = stringResource(R.string.browser_refresh),
                            onClick = { runCatching { webViewRef[0]?.reload() } },
                        )
                    }
                    MuseTactileButton(
                        icon = MuseIcons.share,
                        contentDescription = stringResource(R.string.common_share),
                        onClick = { scope.launch { shareRichContent(context, html, normalizedLang, resolvedTitle) } },
                    )
                    MuseTactileButton(
                        icon = MuseIcons.browser,
                        contentDescription = stringResource(R.string.html_preview_open_in_browser_cd),
                        onClick = { scope.launch { openInExternalBrowser(context, html, normalizedLang, resolvedTitle) } },
                    )
                    MuseTactileButton(
                        icon = MuseIcons.download,
                        contentDescription = stringResource(R.string.html_preview_download_cd),
                        onClick = { scope.launch { downloadHtml(context, html, normalizedLang, resolvedTitle) } },
                    )
                    MuseTactileButton(
                        icon = MuseIcons.copy,
                        contentDescription = stringResource(R.string.html_preview_copy_source_cd),
                        onClick = { copyHtmlToClipboard(context, html) },
                    )
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding(),
            ) {
                if (showSource) {
                    val highlighted = CodeHighlighter.highlight(html, normalizedLang)
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(MusePaddings.screen),
                        ) {
                            Text(
                                text = highlighted,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MuseMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    LifecycleAwareWebViewContainer(
                        htmlContent = assembledHtml,
                        baseUrl = baseUrl,
                        javaScriptEnabled = true,
                        domStorageEnabled = true,
                        webViewClient = remember { RichContentWebViewClient() },
                        scrollable = true,
                        onWebViewCreated = { webView ->
                            webView.settings.setSupportZoom(true)
                            webView.settings.builtInZoomControls = true
                            webView.settings.displayZoomControls = false
                            webView.settings.useWideViewPort = true
                            webView.settings.loadWithOverviewMode = true
                            webViewRef[0] = webView
                        },
                        onWebViewReleased = { webViewRef[0] = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private suspend fun writePreviewCacheFile(context: Context, content: String, language: String, title: String?): Pair<File, String> =
    withContext(Dispatchers.IO) {
        val lang = language.lowercase().trim()
        val ext = when (lang) {
            "svg" -> ".svg"
            "chart" -> ".json"
            "mermaid" -> ".mmd"
            else -> ".html"
        }
        val mime = when (lang) {
            "svg" -> "image/svg+xml"
            "chart" -> "application/json"
            "mermaid" -> "text/plain"
            else -> "text/html"
        }
        val dir = File(context.cacheDir, "artifact_open").apply { mkdirs() }
        val name = artifactExportFileName(title ?: "muse_preview", ext)
        val file = File(dir, name)
        file.writeText(content, Charsets.UTF_8)
        file to mime
    }

private suspend fun openInExternalBrowser(context: Context, html: String, language: String, title: String?) {
    runCatching {
        val (file, mime) = writePreviewCacheFile(context, html, language, title)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { e ->
        MuseToast.show(
            context.getString(R.string.html_preview_open_in_browser_failed, e.message ?: ""),
        )
    }
}

private suspend fun shareRichContent(context: Context, html: String, language: String, title: String?) {
    runCatching {
        val (file, mime) = writePreviewCacheFile(context, html, language, title)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, title ?: context.getString(R.string.html_preview_title))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ShareIntentHelper.startChooserSafely(
            context = context,
            shareIntent = shareIntent,
            chooserTitle = context.getString(R.string.artifact_share_chooser),
        )
    }.onFailure { e ->
        MuseToast.show(context.getString(R.string.artifact_open_failed_with_reason, e.message ?: ""))
    }
}

private fun copyHtmlToClipboard(context: Context, html: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("html", html))
    MuseToast.show(context.getString(R.string.html_preview_source_copied))
}

private suspend fun downloadHtml(context: Context, html: String, language: String, title: String?) {
    val ext = artifactExportExtension("rich_card", language)
    val mime = artifactExportMime("rich_card", language)
    val fileName = artifactExportFileName(
        title?.takeIf { it.isNotBlank() } ?: "muse_artifact_${System.currentTimeMillis()}",
        ext,
    )
    val error = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = html.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error(context.getString(R.string.code_more_download_insert_failed))
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error(context.getString(R.string.code_more_output_stream_failed))
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                File(dir, fileName).writeBytes(bytes)
            }
            null
        }.exceptionOrNull()
    }
    if (error == null) {
        MuseToast.show(context.getString(R.string.html_preview_download_success, fileName))
    } else {
        MuseToast.show(context.getString(R.string.html_preview_download_failed, error.message ?: ""))
    }
}
