package io.zer0.muse.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * JavaScript 执行沙盒(单例)。
 *
 * 参考自 rikkahub 的 QuickJS 实现思路;Android 无内置 JS 引擎且不能新增大型依赖,
 * 改用 WebView 的 V8 引擎执行 JS,让 AI Agent 能跑数学计算/数据处理/简单算法。
 *
 * 设计要点:
 *  - 持有单个 WebView 实例,必须在主线程创建和访问([Dispatchers.Main] 切换)
 *  - 用 [WebView.evaluateJavascript] 执行代码,回调中拿到 JSON 字符串返回值
 *  - [WebChromeClient.onConsoleMessage] 拦截 console.log/warn/error 收集日志
 *  - 安全限制:禁用 fetch / XMLHttpRequest / window.open / window.location 写入;
 *    [WebViewClient.shouldInterceptRequest] 拦截所有网络请求;
 *    [WebSettings.setBlockNetworkLoads] 阻止网络加载;
 *    不加载任何外部 URL(只 loadDataWithBaseURL("about:blank", ...))
 *  - 超时机制:[withTimeoutOrNull] 包裹 evaluateJavascript 回调;JS 本身无法中断,
 *    但 Kotlin 侧会返回超时错误,避免无限挂起
 *  - 线程安全:所有 WebView 访问都在 [Dispatchers.Main] 协程上下文中,
 *    日志收集用 synchronized 保护
 *
 * 使用前必须先调用 [init] 注入 Application Context(由 [CodeExecutionTool] / [SkillExecutor] 桥接)。
 */
object JsSandbox {

    private const val TAG = "JsSandbox"

    /** 注入 JS 桥接对象的名称(未使用 addJavascriptInterface,留作扩展)。 */
    private const val BRIDGE_NAME = "KtSandbox"

    /** 注入的安全初始化 JS:禁用网络 API 与导航 API。 */
    private const val INIT_JS = """
        (function() {
            'use strict';
            // 禁用 fetch / XMLHttpRequest:沙盒内不允许任何网络请求
            try { Object.defineProperty(window, 'fetch', { value: function() { throw new Error('fetch is disabled in sandbox'); }, writable: false, configurable: false }); } catch (e) {}
            try { Object.defineProperty(window, 'XMLHttpRequest', { value: function() { throw new Error('XMLHttpRequest is disabled in sandbox'); }, writable: false, configurable: false }); } catch (e) {}
            try { Object.defineProperty(window, 'WebSocket', { value: function() { throw new Error('WebSocket is disabled in sandbox'); }, writable: false, configurable: false }); } catch (e) {}
            // 禁用 window.open / window.close:防止导航跳转
            try { window.open = function() { throw new Error('window.open is disabled in sandbox'); }; } catch (e) {}
            try { window.close = function() {}; } catch (e) {}
            // 屏蔽 navigator.sendBeacon
            try { if (navigator && navigator.sendBeacon) navigator.sendBeacon = function() { throw new Error('sendBeacon is disabled in sandbox'); }; } catch (e) {}
            // 屏蔽 document.write / writeln:防止注入 DOM
            try { document.write = function() {}; } catch (e) {}
            try { document.writeln = function() {}; } catch (e) {}
        })();
    """

    @Volatile private var webViewRef: WebView? = null
    @Volatile private var appContext: Context? = null

    /** 日志缓冲区:每次 execute 前清空。synchronized 保护多线程访问。 */
    private val logsLock = Any()
    private val currentLogs = mutableListOf<String>()

    /** JS 沙盒执行结果。 */
    data class JsResult(
        /** 执行返回值(JSON 字符串形式,可由调用方再解析)。 */
        val value: Any?,
        /** console.log/warn/error 收集到的日志。 */
        val consoleLogs: List<String>,
        /** 执行错误信息(超时/JS 异常),null 表示成功。 */
        val error: String?,
    )

    /**
     * 注入 Application Context。幂等,可多次调用。
     *
     * 在 [CodeExecutionTool] / [SkillExecutor] 第一次执行前调用,
     * 让单例 WebView 能拿到 applicationContext 创建。
     */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Logger.i(TAG, "JsSandbox 已注入 applicationContext")
        }
    }

    /**
     * 执行 JavaScript 代码。
     *
     * @param code JS 代码(可以是表达式、语句块或函数调用;若为语句块,返回最后一个表达式的值)
     * @param timeoutMs 超时毫秒数,默认 10 秒
     * @return [Result] 包裹的 [JsResult];Kotlin 侧异常返回 failure(JS 执行错误封装在 JsResult.error 中)
     */
    suspend fun execute(code: String, timeoutMs: Long = 10000L): Result<JsResult> =
        withContext(Dispatchers.Main) {
            try {
                val webView = ensureWebView()
                // 清空当前日志
                synchronized(logsLock) { currentLogs.clear() }

                // 用 IIFE 包裹代码:让 LLM 可以写语句块;返回值用 JSON.stringify 序列化
                // 注意:LLM 写的代码可能是 "1+2" / "const x = 3; x*2" / "Math.max(1,2)" 等
                // 这里用 (function(){ ... })() 包裹后,内部代码可以用 return 返回值;
                // 但若 LLM 写的是表达式而非 return 语句,IIFE 内部最后表达式不会自动返回。
                // 折中方案:用 eval(code) 求值,表达式直接返回;语句块若没 return 则返回 undefined
                val wrappedCode = buildString {
                    append("(function() {\n")
                    append("    try {\n")
                    append("        var __result__ = eval(")
                    append(quoteJs(code))
                    append(");\n")
                    append("        // JSON.stringify 包装返回值,undefined → null\n")
                    append("        if (__result__ === undefined) {\n")
                    append("            return { ok: true, value: null };\n")
                    append("        }\n")
                    append("        try {\n")
                    append("            return { ok: true, value: JSON.stringify(__result__) };\n")
                    append("        } catch (e) {\n")
                    append("            // 不可序列化(如函数/DOM 节点):降级到 String()\n")
                    append("            return { ok: true, value: String(__result__), stringifyError: e.message };\n")
                    append("        }\n")
                    append("    } catch (e) {\n")
                    append("        return { ok: false, error: e.message || String(e), stack: e.stack || '' };\n")
                    append("    }\n")
                    append("})();\n")
                }

                val raw = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        webView.evaluateJavascript(wrappedCode) { result ->
                            // withTimeout 取消后回调仍可能触发;用 isActive 守卫避免 resume 已取消的 cont
                            if (cont.isActive) {
                                cont.resume(result)
                            }
                        }
                    }
                }

                if (raw == null) {
                    // 超时:JS 仍在 V8 中跑(无法中断),但 Kotlin 侧返回超时错误
                    val logs = synchronized(logsLock) { currentLogs.toList() }
                    return@withContext Result.success(
                        JsResult(
                            value = null,
                            consoleLogs = logs,
                            error = "执行超时(${timeoutMs}ms)",
                        )
                    )
                }

                val logs = synchronized(logsLock) { currentLogs.toList() }
                val (value, error) = parseRawResult(raw)
                Result.success(JsResult(value = value, consoleLogs = logs, error = error))
            } catch (e: Exception) {
                Logger.e(TAG, "JsSandbox execute 异常: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * 销毁 WebView(可在 Application.onTerminate / 测试 tearDown 调用,释放资源)。
     * 必须在主线程调用。
     */
    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Logger.w(TAG, "destroy 必须在主线程调用,已忽略")
            return
        }
        webViewRef?.let { wv ->
            wv.stopLoading()
            wv.removeAllViews()
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webViewRef = null
        synchronized(logsLock) { currentLogs.clear() }
        Logger.i(TAG, "JsSandbox WebView 已销毁")
    }

    // ── 内部实现 ──────────────────────────────────────────────────────

    /**
     * 创建/获取 WebView 单例。必须在主线程调用。
     * 第一次调用会创建 WebView + 注入安全限制 JS。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "JsSandbox WebView 必须在主线程创建和访问"
        }
        webViewRef?.let { return it }
        val ctx = appContext
            ?: error("JsSandbox 未初始化,请先调用 init(context) 注入 Application Context")

        val wv = WebView(ctx).apply {
            // ── WebSettings:开启 JS + 关闭所有危险功能 ──
            settings.javaScriptEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.databaseEnabled = false
            settings.domStorageEnabled = true // localStorage 等(沙盒内可用)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadsImagesAutomatically = false
            settings.blockNetworkLoads = true // 阻止所有网络加载

            // ── WebViewClient:拦截所有 URL 与网络请求 ──
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = true // 阻止任何 URL 加载

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    // 阻止所有网络请求(包括 fetch/XHR/资源加载)
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream("sandbox-blocked".toByteArray()),
                    )
                }
            }

            // ── WebChromeClient:拦截 console.log 收集日志 + 阻止弹窗 ──
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val level = when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "error"
                        ConsoleMessage.MessageLevel.WARNING -> "warn"
                        ConsoleMessage.MessageLevel.DEBUG -> "debug"
                        ConsoleMessage.MessageLevel.TIP -> "tip"
                        ConsoleMessage.MessageLevel.LOG -> "log"
                        else -> "log"
                    }
                    synchronized(logsLock) {
                        currentLogs.add("[$level] ${consoleMessage.message()}")
                    }
                    return true
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult,
                ): Boolean {
                    // 收集 alert 日志并阻止弹窗
                    synchronized(logsLock) {
                        currentLogs.add("[alert] ${message ?: ""}")
                    }
                    result.cancel()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult,
                ): Boolean {
                    synchronized(logsLock) {
                        currentLogs.add("[confirm] ${message ?: ""}")
                    }
                    result.cancel()
                    return true
                }
            }
        }

        // 加载空白页(不加载任何外部 URL)
        wv.loadDataWithBaseURL("about:blank", "<html><body></body></html>", "text/html", "utf-8", null)
        // 注入安全限制 JS:禁用 fetch/XHR/window.open/window.location 写入
        wv.evaluateJavascript(INIT_JS, null)
        webViewRef = wv
        Logger.i(TAG, "WebView 沙盒已初始化")
        return wv
    }

    /**
     * 解析 evaluateJavascript 回调返回的字符串。
     *
     * 回调返回的是 JSON-encoded 字符串,如:
     *  - null / "null" 表示无返回值
     *  - "{\"ok\":true,\"value\":\"42\"}" 表示执行成功
     *  - "{\"ok\":false,\"error\":\"...\"}" 表示执行抛错
     */
    private fun parseRawResult(raw: String?): Pair<Any?, String?> {
        if (raw.isNullOrBlank() || raw == "null") return null to null
        return try {
            val element = AppJson.parseToJsonElement(raw)
            if (element !is JsonObject) {
                // 非 JSON 对象(数字/字符串/布尔):直接返回原值
                return raw to null
            }
            val ok = (element["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
            if (ok) {
                val valueStr = (element["value"] as? JsonPrimitive)?.contentOrNull
                valueStr to null
            } else {
                val err = (element["error"] as? JsonPrimitive)?.contentOrNull
                    ?: "未知错误"
                null to err
            }
        } catch (e: Exception) {
            Logger.w(TAG, "解析 JS 返回值失败: ${e.message}(raw=$raw)")
            raw to null
        }
    }

    /** 把 Kotlin 字符串转为 JS 字符串字面量(用 JSON.stringify 风格)。 */
    private fun quoteJs(s: String): String {
        // 用 JSON-style 转义:简单且安全
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
