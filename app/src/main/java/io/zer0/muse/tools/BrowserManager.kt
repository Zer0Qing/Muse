package io.zer0.muse.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * P2-6 BrowserManager:浏览器自动化管理器。
 *
 * 持有一个 headless [WebView] 实例,让 AI Agent 能通过工具调用完成浏览器自动化:
 *  - [navigate] 加载 URL,完成后更新 [currentUrl] / [currentTitle] / [currentHtml]
 *  - [evaluateJs] 在当前页执行任意 JS,返回 JSON-encoded 字符串
 *  - [click] / [type] / [extractText] / [extractAttribute] 基于 CSS 选择器的常用操作
 *  - [scrollToBottom] 翻页加载更多内容(无限滚动场景)
 *  - [captureScreenshot] 把当前 WebView 渲染为 Base64 PNG
 *
 * 设计要点(参考 [JsSandbox] 的 WebView 沙盒模式):
 *  - WebView 必须在主线程创建和访问,所有 suspend 函数用 [Dispatchers.Main] 切换
 *  - [evaluateJs] 用 [suspendCancellableCoroutine] + `ValueCallback<String>` 桥接异步回调
 *  - 所有操作 10 秒超时([DEFAULT_TIMEOUT_MS]),用 [withTimeoutOrNull] 包裹避免无限挂起
 *  - 安全限制:禁用 file access / content access(防止 file:// 跨域读取),
 *    但允许网络加载(浏览器需要拉取远程页面)
 *  - WebView 用 applicationContext 创建,headless 运行(不附加到任何 View 树),
 *    可被 BrowserAutomationScreen 之外的 AI 工具调用
 *
 * 与 [BrowserAutomationScreen] 的关系:
 *  - BrowserManager 持有自动化用 WebView(AI 调用),Screen 持有展示用 WebView(用户演示)
 *  - 两者独立,互不干扰;UI 仅用于演示自动化能力,不与 AI 共享 WebView
 *
 * @param context 应用 Context(用 applicationContext 创建 WebView,避免 Activity 泄漏)
 */
class BrowserManager(private val context: Context) {

    companion object {
        private const val TAG = "BrowserManager"

        /** 所有 WebView 异步操作的默认超时(毫秒)。 */
        private const val DEFAULT_TIMEOUT_MS = 10_000L

        /** currentHtml 截断阈值(50KB,防止过长导致 LLM 上下文爆炸)。 */
        private const val MAX_HTML_LENGTH = 50 * 1024

        /** 提取页面 HTML 的 JS 片段。 */
        private const val GET_OUTER_HTML_JS =
            "(function(){ try { return document.documentElement.outerHTML; } catch(e){ return ''; } })();"
    }

    // ── StateFlows(供 UI / AI 观察当前页状态)─────────────────────────────

    private val _currentUrl = MutableStateFlow("")
    /** 当前页 URL。 */
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _currentTitle = MutableStateFlow("")
    /** 当前页 title。 */
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _currentHtml = MutableStateFlow("")
    /** 当前页 HTML(截断到 50KB 防止过长)。 */
    val currentHtml: StateFlow<String> = _currentHtml.asStateFlow()

    private val _currentScreenshot = MutableStateFlow<String?>(null)
    /** 页面截图(Base64 PNG,可选)。 */
    val currentScreenshot: StateFlow<String?> = _currentScreenshot.asStateFlow()

    /** WebView 单例引用(必须在主线程访问)。@Volatile 保证可见性。 */
    @Volatile
    private var webViewRef: WebView? = null

    // ── 公开 API ──────────────────────────────────────────────────────────

    /**
     * 导航到 [url]。加载完成后更新 [currentUrl] / [currentTitle] / [currentHtml]。
     *
     * @param url 目标 URL(可省略 scheme,自动补 https://)
     * @return Result<Unit> — 成功或失败(超时/异常)
     */
    suspend fun navigate(url: String): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val target = normalizeUrl(url)
            val webView = ensureWebView()

            // 临时替换 WebViewClient 拦截 onPageFinished;用 suspendCancellableCoroutine 等待完成
            val success = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val previousClient = webView.webViewClient
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            _currentUrl.value = view?.url ?: url ?: target
                            _currentTitle.value = view?.title ?: ""
                            // 异步拉取 HTML,即便失败也认为导航完成
                            view?.evaluateJavascript(GET_OUTER_HTML_JS) { raw ->
                                _currentHtml.value = parseJsValue(raw).take(MAX_HTML_LENGTH)
                            }
                            // 恢复原有 client,避免后续 navigate 拦截器累积
                            view?.webViewClient = previousClient
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                    webView.loadUrl(target)
                }
            }

            if (success == null) {
                Logger.w(TAG, "navigate 超时: $target")
                Result.failure(java.util.concurrent.TimeoutException("navigate 超时(${DEFAULT_TIMEOUT_MS}ms): $target"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "navigate 异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 执行任意 JavaScript。
     *
     * @param script JS 代码(表达式或语句块)
     * @return Result<String> — 成功返回 JSON-encoded 字符串(如 `"hello"` / `42` / `true` / `null`)
     */
    suspend fun evaluateJs(script: String): Result<String> = withContext(Dispatchers.Main) {
        try {
            val webView = ensureWebView()
            val raw = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    webView.evaluateJavascript(script) { value ->
                        // withTimeoutOrNull 取消后回调仍可能触发,用 isActive 守卫
                        if (cont.isActive) cont.resume(value ?: "null")
                    }
                }
            } ?: return@withContext Result.failure(
                java.util.concurrent.TimeoutException("evaluateJs 超时(${DEFAULT_TIMEOUT_MS}ms)")
            )
            Result.success(raw)
        } catch (e: Exception) {
            Logger.e(TAG, "evaluateJs 异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 点击 CSS 选择器命中的第一个元素。
     * @return Result<Boolean> — true 表示找到并点击,false 表示未命中
     */
    suspend fun click(selector: String): Result<Boolean> {
        val script = buildString {
            append("(function(){ try { var el = document.querySelector(")
            append(jsStringLiteral(selector))
            append("); if(!el) return false; el.click(); return true; } catch(e){ return false; } })();")
        }
        return evaluateJs(script).mapCatching { parseJsValue(it) == "true" }
    }

    /**
     * 向 CSS 选择器命中的第一个 input/textarea 输入文本。
     * 触发 input + change 事件以兼容 React/Vue 等框架。
     * @return Result<Boolean> — true 表示找到并输入,false 表示未命中
     */
    suspend fun type(selector: String, text: String): Result<Boolean> {
        val script = buildString {
            append("(function(){ try { var el = document.querySelector(")
            append(jsStringLiteral(selector))
            append("); if(!el) return false; el.focus(); el.value = ")
            append(jsStringLiteral(text))
            append("; el.dispatchEvent(new Event('input',{bubbles:true}));")
            append(" el.dispatchEvent(new Event('change',{bubbles:true})); return true; } catch(e){ return false; } })();")
        }
        return evaluateJs(script).mapCatching { parseJsValue(it) == "true" }
    }

    /**
     * 提取 CSS 选择器命中元素的 textContent。
     * @return Result<String> — 元素的文本内容;未命中返回空串
     */
    suspend fun extractText(selector: String): Result<String> {
        val script = buildString {
            append("(function(){ try { var el = document.querySelector(")
            append(jsStringLiteral(selector))
            append("); return el ? el.textContent : null; } catch(e){ return null; } })();")
        }
        return evaluateJs(script).mapCatching { parseJsValue(it) }
    }

    /**
     * 提取 CSS 选择器命中元素的指定属性。
     * @return Result<String> — 属性值;未命中或属性不存在返回空串
     */
    suspend fun extractAttribute(selector: String, attribute: String): Result<String> {
        val script = buildString {
            append("(function(){ try { var el = document.querySelector(")
            append(jsStringLiteral(selector))
            append("); return el ? el.getAttribute(")
            append(jsStringLiteral(attribute))
            append(") : null; } catch(e){ return null; } })();")
        }
        return evaluateJs(script).mapCatching { parseJsValue(it) }
    }

    /**
     * 滚动到页面底部。常用于无限滚动场景加载更多内容。
     * @return Result<Unit>
     */
    suspend fun scrollToBottom(): Result<Unit> {
        val result = evaluateJs(
            "(function(){ window.scrollTo(0, document.body.scrollHeight); return document.body.scrollHeight; })();"
        )
        return result.map { Unit }
    }

    /**
     * 截图当前 WebView 渲染内容,返回 Base64 编码的 PNG。
     * 同步更新 [currentScreenshot]。
     */
    suspend fun captureScreenshot(): Result<String> = withContext(Dispatchers.Main) {
        try {
            val webView = ensureWebView()
            val width = webView.width.coerceAtLeast(1)
            val height = webView.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, baos)
            bitmap.recycle()
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            _currentScreenshot.value = base64
            Result.success(base64)
        } catch (e: Exception) {
            Logger.e(TAG, "captureScreenshot 异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 关闭浏览器:停止加载 + 销毁 WebView + 清空 StateFlow。
     * 必须在主线程操作 WebView;非主线程调用会 post 到主线程。
     */
    fun close() {
        val webView = webViewRef ?: return
        val destroyAction = {
            try {
                webView.stopLoading()
                webView.removeAllViews()
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
            } catch (e: Exception) {
                Logger.w(TAG, "close destroy 异常: ${e.message}")
            }
            webViewRef = null
            _currentUrl.value = ""
            _currentTitle.value = ""
            _currentHtml.value = ""
            _currentScreenshot.value = null
            Logger.i(TAG, "BrowserManager 已关闭")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyAction()
        } else {
            webView.post { destroyAction() }
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────

    /**
     * 创建/获取 WebView 单例。必须在主线程调用。
     * 第一次调用会创建 WebView + 配置安全限制。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BrowserManager WebView 必须在主线程创建和访问"
        }
        webViewRef?.let { return it }

        val wv = WebView(context.applicationContext).apply {
            // ── WebSettings:启用 JS + 浏览器必需功能 + 严格安全限制 ──
            settings.javaScriptEnabled = true
            // 严格安全:禁用 file/content access(防止 file:// 跨域读取)
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            // DOM storage(localStorage / sessionStorage)— 现代网页普遍依赖
            settings.domStorageEnabled = true
            // 浏览器需要网络加载(与 JsSandbox 不同,这里允许网络)
            settings.blockNetworkLoads = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            // 视口与缩放
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            // ── WebViewClient:监听页面加载完成,更新 StateFlow ──
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    _currentUrl.value = view?.url ?: url ?: ""
                    _currentTitle.value = view?.title ?: ""
                    view?.evaluateJavascript(GET_OUTER_HTML_JS) { raw ->
                        _currentHtml.value = parseJsValue(raw).take(MAX_HTML_LENGTH)
                    }
                }
            }

            // ── WebChromeClient:同步 title 进度 ──
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    _currentTitle.value = title ?: ""
                }
            }
        }

        webViewRef = wv
        Logger.i(TAG, "BrowserManager WebView 已初始化")
        return wv
    }

    /** URL 规范化:补全 scheme,空串降级为 about:blank。 */
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
     *
     * 回调返回值规律:
     *  - null / "null" → 空串
     *  - "\"hello\"" → hello(JSON 字符串解包)
     *  - "42" / "true" → 42 / true(原始字面量)
     *  - "{\"a\":1}" → {"a":1}(原始 JSON 文本)
     */
    private fun parseJsValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            val element = AppJson.parseToJsonElement(raw)
            // 字符串/数字/布尔字面量 → 取 content(JsonPrimitive 自动解包引号)
            // 对象/数组 → toString() 原样返回 JSON 文本
            element.jsonPrimitive.contentOrNull ?: ""
        } catch (e: Exception) {
            // 非 JSON 字面量(理论上不会出现,evaluateJavascript 总返回 JSON-encoded 值)
            raw
        }
    }

    /**
     * 把 Kotlin 字符串编码为 JS 字符串字面量(双引号包裹 + JSON 风格转义)。
     * 用 JSON-style 转义避免注入风险(选择器/文本中可能含特殊字符)。
     */
    private fun jsStringLiteral(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u").append("%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
