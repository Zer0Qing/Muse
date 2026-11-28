package io.zer0.muse.ui.artifact

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.LifecycleAwareWebView
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewFactory
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museSafeTopInsetPadding
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 文档预览（PDF）— 全屏 WebView + pdf.js（assets 离线分发），支持分享与外部应用打开。
 *
 * 超过 [MAX_DOC_BYTES] 时提示大小上限，并保留外部应用打开入口。
 */
@Composable
fun DocPreviewDialog(fileName: String, loadBytes: suspend () -> ByteArray?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var rawBytes by remember(fileName) { mutableStateOf<ByteArray?>(null) }
    var payloadB64 by remember(fileName) { mutableStateOf<String?>(null) }
    var tooLarge by remember(fileName) { mutableStateOf(false) }
    var failed by remember(fileName) { mutableStateOf(false) }
    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    LaunchedEffect(fileName) {
        val bytes = runCatching { withContext(Dispatchers.IO) { loadBytes() } }.getOrNull()
        rawBytes = bytes
        when {
            bytes == null || bytes.isEmpty() -> failed = true
            bytes.size > MAX_DOC_BYTES -> tooLarge = true
            else -> payloadB64 = withContext(Dispatchers.Default) {
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        }
    }

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .museSafeTopInsetPadding()
                        .padding(horizontal = MuseSpacing.xs, vertical = MuseSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseTactileButton(
                        icon = MuseIcons.arrowLeft,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.action_back),
                    )
                    Spacer(Modifier.width(MuseSpacing.xs))
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val availableBytes = rawBytes
                    if (availableBytes != null && availableBytes.isNotEmpty()) {
                        MuseTactileButton(
                            icon = MuseIcons.share,
                            onClick = {
                                scope.launch {
                                    sharePdfBytes(context, fileName, availableBytes)
                                }
                            },
                            contentDescription = stringResource(R.string.common_share),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MuseTactileButton(
                            icon = MuseIcons.externalLink,
                            onClick = {
                                scope.launch {
                                    openPdfExternal(context, fileName, availableBytes)
                                }
                            },
                            contentDescription = stringResource(R.string.artifact_open_external),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    val b64 = payloadB64
                    when {
                        tooLarge -> Column(
                            modifier = Modifier.padding(MusePaddings.screen),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                        ) {
                            Text(
                                text = stringResource(R.string.doc_preview_too_large),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            val oversizedBytes = rawBytes
                            if (oversizedBytes != null) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.artifact_open_external),
                                    onClick = {
                                        scope.launch { openPdfExternal(context, fileName, oversizedBytes) }
                                    },
                                    variant = IosCapsuleButtonVariant.Secondary,
                                    fillWidth = false,
                                )
                            }
                        }
                        failed -> Text(
                            text = stringResource(R.string.common_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        b64 == null -> MuseSpinner(
                            size = MuseIconSizes.iconMedium,
                        )
                        else -> AndroidView(
                            factory = { ctx ->
                                LifecycleAwareWebViewFactory.create(ctx, lifecycleOwner).apply {
                                    settings.javaScriptEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    settings.mediaPlaybackRequiresUserGesture = true
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    val labels = JSONObject().apply {
                                        put("title", ctx.getString(R.string.doc_preview_title))
                                        put("waiting", ctx.getString(R.string.doc_preview_waiting))
                                        put("failed", ctx.getString(R.string.doc_preview_failed))
                                        put("language", ctx.resources.configuration.locales[0].toLanguageTag())
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            view?.evaluateJavascript("window.setDocumentLabels($labels); window.renderPdf('$b64')", null)
                                        }
                                    }
                                    webViewRef[0] = this
                                    loadUrl("file:///android_asset/docview/viewer.html")
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            webViewRef[0]?.also { wv ->
                if (wv is LifecycleAwareWebView) {
                    lifecycleOwner.lifecycle.removeObserver(wv)
                }
                wv.destroy()
            }
            webViewRef[0] = null
        }
    }
}

private suspend fun writeTempPdfUri(context: Context, fileName: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
    val safeName = artifactExportFileName(fileName.substringBeforeLast('.'), ".pdf")
    val dir = File(context.cacheDir, "artifact_open").apply { mkdirs() }
    val file = File(dir, safeName)
    file.writeBytes(bytes)
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private suspend fun sharePdfBytes(context: Context, fileName: String, bytes: ByteArray) {
    runCatching {
        val uri = writeTempPdfUri(context, fileName, bytes)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ShareIntentHelper.startChooserSafely(
            context = context,
            shareIntent = intent,
            chooserTitle = context.getString(R.string.artifact_share_chooser),
        )
    }.onFailure { e ->
        Logger.w("DocPreviewDialog", "分享 PDF 失败: ${e.message}", e)
        MuseToast.show(context.getString(R.string.artifact_open_failed_with_reason, e.message ?: ""))
    }
}

private suspend fun openPdfExternal(context: Context, fileName: String, bytes: ByteArray) {
    runCatching {
        val uri = writeTempPdfUri(context, fileName, bytes)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { e ->
        val reason = e.message ?: e.javaClass.simpleName
        Logger.w("DocPreviewDialog", "外部打开 PDF 失败: $reason", e)
        MuseToast.show(context.getString(R.string.artifact_open_failed_with_reason, reason))
    }
}

/** 文档预览大小上限（base64 注入路径的保守值）。 */
private const val MAX_DOC_BYTES = 20 * 1024 * 1024
