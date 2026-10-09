package io.zer0.muse.ui.common.media

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView

/**
 * v2.6.6: 持有“上次已加载的 HTML”的小容器。
 *
 * 用类包一层是为了让 `remember { ... }` 能跨重组保留可变状态
 * （直接 remember 一个 String 值会被重组时的参数变化重置，无法用于“是否变化”判定）。
 */
private class LastLoadedHtml {
    var value: String? = null
}

/**
 * 生命周期感知的 WebView Composable 容器。
 *
 * v1.88 已知限制修复: 替代项目中直接使用 `AndroidView { WebView(it) }` 的模式,
 * 统一为 [LifecycleAwareWebView],自动处理:
 *
 *  - 创建 [LifecycleAwareWebView] 并注册到 [LocalLifecycleOwner];
 *  - ON_PAUSE / ON_RESUME 时自动暂停/恢复 JS 定时器与动画(暂停 [WebView.pauseTimers] /
 *    [WebView.onPause] / 恢复 [WebView.resumeTimers] / [WebView.onResume]);
 *  - ON_DESTROY 时销毁 WebView 并注销观察者;
 *  - Composable 离开组合时(宿主未销毁的场景)兜底 destroy + removeObserver,避免泄漏。
 *
 * 调用方只需关注业务配置:
 *  - [htmlContent] + [baseUrl]:自动调用 [WebView.loadDataWithBaseURL] 加载;
 *  - [javaScriptEnabled] / [domStorageEnabled]:常见 settings 开关;
 *  - [webViewClient]:自定义导航拦截(默认不拦截);
 *  - [onWebViewCreated]:创建后做额外 settings 配置或自定义加载逻辑;
 *  - [onPageFinished]:页面加载完成回调。
 *
 * 安全默认(覆盖原项目所有 WebView 使用点的通用配置):
 *  - 禁用文件/内容访问(防止 file:// 跨域读取);
 *  - 需用户手势播放媒体;
 *  - 背景透明(可由 [transparentBackground] 关闭)。
 *
 * @param modifier Compose 修饰符
 * @param htmlContent 待加载的 HTML 内容;为 null 时不自动加载(由调用方在 [onWebViewCreated] 中加载)
 * @param baseUrl 加载 HTML 时的 base URL;`file:///android_asset/` 用于加载本地 assets 资源,
 *                null 表示无 base URL
 * @param mimeType 内容类型,默认 text/html
 * @param encoding 编码,默认 UTF-8
 * @param historyUrl 历史记录 URL,通常 null
 * @param javaScriptEnabled 是否启用 JavaScript,默认 false(SVG/HTML 卡片禁用,公式/图表启用)
 * @param domStorageEnabled 是否启用 DOM storage,默认 false
 * @param transparentBackground 是否将背景设为透明,默认 true
 * @param webViewClient 自定义 WebViewClient;不传则使用默认 WebViewClient(不拦截导航)
 * @param onWebViewCreated WebView 创建后回调,调用方可在此做额外 settings 配置或自定义加载
 * @param onWebViewReleased WebView 即将销毁前回调(对应 AndroidView 的 onRelease),
 *        调用方可在此清理对 WebView 的引用,避免持有已销毁实例
 * @param onPageFinished 页面加载完成回调
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LifecycleAwareWebViewContainer(
    modifier: Modifier = Modifier,
    htmlContent: String? = null,
    baseUrl: String? = "file:///android_asset/",
    mimeType: String = "text/html",
    encoding: String = "UTF-8",
    historyUrl: String? = null,
    javaScriptEnabled: Boolean = false,
    domStorageEnabled: Boolean = false,
    transparentBackground: Boolean = true,
    webViewClient: WebViewClient? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null,
    onWebViewReleased: ((WebView) -> Unit)? = null,
    onPageFinished: ((WebView, String) -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // v2.6.6: 记住上次已加载的 HTML，避免内容未变时重复 loadDataWithBaseURL。
    // 必须用 remember 跨重组保留，否则每次重组重置 → 又变回每帧重载。
    val lastLoadedHtmlRef = remember { LastLoadedHtml() }

    // 解析最终使用的 WebViewClient:
    //  - 调用方提供则直接用;
    //  - 否则若需要 onPageFinished,包装一个默认 client 转发回调;
    //  - 否则用最简 WebViewClient。
    val effectiveClient = remember(webViewClient, onPageFinished) {
        when {
            webViewClient != null -> webViewClient
            onPageFinished != null -> object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    onPageFinished.invoke(view, url)
                }
            }
            else -> WebViewClient()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // 通过工厂创建 LifecycleAwareWebView 并自动绑定到当前 LifecycleOwner
            LifecycleAwareWebViewFactory.create(ctx, lifecycleOwner).apply {
                settings.javaScriptEnabled = javaScriptEnabled
                settings.domStorageEnabled = domStorageEnabled
                // 安全默认:禁用文件/内容访问,需用户手势播放媒体
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
                // v2.6.6 安全加固(所有卡片公共路径):
                //  - 禁止文件/内容 URL 跨域读取(含 file:// 走 assets 的场景也已关);
                //  - 禁止 JS 弹窗(window.alert/confirm/prompt)—— 模型产卡片可能滥用;
                //  - 禁止混合内容与外部协议导航;
                //  - 禁地理位置/数据库等非必要能力。
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setGeolocationEnabled(false)
                settings.databaseEnabled = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (transparentBackground) {
                    setBackgroundColor(Color.TRANSPARENT)
                }
                this.webViewClient = effectiveClient
                // 调用方自定义配置(自定义 settings / 自定义加载逻辑等)
                onWebViewCreated?.invoke(this)
                // 加载 HTML 内容(htmlContent 为 null 时由调用方在 onWebViewCreated 中加载)
                if (htmlContent != null) {
                    lastLoadedHtmlRef.value = htmlContent
                    loadDataWithBaseURL(baseUrl, htmlContent, mimeType, encoding, historyUrl)
                }
            }
        },
        update = { webView ->
            // v2.6.6: 内容未变时不重新加载 —— 原逻辑每次重组都 loadDataWithBaseURL,
            // 流式渐进渲染下会导致每个 token 重载整个 WebView(卡死)。
            // 只有 HTML 真正变化时才刷新。
            if (htmlContent != null && htmlContent != lastLoadedHtmlRef.value) {
                lastLoadedHtmlRef.value = htmlContent
                webView.loadDataWithBaseURL(baseUrl, htmlContent, mimeType, encoding, historyUrl)
            }
        },
        onRelease = { webview ->
            // Composable 离开组合时调用(宿主未销毁的场景,如条件渲染切换):
            //  - 通知调用方清理对 WebView 的引用(在 destroy 之前);
            //  - 注销生命周期观察者,避免后续 ON_DESTROY 重复 destroy 已销毁实例;
            //  - 兜底 destroy 释放 native 资源。
            onWebViewReleased?.invoke(webview)
            if (webview is LifecycleAwareWebView) {
                lifecycleOwner.lifecycle.removeObserver(webview)
            }
            webview.destroy()
        },
    )
}
