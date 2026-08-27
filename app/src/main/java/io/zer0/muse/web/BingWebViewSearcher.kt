package io.zer0.muse.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v1.0.81: 基于 headless Android WebView 的必应搜索兜底。
 *
 * 为什么需要它:
 *  对齐 Hana(桌面用 Electron 无头 Chromium)的思路——用真实浏览器加载必应结果页,
 *  等待 JS 渲染后注入提取脚本,而不是用 OkHttp 抓静态 HTML。后者在国内网络/反爬/
 *  地区重定向下表现不稳定(时好时坏的"抽卡"现象)。
 *
 *  本类是 [BingProvider] 的 HTTP 抓取失败/被拦截时的兜底,不替代 HTTP(HTTP 快)。
 *  它用 applicationContext 创建一个无界面 WebView,离屏加载 cn.bing.com,
 *  注入与 Hana browser-search-extractors.cjs 等价的提取 JS,拿到结构化结果。
 *
 * 设计要点:
 *  - 独立 WebView 实例,不与 [io.zer0.muse.tools.BrowserManager](浏览器自动化工具)共享,
 *    避免用户同时用浏览器工具时会话/cookie 互相干扰;
 *  - WebView 必须在主线程创建/访问,所有操作切 [Dispatchers.Main];
 *  - 每次搜索复用同一个 WebView(省去反复初始化 Chromium 的开销),但用独立 WebViewClient
 *    拦截单次加载,完成后恢复;
 *  - 检测验证码/同意页(captcha/verify/consent),命中返回空让上层走下一个引擎;
 *  - 固定超时,避免 WebView 卡死拖慢搜索。
 *
 * 线程安全:所有公开方法内部切主线程;外部从任意线程调用均可。
 */
class BingWebViewSearcher(private val appContext: Context) {

    companion object {
        private const val TAG = "BingWebView"
        /** 单次搜索超时(含页面加载 + JS 渲染 + 提取)。 */
        private const val SEARCH_TIMEOUT_MS = 20_000L
        /** 桌面 Chrome UA(与 Hana extractors 对齐),拿到桌面版 li.b_algo 结构。 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        /** Bing 桌面中文结果 cookie。 */
        private const val BING_COOKIE =
            "SRCHHPGUSR=NRSLT=10;SRCHLANG=zh-CN;HIDTTONS=1;ADLT=MODERATE; SRCHLANG=zh-CN; _SS=mlock=1"
    }

    @Volatile
    private var webViewRef: WebView? = null

    /**
     * 用 WebView 加载必应搜索页并提取结果。
     *
     * @param query 查询词(原始文本,内部 URL 编码)
     * @param maxResults 最多返回条数
     * @return 搜索结果;被拦截/超时/解析失败返回空列表(不抛异常,由上层 fallback)
     */
    suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = buildString {
                append("https://cn.bing.com/search?q=").append(encoded)
                append("&mkt=zh-CN&setlang=zh-CN&FORM=Z9FD1")
            }

            val outcome = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                loadAndExtract(wv, url, maxResults)
            }
            when {
                outcome == null -> {
                    Logger.w(TAG, "search 超时(${SEARCH_TIMEOUT_MS}ms): $query")
                    emptyList()
                }
                outcome.blocked -> {
                    Logger.w(TAG, "必应返回验证码/拦截页,放弃 WebView 兜底: $query")
                    emptyList()
                }
                else -> outcome.results
            }
        }

    /** 加载 [url] 并在页面渲染后注入提取 JS,返回结构化结果。 */
    private suspend fun loadAndExtract(
        wv: WebView,
        url: String,
        maxResults: Int,
    ): ExtractOutcome = suspendCancellableCoroutine { cont ->
        val previousClient = wv.webViewClient
        var resumed = false
        // 轮询尝试次数与间隔:必应部分结果是 JS 异步渲染,固定等 600ms 有时不够。
        // onPageFinished 后每 250ms 试一次提取,拿到 ≥1 条结果或达上限(约 3s)即结束。
        val maxAttempts = 12
        var attempt = 0
        val pollIntervalMs = 250L
        val watchdogDelayMs = 5_000L
        var pollingStarted = false

        lateinit var pollRunnable: Runnable
        lateinit var watchdogRunnable: Runnable

        fun startPolling(reason: String) {
            if (pollingStarted || resumed) return
            pollingStarted = true
            Logger.i(TAG, "start extraction polling: reason=$reason url=${wv.url ?: ""}")
            wv.post(pollRunnable)
        }

        fun finishOnce(outcome: ExtractOutcome) {
            if (resumed) return
            resumed = true
            wv.removeCallbacks(pollRunnable)
            wv.removeCallbacks(watchdogRunnable)
            wv.webViewClient = previousClient
            if (cont.isActive) cont.resume(outcome)
        }

        pollRunnable = Runnable {
            try {
                val script = buildExtractionScript(maxResults)
                wv.evaluateJavascript(script) { raw ->
                    val outcome = parseExtraction(raw)
                    Logger.i(TAG, "extract attempt=${attempt + 1}: url=${wv.url ?: ""}, blocked=${outcome.blocked}, results=${outcome.results.size}, raw=${raw?.take(180)}")
                    attempt++
                    // 拿到结果、被拦截、或达到最大尝试次数时结束;否则继续轮询。
                    if (outcome.blocked || outcome.results.isNotEmpty() || attempt >= maxAttempts) {
                        finishOnce(outcome)
                    } else {
                        wv.postDelayed(pollRunnable, pollIntervalMs)
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "evaluateJavascript 异常: ${e.message}")
                finishOnce(ExtractOutcome(false, emptyList()))
            }
        }

        // 某些模拟器/网络环境页面永远不触发 onPageFinished，但 DOM 已经可以读取。
        // watchdog 到点直接执行提取，不把“页面加载完成事件”当成唯一成功条件。
        watchdogRunnable = Runnable { startPolling("watchdog-${watchdogDelayMs}ms") }

        wv.webViewClient = object : WebViewClient() {
            private var finished = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Logger.i(TAG, "onPageStarted: ${url ?: ""}")
                // Bing 常见先完成一次页面、随后通过 rdr=1 重定向。第一版会在中间页
                // 上启动 evaluateJavascript，导致后续渲染线程卡住并最终 20 秒超时。
                // 发现新主文档后取消旧轮询，等最终页面的 onPageFinished 再提取。
                if (finished && !resumed) {
                    finished = false
                    // 关键：第一次页面已经把 pollingStarted 置 true；重定向后必须复位，
                    // 否则最终 onPageFinished 会被 startPolling 的幂等保护拦掉，整次搜索只能等到 20s 超时。
                    pollingStarted = false
                    wv.removeCallbacks(pollRunnable)
                    wv.removeCallbacks(watchdogRunnable)
                    Logger.i(TAG, "主文档发生重定向，重置提取状态，等待最终页面后再提取")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Logger.i(TAG, "onPageFinished: ${view?.url ?: url ?: ""}")
                if (finished) return
                finished = true
                // 页面完成后停止剩余资源加载，避免 evaluateJavascript 被持续的重定向/子资源
                // 占住渲染线程。主文档 DOM 已经可读，搜索结果提取不需要继续等图片和追踪脚本。
                view?.stopLoading()
                startPolling("page-finished")
            }

            @Deprecated("Deprecated in API 23")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                // 旧 API:仅主框架错误才失败,子资源错误忽略
                Logger.w(TAG, "onReceivedError(legacy): code=$errorCode desc=$description url=$failingUrl")
                if (!resumed) {
                    finishOnce(ExtractOutcome(false, emptyList()))
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    Logger.w(TAG, "页面加载错误: code=${error?.errorCode} desc=${error?.description} url=${request.url}")
                }
                if (request?.isForMainFrame == true && !resumed) {
                    finishOnce(ExtractOutcome(false, emptyList()))
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    Logger.w(TAG, "HTTP 错误: code=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} url=${request.url}")
                }
                if (request?.isForMainFrame == true && !resumed) {
                    finishOnce(ExtractOutcome(false, emptyList()))
                }
            }
        }

        cont.invokeOnCancellation {
            wv.stopLoading()
            wv.removeCallbacks(pollRunnable)
            wv.webViewClient = previousClient
        }

        Logger.i(TAG, "loadUrl: $url")
        // 不依赖 onPageStarted：部分 MuMu/WebView 网络失败时不会回调任何页面事件，
        // 但 evaluateJavascript 仍可能读取到错误页或部分 DOM。立即预约 watchdog，
        // 到点主动提取，避免一直等到 20 秒总超时。
        wv.postDelayed(watchdogRunnable, watchdogDelayMs)
        wv.loadUrl(url)
    }

    /**
     * 构造页面内提取 JS。逻辑对齐 Hana browser-search-extractors.cjs 的 bingResults():
     *  - 选择 li.b_algo,取 h2 a 的标题/链接,.b_caption p 等的摘要
     *  - 清理 bing /ck/a 跟踪跳转
     *  - 检测验证码/同意页
     * 返回一个 JSON 字符串: {blocked: bool, results: [{title,url,snippet}]}
     */
    private fun buildExtractionScript(max: Int): String {
        // JS 在页面上下文执行,返回 JSON 字符串(evaluateJavascript 会再 JSON 编码一层)
        return """
        (function(){
          function textOf(el){ return (el && (el.innerText || el.textContent) || '').replace(/\s+/g,' ').trim(); }
          function firstText(root, selectors){
            for (var i=0;i<selectors.length;i++){
              var el = root.querySelector(selectors[i]);
              var t = textOf(el); if (t) return t;
            }
            return '';
          }
          function firstAnchor(root, selectors){
            for (var i=0;i<selectors.length;i++){
              var el = root.querySelector(selectors[i]);
              if (el && el.href) return el;
            }
            return null;
          }
          function cleanUrl(raw){
            if(!raw) return '';
            var url;
            try { url = new URL(raw, location.href); } catch(e){ return ''; }
            if (!/^https?:$/.test(url.protocol)) return '';
            return url.href;
          }
          function hasCaptcha(){
            var bodyText = textOf(document.body).toLowerCase();
            var href = (location.href || '').toLowerCase();
            return href.indexOf('/sorry/') >= 0 ||
              href.indexOf('captcha') >= 0 ||
              bodyText.indexOf('verify you are human') >= 0 ||
              bodyText.indexOf('detected unusual traffic') >= 0 ||
              bodyText.indexOf('please verify') >= 0;
          }
          function hasConsent(){
            var bodyText = textOf(document.body).toLowerCase();
            return bodyText.indexOf('consent') >= 0 && bodyText.indexOf('privacy') >= 0
              && document.querySelectorAll('li.b_algo').length === 0;
          }
          if (hasCaptcha() || hasConsent()) {
            return JSON.stringify({blocked:true, results:[]});
          }
          var items = Array.prototype.slice.call(document.querySelectorAll('li.b_algo'));
          var results = [];
          for (var i=0;i<items.length && results.length < $max;i++){
            var item = items[i];
            var cls = item.className || '';
            if (cls.indexOf('b_algo_default') >= 0 || cls.indexOf('b_card') >= 0 ||
                cls.indexOf('b_ans') >= 0 || cls.indexOf('b_ad') >= 0) continue;
            var anchor = firstAnchor(item, ['h2 a','a']);
            if(!anchor) continue;
            var title = textOf(anchor);
            var url = cleanUrl(anchor.href);
            if(!title || !url) continue;
            var snippet = firstText(item, [
              '.b_caption p','.b_algoSlug','.b_caption .b_paractl',
              '.b_lineclamp2','.b_lineclamp3','.b_lineclamp4',
              '.b_focusTextLarge','.b_caption','.b_dList'
            ]);
            if(snippet.length > 300) snippet = snippet.substring(0,300) + '…';
            results.push({title:title, url:url, snippet:snippet});
          }
          return JSON.stringify({blocked:false, results:results});
        })();
        """.trimIndent()
    }

    /** 解析 evaluateJavascript 返回值(它是 JSON-encoded 字符串)。 */
    private fun parseExtraction(raw: String?): ExtractOutcome {
        if (raw.isNullOrBlank() || raw == "null") {
            return ExtractOutcome(false, emptyList())
        }
        return try {
            // evaluateJavascript 返回的是 JSON 编码值,字符串会带外层引号,这里两次解析
            val outer = AppJsonLenient.parseToJsonElement(raw)
            val inner = if (outer is kotlinx.serialization.json.JsonPrimitive) {
                AppJsonLenient.parseToJsonElement(outer.content)
            } else outer
            val obj = inner as? kotlinx.serialization.json.JsonObject
                ?: return ExtractOutcome(false, emptyList())
            val blocked = (obj["blocked"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            val arr = obj["results"] as? kotlinx.serialization.json.JsonArray
                ?: kotlinx.serialization.json.JsonArray(emptyList())
            val results = arr.mapNotNull { item ->
                val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val title = (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val snippet = (o["snippet"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                if (title.isBlank() || url.isBlank()) null
                else WebSearchResult(title = title, url = url, snippet = snippet, source = "Bing")
            }
            ExtractOutcome(blocked, results)
        } catch (e: Exception) {
            Logger.w(TAG, "解析提取结果失败: ${e.message}; raw=${raw.take(200)}")
            ExtractOutcome(false, emptyList())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BingWebViewSearcher WebView 必须在主线程访问"
        }
        webViewRef?.let { return it }

        val wv = WebView(appContext.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = DESKTOP_UA
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            // 安全限制
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false

            webViewClient = object : WebViewClient() {
                /** ROM 兼容:渲染进程崩溃时销毁,下次搜索自动重建。 */
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    Logger.e(TAG, "WebView 渲染进程崩溃: ${detail?.didCrash()},销毁重建")
                    webViewRef = null
                    view?.let { v ->
                        runCatching { (v.parent as? android.view.ViewGroup)?.removeView(v) }
                        runCatching { v.destroy() }
                    }
                    return true
                }
            }
        }
        // 设置必应 cookie(语言/安全搜索/结果数)
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            BING_COOKIE.split(";").forEach { c ->
                val trimmed = c.trim()
                if (trimmed.isNotEmpty()) {
                    cookieManager.setCookie("https://cn.bing.com", "$trimmed; domain=.bing.com; path=/")
                }
            }
            CookieManager.getInstance().flush()
        }.onFailure { Logger.w(TAG, "设置 cookie 失败: ${it.message}") }

        webViewRef = wv
        Logger.i(TAG, "headless WebView 已初始化")
        return wv
    }

    /** 释放 WebView 资源(可在设置切换/低内存时调用)。 */
    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        webViewRef?.let { wv ->
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
        webViewRef = null
    }

    private data class ExtractOutcome(val blocked: Boolean, val results: List<WebSearchResult>)

    // 独立 Json 实例(忽略未知键,宽松解析)
    private val AppJsonLenient = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
