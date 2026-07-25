package io.zer0.muse.ui

import android.annotation.SuppressLint
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import io.zer0.muse.R
import io.zer0.muse.ui.common.IosTactileButton
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.LifecycleAwareWebView
import io.zer0.muse.ui.common.LifecycleAwareWebViewFactory
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * P2-6 BrowserAutomationScreen:浏览器自动化演示页(用户展示用)。
 *
 * 全屏 WebView + 顶部地址栏 + 底部操作栏(后退/前进/刷新/执行 JS/关闭),
 * 让用户能直观看到 AI 浏览器自动化的能力。
 *
 * 与 [io.zer0.muse.tools.BrowserManager] 的关系:
 *  - 本页持有独立的展示用 WebView(与 AI 调用的 headless WebView 互不干扰)
 *  - AI 通过 BrowserManager 操作 headless WebView 时,本页 UI 不会同步变化
 *  - 本页仅供用户演示与手动测试,不影响 AI 工具行为
 *
 * 设计要点(遵循项目设计令牌):
 *  - 顶部 [IosTopBar]:标题"浏览器自动化" + 返回按钮
 *  - 地址栏:[OutlinedTextField] + 软键盘"前往"动作触发导航(loadUrl 到展示 WebView)
 *  - 底部操作栏:[IosTactileButton] x 5(后退/前进/刷新/执行 JS/关闭)
 *  - 全部使用 [MuseShapes] / [MusePaddings] / [MuseIconSizes] 令牌
 *  - 不使用 Material3 默认 Button(用 IosTactileButton 替代)
 *
 * 安全说明:
 *  - WebView 严格禁用 file/content access(防止 file:// 跨域读取)
 *  - 允许网络加载(浏览器需要拉取远程页面)
 *  - 不调用 addJavascriptInterface(不暴露宿主对象)
 *
 * @param onBack 返回回调
 */
@Composable
fun BrowserAutomationScreen(
    onBack: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // 地址栏输入文本(rememberSaveable 保证旋转/配置变更后保留)
    var addressText by rememberSaveable { mutableStateOf("") }
    // WebView 引用缓存(DisposableEffect 中释放,避免泄漏)
    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    // v1.88 修复: WebView 自身已实现生命周期感知(LifecycleAwareWebView),
    // ON_PAUSE/ON_RESUME/ON_DESTROY 由 WebView 内部自动处理(含 pauseTimers/resumeTimers)。
    // 此处仅保留离开组合时的兜底销毁与 DOM storage 清理逻辑。
    DisposableEffect(lifecycleOwner) {
        onDispose {
            // 兜底释放:离开组合时销毁展示用 WebView(宿主未销毁的场景,如导航返回)
            webViewRef[0]?.also {
                // 注销生命周期观察者,避免后续 ON_DESTROY 重复 destroy 已销毁实例
                if (it is LifecycleAwareWebView) {
                    lifecycleOwner.lifecycle.removeObserver(it)
                }
                it.destroy()
                webViewRef[0] = null
            }
            WebStorage.getInstance().deleteAllData()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶部栏:返回 + 标题 ──
            IosTopBar(
                title = stringResource(R.string.browser_automation_title),
                onBack = onBack,
            )

            // ── 地址栏:OutlinedTextField + 软键盘"前往"动作 ──
            AddressBar(
                text = addressText,
                onTextChange = { addressText = it },
                onNavigate = { url ->
                    // 直接在展示 WebView 上加载 URL(规范化 URL 后调用 loadUrl)
                    val wv = webViewRef[0]
                    if (wv != null) {
                        val target = normalizeUrl(url)
                        wv.loadUrl(target)
                        MuseToast.show("已导航到: $target")
                    } else {
                        MuseToast.show("WebView 未初始化")
                    }
                },
            )

            // ── WebView 容器:占满剩余空间 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                AndroidView(
                    factory = { ctx ->
                        // v1.88 修复: createDisplayWebView 内部使用 LifecycleAwareWebViewFactory.create,
                        // 自动绑定到 lifecycleOwner,后台时暂停 JS 定时器/动画。
                        createDisplayWebView(ctx, lifecycleOwner).also { webViewRef[0] = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ── 底部操作栏:后退 / 前进 / 刷新 / 执行 JS / 关闭 ──
            BottomActionBar(
                onBack = {
                    val wv = webViewRef[0]
                    if (wv?.canGoBack() == true) wv.goBack() else MuseToast.show("没有历史记录可后退")
                },
                onForward = {
                    val wv = webViewRef[0]
                    if (wv?.canGoForward() == true) wv.goForward() else MuseToast.show("没有历史记录可前进")
                },
                onRefresh = {
                    webViewRef[0]?.reload()
                },
                onRunJs = {
                    val wv = webViewRef[0]
                    if (wv != null) {
                        // 演示:执行一段简单的 JS 读取当前页面 title,Toast 反馈
                        wv.evaluateJavascript("document.title") { result ->
                            val title = parseJsValue(result)
                            MuseToast.show("页面标题: $title")
                        }
                    } else {
                        MuseToast.show("WebView 未初始化")
                    }
                },
                onClose = {
                    val wv = webViewRef[0]
                    if (wv != null) {
                        wv.stopLoading()
                        wv.destroy()
                        webViewRef[0] = null
                        MuseToast.show("展示用浏览器已关闭")
                    }
                    onBack()
                },
            )
        }
    }
}

/**
 * 地址栏:输入 URL + 软键盘"前往"动作触发导航。
 *
 * @param text 当前输入文本
 * @param onTextChange 文本变化回调
 * @param onNavigate 导航回调(用户按软键盘"前往"或回车触发)
 */
@Composable
private fun AddressBar(
    text: String,
    onTextChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MusePaddings.screen,
                vertical = MusePaddings.contentGap,
            ),
        placeholder = {
            Text(text = stringResource(R.string.browser_address_bar))
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = true,
        shape = MuseShapes.semiLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(
            onGo = {
                val url = text.trim()
                if (url.isNotEmpty()) onNavigate(url)
            },
        ),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * 底部操作栏:5 个 iOS 风格触觉按钮(后退/前进/刷新/执行 JS/关闭)。
 *
 * @param onBack 后退回调
 * @param onForward 前进回调
 * @param onRefresh 刷新回调
 * @param onRunJs 执行 JS 回调
 * @param onClose 关闭回调
 */
@Composable
private fun BottomActionBar(
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onRunJs: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MusePaddings.contentGap,
        shape = MuseShapes.huge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MusePaddings.screen,
                    vertical = MusePaddings.contentGap,
                ),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IosTactileButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.browser_back),
                onClick = onBack,
            )
            IosTactileButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.browser_forward),
                onClick = onForward,
            )
            IosTactileButton(
                icon = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.browser_refresh),
                onClick = onRefresh,
            )
            IosTactileButton(
                icon = Icons.Default.Code,
                contentDescription = stringResource(R.string.browser_run_js),
                onClick = onRunJs,
            )
            IosTactileButton(
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.browser_close),
                onClick = onClose,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 创建展示用 WebView 实例(供 AndroidView.factory 调用)。
 *
 * v1.88 修复: 改用 [LifecycleAwareWebViewFactory.create] 创建 [LifecycleAwareWebView],
 * 自动绑定到 [lifecycleOwner],后台时暂停 JS 定时器/动画。
 *
 * 安全配置与 BrowserManager.ensureWebView() 一致:
 *  - 启用 JS / DOM storage(现代网页普遍依赖)
 *  - 禁用 file/content access(防止 file:// 跨域读取)
 *  - 允许网络加载(浏览器需要拉取远程页面)
 *  - 拦截外部导航(只放行 http/https/about/data 协议)
 *
 * @param context Android 上下文
 * @param lifecycleOwner 宿主生命周期所有者,用于绑定 WebView 生命周期观察
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createDisplayWebView(
    context: android.content.Context,
    lifecycleOwner: LifecycleOwner,
): LifecycleAwareWebView {
    return LifecycleAwareWebViewFactory.create(context, lifecycleOwner).apply {
        settings.javaScriptEnabled = true
        // 严格安全:禁用 file/content access
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        // DOM 存储(localStorage / sessionStorage)
        settings.domStorageEnabled = true
        // 允许网络加载
        settings.blockNetworkLoads = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        // 视口与缩放
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // 背景色透明,与 Compose Surface 融合
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 拦截非 http(s)/about/data 协议的 URL(防止 intent:// 等敏感协议跳转)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): Boolean {
                val url = request?.url ?: return true
                val scheme = url.scheme?.lowercase() ?: return true
                // 仅允许 http/https/about/data 协议,其余一律拦截
                return scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data"
            }
        }
    }
}

/**
 * URL 规范化:补全 scheme,空串降级为 about:blank。
 * 与 BrowserManager.normalizeUrl 行为一致,避免重复引入 BrowserManager 依赖。
 */
private fun normalizeUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return "about:blank"
    if (
        trimmed.startsWith("http://") ||
        trimmed.startsWith("https://") ||
        trimmed.startsWith("about:") ||
        trimmed.startsWith("file:")
    ) {
        return trimmed
    }
    return "https://$trimmed"
}

/**
 * 解析 evaluateJavascript 回调返回的 JSON-encoded 字符串。
 * 与 BrowserManager.parseJsValue 行为一致(轻量内联实现,避免依赖 BrowserManager)。
 */
private fun parseJsValue(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return try {
        val element = io.zer0.common.AppJson.parseToJsonElement(raw)
        element.jsonPrimitive.contentOrNull ?: ""
    } catch (_: Exception) {
        raw
    }
}
