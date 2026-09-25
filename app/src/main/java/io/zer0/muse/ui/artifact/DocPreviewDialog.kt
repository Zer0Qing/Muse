package io.zer0.muse.ui.artifact

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.media.LifecycleAwareWebViewFactory
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.museSafeTopInsetPadding
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * v2.0.1: 文档预览（PDF）— 全屏 WebView + pdf.js（assets 离线分发）。
 *
 * 数据以 base64 注入 viewer.html 的 `window.renderPdf`（避免 file:// 跨域限制）；
 * 超过 [MAX_DOC_BYTES] 的文件回退加载失败态（后续可扩展分块加载）。
 */
@Composable
fun DocPreviewDialog(
    fileName: String,
    loadBytes: suspend () -> ByteArray?,
    onDismiss: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var payloadB64 by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    LaunchedEffect(fileName) {
        val bytes = runCatching { loadBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_DOC_BYTES) {
            failed = true
        } else {
            payloadB64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .museSafeTopInsetPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseTactileButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = onDismiss,
                        contentDescription = stringResource(R.string.action_back),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    val b64 = payloadB64
                    when {
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
                                    settings.mediaPlaybackRequiresUserGesture = true
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            view?.evaluateJavascript("window.renderPdf('$b64')", null)
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

    DisposableEffect(Unit) {
        onDispose {
            webViewRef[0]?.destroy()
            webViewRef[0] = null
        }
    }
}

/** 文档预览大小上限（base64 注入路径的保守值）。 */
private const val MAX_DOC_BYTES = 20 * 1024 * 1024
