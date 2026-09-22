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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * JavaScript 执行沙盒(单例)。
 *
 * Android 无内置 JS 引擎且不能新增大型依赖,
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

    /** 当前执行的插件 id（用于 host.getConfig 查找配置）。 */
    @Volatile private var currentPluginId: String? = null

    /**
     * 设置当前 JS 执行所属的插件 id，用于 host.getConfig(key) 查找配置。
     *
     * 必须在 [execute] 调用前设置，执行结束后自动清空。
     */
    fun setCurrentPluginId(pluginId: String?) {
        currentPluginId = pluginId
    }

    /** 注入的安全初始化 JS:禁用网络 API 与导航 API。 */
    private const val INIT_JS = """
        (function() {
            'use strict';
            var marker = 'muse-js-sandbox-disabled-v1';
            var disabled = function(api) {
                var error = new Error(api + ' is disabled in sandbox');
                error.__museSandboxDisabled = marker;
                throw error;
            };
            // Sentinel 使用不可配置属性和明确标记，验证不依赖 function.name（原生函数 name 可能为空）。
            Object.defineProperty(window, '__museSandboxDisabled', {
                value: marker, writable: false, configurable: false, enumerable: false
            });
            function install(target, name, api) {
                var fn = function() { return disabled(api); };
                Object.defineProperty(fn, '__museSandboxDisabled', {
                    value: marker, writable: false, configurable: false, enumerable: false
                });
                Object.defineProperty(target, name, {
                    value: fn, writable: false, configurable: false, enumerable: false
                });
            }
            install(window, 'fetch', 'fetch');
            install(window, 'XMLHttpRequest', 'XMLHttpRequest');
            install(window, 'WebSocket', 'WebSocket');
            install(navigator, 'sendBeacon', 'sendBeacon');
            try { install(window, 'open', 'window.open'); } catch (e) { /* 属性可能不存在,静默跳过 */ }
            try { window.close = function() {}; } catch (e) { /* 属性可能不存在,静默跳过 */ }
            try { document.write = function() {}; } catch (e) { /* 属性可能不存在,静默跳过 */ }
            try { document.writeln = function() {}; } catch (e) { /* 属性可能不存在,静默跳过 */ }
            // B7-01: host 对象 — 插件可通过 host.getConfig(key) 读取宿主提供的配置值
            // getConfig(key) 返回字符串化后的配置值（与 manifest 中 defaultVal 类型对应）
            // 若插件未声明该 key 或值不存在，返回 null
            if (typeof host === 'undefined') {
                window.host = {
                    getConfig: function(key) {
                        // 由 Kotlin 侧在每次 execute 前注入实际配置值
                        var v = window.__musePluginConfig && window.__musePluginConfig[key];
                        return v !== undefined ? JSON.stringify(v) : null;
                    },
                    getPluginId: function() {
                        return window.__musePluginId || null;
                    }
                };
            }
        })();
    """

    /** H-SEC-4: 验证必须检查不可伪造的 sentinel 及实际抛错行为。 */
    private const val VERIFY_JS = """
        (function() {
            var issues = [], marker = 'muse-js-sandbox-disabled-v1';
            function check(name, fn, invoke) {
                if (typeof fn !== 'function' || fn.__museSandboxDisabled !== marker) {
                    issues.push(name + ' not hijacked'); return;
                }
                try { invoke(); issues.push(name + ' did not throw'); }
                catch (e) { if (!e || e.__museSandboxDisabled !== marker) issues.push(name + ' behavior not disabled'); }
            }
            check('fetch', window.fetch, function() { window.fetch('about:blank'); });
            check('XHR', window.XMLHttpRequest, function() { new window.XMLHttpRequest(); });
            check('WS', window.WebSocket, function() { new window.WebSocket('wss://x.x'); });
            check('sendBeacon', navigator && navigator.sendBeacon, function() { navigator.sendBeacon('about:blank', 'x'); });
            JSON.stringify({ verified: issues.length === 0, issues: issues });
        })();
    """

    @Volatile private var webViewRef: WebView? = null
    @Volatile private var appContext: Context? = null

    /** R-SVC-04: 连续超时熔断阈值。 */
    private const val MAX_CONSECUTIVE_TIMEOUTS = 2
    /** R-SVC-04: 熔断后自动恢复冷却时间。 */
    private const val CIRCUIT_COOLDOWN_MS = 60_000L
    /** R-SVC-04: 累计超时配额(进程内),超过后同样熔断。 */
    private const val MAX_TOTAL_TIMED_OUT_MS = 60_000L

    /**
     * C-30: 熔断状态按 scopeKey(插件 id)维度隔离,避免一个插件死循环熔断连坐全部 JS 工具。
     * key == null 代表内置/非插件工具(保留原有全局语义)。
     * 改用 [ConcurrentHashMap] 支持并发读写(每个周期的状态更新在同一主线程 mutex 内串行,
     * 但读取 [isCircuitBroken] 可能来自其他线程)。
     */
    private class CircuitState(
        @Volatile var consecutiveTimeouts: Int = 0,
        @Volatile var totalTimedOutMs: Long = 0L,
        @Volatile var circuitBrokenUntil: Long = 0L,
    )

    private val circuitStates = ConcurrentHashMap<String?, CircuitState>()

    /** 获取指定 scopeKey(插件 id)的熔断状态;null scope 表示内置工具,keyed 惰性创建。 */
    private fun stateFor(scopeKey: String?): CircuitState = circuitStates.getOrPut(scopeKey) { CircuitState() }

    /** 日志缓冲区:每次 execute 前清空。synchronized 保护多线程访问。 */
    private val logsLock = Any()
    private val currentLogs = mutableListOf<String>()

    /** 审计修复 (4.4): execute 互斥锁 — 串行化 WebView JS 执行。 */
    private val executionMutex = Mutex()

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
     * @param scopeKey C-30: 执行归属的插件 id(插件工具传 pluginId;内置工具不传使用 null 全局 scope)。
     *   熔断状态按此隔离,一个插件的死循环超时只熔断自身,不会连坐其他插件/内置工具。
     * @param pluginConfigJson B7-01: 插件配置 JSON 字符串,用于注入 host.getConfig() 桥接。null 时跳过注入。
     * @return [Result] 包裹的 [JsResult];Kotlin 侧异常返回 failure(JS 执行错误封装在 JsResult.error 中)
     */
    suspend fun execute(code: String, timeoutMs: Long = 10000L, scopeKey: String? = null, pluginConfigJson: String? = null): Result<JsResult> =
        withContext(Dispatchers.Main) {
            // 审计修复 (4.4): execute 加互斥 — 原实现无并发控制,多个工具同时 execute
            // 会并发进入 WebView JS 执行,console 日志缓冲区互相污染、超时销毁与回调交错;
            // 用 Mutex 串行化,同一时刻只允许一个 JS 执行。锁内等待是挂起而非阻塞主线程。
            executionMutex.withLock {
            try {
                // C-30: 熔断状态按 scopeKey 隔离读取
                val state = stateFor(scopeKey)
                if (System.currentTimeMillis() < state.circuitBrokenUntil) {
                    return@withContext Result.failure(
                        IllegalStateException("JS 沙盒已熔断，请稍后重试(scope=${scopeKey ?: "default"})"),
                    )
                }
                val webView = ensureWebView()
                // C-30: 每次执行前清理 localStorage / 缓存 / 表单数据,防止跨插件(以及跨执行)残留
                //   泄漏到下一次执行。WebView 是全局共享单例,无法按插件隔离实例;
                //   用"执行前清空数据"保证插件 A 写入的 localStorage 不会被插件 B 读到。
                clearPerExecutionData(webView)
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
                        // B7-01: 在执行前注入当前插件的配置到 window.__musePluginConfig
                        if (pluginConfigJson != null) {
                            // pluginConfigJson 已经是安全的 JSON 字符串，直接注入
                            webView.evaluateJavascript("window.__musePluginConfig = $pluginConfigJson;") { }
                        }
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
                    // R-SVC-04 + C-30: 超时后销毁 WebView 真正终止 JS,并累计熔断状态。
                    //   熔断计数按 scopeKey 隔离,避免一个插件死循环连坐其他 JS 工具。
                    state.consecutiveTimeouts++
                    state.totalTimedOutMs += timeoutMs
                    if (state.consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS || state.totalTimedOutMs >= MAX_TOTAL_TIMED_OUT_MS) {
                        state.circuitBrokenUntil = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS
                        Logger.e(TAG, "JS 沙盒超时熔断(scope=${scopeKey ?: "default"}): consecutive=${state.consecutiveTimeouts} total=${state.totalTimedOutMs}")
                    }
                    destroy()
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
                state.consecutiveTimeouts = 0
                // B-16a: totalTimedOutMs 原单调不减,熔断冷却结束后累计量仍逼近上限,
                //   导致任意一次新超时就立即再次熔断(直至手动复位才恢复)。成功执行时
                //   按固定 window 衰减(减半),熔断后允许逐步恢复;不彻底清零以保留对
                //   连续超时的保守记忆(fixed-window 衰减的保守实现)。
                state.totalTimedOutMs /= 2
                Result.success(JsResult(value = value, consoleLogs = logs, error = error))
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // B-16a: 协程取消信号必须向上传播,不能被 catch(Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "JsSandbox execute 异常: ${e.message}", e)
                Result.failure(e)
            }
            }
        }

    /**
     * R-SVC-04: 当前是否处于熔断期(内置/默认 scope,null key)。
     * C-30: 保留对内置工具的全局语义;插件请使用 [isCircuitBrokenFor] 按 id 判断。
     */
    val isCircuitBroken: Boolean get() = isCircuitBrokenFor(null)

    /** C-30: 指定插件/scope 是否处于熔断期。 */
    fun isCircuitBrokenFor(scopeKey: String?): Boolean {
        val state = circuitStates[scopeKey] ?: return false
        return System.currentTimeMillis() < state.circuitBrokenUntil
    }

    /**
     * R-SVC-04: 手动复位熔断(用户重试/插件禁用后可调用)。
     * C-30: 默认复位内置 scope;插件复位传对应 pluginId。
     */
    fun resetCircuitBreaker(scopeKey: String? = null) {
        val state = stateFor(scopeKey)
        state.circuitBrokenUntil = 0L
        state.consecutiveTimeouts = 0
        state.totalTimedOutMs = 0L
        Logger.i(TAG, "JsSandbox 熔断已复位(scope=${scopeKey ?: "default"})")
    }

    /**
     * C-30: 每次执行前清理沙箱 WebView 的持久数据,防止跨插件残留。必须在主线程调用
     * (execute 在 Main 线程串行执行,安全)。
     *
     * P2-20: 不再调用 WebStorage.deleteAllData() — 沙箱只加载 about:blank(opaque
     * origin,自带 localStorage 不可用),而 WebStorage 是应用级全局存储,删除会同时
     * 清掉 BrowserManager/浏览器已登录站点的 localStorage(登录态丢失)。
     */
    private fun clearPerExecutionData(webView: WebView) {
        runCatching {
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
        }.onFailure { e ->
            // 清理失败不影响 JS 执行本身(沙盒权限仍有限制),记录日志便于排查
            Logger.w(TAG, "JsSandbox 每次执行数据清理失败: ${e.message}")
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
    private suspend fun ensureWebView(): WebView {
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
        // evaluateJavascript 是异步的；必须等 INIT_JS 回调完成后再验证，避免竞态。
        suspendCancellableCoroutine<Unit> { cont ->
            wv.evaluateJavascript(INIT_JS) { if (cont.isActive) cont.resume(Unit) }
        }
        val raw = suspendCancellableCoroutine<String?> { cont ->
            wv.evaluateJavascript(VERIFY_JS) { result -> if (cont.isActive) cont.resume(result) }
        }
        val verifiedResult = runCatching {
            val first = AppJson.parseToJsonElement(raw ?: "null")
            val elem = if (first is JsonPrimitive && first.isString) {
                AppJson.parseToJsonElement(first.content)
            } else first
            val obj = elem as? JsonObject ?: error("invalid verification result")
            val verified = (obj["verified"] as? JsonPrimitive)?.booleanOrNull == true
            val issues = (obj["issues"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.isNotBlank() } ?: emptyList()
            verified to issues
        }.getOrElse { false to listOf("verification parse failed: ${it.message}") }
        if (!verifiedResult.first) {
            Logger.w(TAG, "JsSandbox 劫持验证失败: ${verifiedResult.second.joinToString(", ")}")
            wv.destroy()
            throw IllegalStateException("JsSandbox security verification failed")
        }
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
