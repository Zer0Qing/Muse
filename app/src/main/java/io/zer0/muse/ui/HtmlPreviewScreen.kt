package io.zer0.muse.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.zer0.muse.R
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.IosTactileButton
import io.zer0.muse.ui.common.LifecycleAwareWebView
import io.zer0.muse.ui.common.LifecycleAwareWebViewFactory
import io.zer0.muse.ui.common.MuseToast

/**
 * HTML 全屏预览页 — 在 WebView 中渲染 LLM 输出的 HTML / SVG 代码。
 *
 * 参考 kelivo 项目的 html_preview_page.dart / webview_page.dart 实现:
 *  - 顶部 IosTopBar 标题"HTML 预览" + 返回按钮 + "在浏览器中打开" + "复制源码"
 *  - 中间 WebView 加载 html(loadDataWithBaseURL)
 *  - 启用 JavaScript 与 DOM storage,支持缩放
 *  - 设置 WebViewClient 防止外部链接跳转(仅放行 about:blank)
 *
 * 安全说明:
 *  - HTML 内容来自 LLM 输出,理论上可能含恶意脚本;此处仅在本地 WebView 渲染,
 *    不暴露任何宿主接口(addJavascriptInterface),且拦截外部导航;
 *  - 复用 [RichContentWebViewClient] 的拦截策略(只放行 about:blank),
 *    避免点击 <a> 跳出预览页;
 *  - 与 RichContentCard 不同,本页启用 JS(用户主动点击"预览"才进入,
 *    适合交互式 HTML demo / 邮件模板 / SVG 动画)。
 *
 * @param html 待渲染的 HTML 源码(SVG 已由调用方包装为完整 HTML)
 * @param onBack 返回回调
 */
@Composable
fun HtmlPreviewScreen(
    html: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 缓存 WebView 引用,DisposableEffect 中释放,避免泄漏
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // v1.88 修复: WebView 自身已实现生命周期感知(LifecycleAwareWebView),
    // ON_PAUSE/ON_RESUME/ON_DESTROY 由 WebView 内部自动处理(含 pauseTimers/resumeTimers)。
    // 此处仅保留离开组合时的兜底销毁与 DOM storage 清理逻辑。
    DisposableEffect(lifecycleOwner) {
        onDispose {
            // 兜底释放:离开组合时销毁 WebView(宿主未销毁的场景,如导航返回)
            webViewRef[0]?.also {
                // 注销生命周期观察者,避免后续 ON_DESTROY 重复 destroy 已销毁实例
                if (it is LifecycleAwareWebView) {
                    lifecycleOwner.lifecycle.removeObserver(it)
                }
                it.destroy()
                webViewRef[0] = null
            }
            // 清理 DOM storage,避免 SVG/HTML demo 累积残留
            WebStorage.getInstance().deleteAllData()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            IosTopBar(
                title = stringResource(R.string.html_preview_title),
                onBack = onBack,
                actions = {
                    // 在浏览器中打开:把 HTML 编码为 data URL,用 ACTION_VIEW 交给系统浏览器
                    IosTactileButton(
                        icon = Icons.Default.OpenInBrowser,
                        contentDescription = stringResource(R.string.html_preview_open_in_browser_cd),
                        onClick = { openInExternalBrowser(context, html) },
                    )
                    // 复制源码:写入剪贴板 + Toast 反馈
                    IosTactileButton(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.html_preview_copy_source_cd),
                        onClick = { copyHtmlToClipboard(context, html) },
                    )
                },
            )
            // WebView 容器:占满剩余空间,设背景色避免白闪
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                AndroidView(
                    factory = { ctx ->
                        // v1.88 修复: 改用 LifecycleAwareWebViewFactory.create,
                        // 自动绑定到 lifecycleOwner,后台时暂停 JS 定时器/动画。
                        LifecycleAwareWebViewFactory.create(ctx, lifecycleOwner).apply {
                            // 启用 JavaScript(HTML demo / 交互式模板需要)
                            settings.javaScriptEnabled = true
                            // 启用 DOM storage(localStorage / sessionStorage)
                            settings.domStorageEnabled = true
                            // 启用缩放(内置缩放控件 + 双指)
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            // 隐藏缩放按钮(保留双指缩放手势),避免 UI 干扰
                            settings.displayZoomControls = false
                            // 允许视口 meta 标签生效(响应式 HTML)
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            // 安全:禁用文件/内容访问,防止 file:// 跨域读取
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            // 需用户手势播放媒体
                            settings.mediaPlaybackRequiresUserGesture = true
                            // 背景色透明,与 Compose Surface 融合
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            // 拦截所有外部导航(只放行 about:blank / data:),防止 <a> 跳转
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url ?: return true
                                    val scheme = url.scheme?.lowercase() ?: return true
                                    // 仅允许 about: 和 data: 协议,其余一律拦截
                                    return scheme != "about" && scheme != "data"
                                }
                            }
                            webViewRef[0] = this
                            // 用 loadDataWithBaseURL 加载,base URL 为 about:blank 避免相对路径越权
                            loadDataWithBaseURL(
                                "about:blank",
                                html,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 把 HTML 源码编码为 data URL,用 ACTION_VIEW 交给系统浏览器打开。
 *
 * 用 [android.util.Base64] 编码避免特殊字符破坏 data URL 解析;
 * 部分浏览器对 data: URL 有长度限制(Chrome ~2MB),超长时降级为 Toast 提示。
 */
private fun openInExternalBrowser(context: Context, html: String) {
    runCatching {
        val encoded = android.util.Base64.encodeToString(
            html.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val dataUrl = "data:text/html;charset=UTF-8;base64,$encoded"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dataUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { e ->
        MuseToast.show(
            context.getString(R.string.html_preview_open_in_browser_failed, e.message ?: ""),
        )
    }
}

/**
 * 复制 HTML 源码到剪贴板,Toast 反馈。
 */
private fun copyHtmlToClipboard(context: Context, html: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("html", html))
    MuseToast.show(context.getString(R.string.html_preview_source_copied))
}
