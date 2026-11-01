package io.zer0.muse.tools

import android.annotation.SuppressLint
import android.content.Context
import io.zer0.muse.data.plugin.PluginCapabilityLedgers
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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
 *  - 安全边界(v2.x 已放开网络):JS 可直接用 fetch/XHR/WebSocket;
 *    仅拦截**页面导航**(window.open)与本地 file://content:// 读取;
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

    /** 联网能力名（与插件清单、能力总线同一词汇）。 */
    private const val CAPABILITY_NETWORK = "network"

    /**
     * 每个 scope（插件 id / 内置默认）一个沙盒实例。
     *
     * 为什么必须分实例（P0-4，解 B-10）：旧实现是**应用级共享单个 WebView + 一把全局锁**，
     * 于是插件 A 死循环时 A 的超时把 B 一起堵在锁外，超时销毁还会连带清掉 A 的状态影响 B
     * —— "一个插件卡住全体"就是这么来的。V8 中断不了脚本，那就**销毁整个实例**：只杀 A。
     *
     * 身份用一次性 token：token → 实例 的映射只写在 Kotlin 侧，脚本伪造不了别人的身份，
     * 也不再需要调用方记得设置全局变量（旧代码里的 `currentPluginId` 从没被赋值过）。
     */
    private class Instance(val scopeKey: String) {
        @Volatile var webView: WebView? = null
        @Volatile var lastUsedMs: Long = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        val logsLock = Any()
        val mutex = Mutex()
        val deliver = java.util.concurrent.atomic.AtomicReference<((String) -> Unit)?>(null)

        /**
         * 追加一条日志，并按**行数 + 字节**双上限裁剪。
         *
         * 没有这一步，一个 `console.log` 刷屏的插件就能在一次执行里把日志列表撑到几百 MB
         * —— 预算项（结果 4KB 由 ToolOrchestrator 截，日志这一路此前没人管）。
         */
        fun appendLog(line: String) {
            synchronized(logsLock) {
                logs.add(line.take(MAX_LOG_LINE_CHARS))
                while (logs.size > MAX_LOG_LINES) logs.removeAt(0)
                var bytes = logs.sumOf { it.length }
                while (bytes > MAX_LOG_TOTAL_CHARS && logs.size > 1) {
                    bytes -= logs.removeAt(0).length
                }
            }
        }

        fun snapshotLogs(): List<String> = synchronized(logsLock) { logs.toList() }

        fun clearLogs() {
            synchronized(logsLock) { logs.clear() }
        }
    }

    private val instances = ConcurrentHashMap<String, Instance>()

    /** token → 实例。只存在于执行期间，结束即摘除（迟到的回调因此打不进别人的会话）。 */
    private val tokenIndex = ConcurrentHashMap<String, Instance>()

    /**
     * 沙盒环境不可用。
     *
     * 属于本机环境问题（WebView 未就绪、自检未通过），与调用方传入的代码无关。
     * 工具层据此给出“不要重试、换条路”的提示，而不是把内部实现名甩给模型。
     */
    class JsSandboxUnavailableException(message: String) : IllegalStateException(message)

    /**
     * 构造每次执行前的宿主配置注入。
     *
     * WebView 全局对象会跨 execute() 保留；即使 localStorage/缓存被清理，
     * 不覆盖这些变量也会让下一个插件读取上一个插件的配置。
     */
    internal fun buildHostInjectionScript(
        scopeKey: String?,
        pluginConfigJson: String?,
        executionToken: String? = null,
    ): String {
        val config = pluginConfigJson ?: "null"
        val pluginId = scopeKey?.let(::quoteJs) ?: "null"
        val token = executionToken?.let(::quoteJs) ?: "null"
        return "window.__musePluginConfig = $config; window.__musePluginId = $pluginId; " +
            "window.__museExecToken = $token;"
    }

    /**
     * 注入的沙盒初始化 JS。
     *
     * v2.x（沙盒放开）:JS 可直接使用 fetch / XMLHttpRequest / WebSocket —— 不再桩掉。
     * 之前网络被完全禁用（桩函数 + shouldInterceptRequest 阻断 + blockNetworkLoads），
     * 使插件/JS 技能只能靠 `__bridge__` 逐项报批访问网络，过于保守。
     * 现改为：网络能力直接可用，但**保留两类底线**：
     *  - 页面导航（window.open / location 写入）仍禁 —— 沙盒不该能跳页/弹窗；
     *  - 请求仍经 [shouldInterceptRequest] 与 [WebSettings] 的 SSRF/本地文件防护。
     */
    private const val INIT_JS = """
        (function() {
            'use strict';
            var marker = 'muse-js-sandbox-open-v2';
            // v2.x: fetch / XMLHttpRequest / WebSocket / sendBeacon 不再拦截，直接可用。
            // 仅保留导航防护：禁止弹窗与页面跳转（沙盒不应能控制宿主浏览行为）。
            try { window.open = function() { return null; }; } catch (e) { /* 属性可能不存在,静默跳过 */ }
            try { window.close = function() {}; } catch (e) { /* 属性可能不存在,静默跳过 */ }
            try { document.write = function() {}; } catch (e) { /* 属性可能不存在,静默跳过 */ }
            try { document.writeln = function() {}; } catch (e) { /* 属性可能不存在,静默跳过 */ }
            Object.defineProperty(window, '__museSandboxDisabled', {
                value: marker, writable: false, configurable: false, enumerable: false
            });
            // ── 网络垫片 ──
            // 沙盒页面是 about:blank，origin=null：直接 fetch 会被同源策略拦成 CORS 错误。
            // 这里把 fetch / XMLHttpRequest 改走 __museHttp（Kotlin 侧 OkHttp），彻底绕开 CORS。
            function __museDoHttp(method, url, headers, body) {
                var payload = {
                    method: method, url: url, headers: headers || {}, body: body,
                    token: window.__museExecToken || null
                };
                var raw = __museHttp.httpRequest(JSON.stringify(payload));
                try { return JSON.parse(raw); } catch (e) { return { error: 'bad response: ' + raw }; }
            }
            window.fetch = function(input, init) {
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                var opts = init || {};
                var headers = {};
                if (opts.headers) {
                    if (typeof opts.headers.forEach === 'function' && !Array.isArray(opts.headers)) {
                        opts.headers.forEach(function(v, k) { headers[k] = v; });
                    } else if (Array.isArray(opts.headers)) {
                        opts.headers.forEach(function(pair) { headers[pair[0]] = pair[1]; });
                    } else {
                        for (var k in opts.headers) { headers[k] = opts.headers[k]; }
                    }
                }
                var res = __museDoHttp((opts.method || 'GET').toUpperCase(), url, headers,
                    opts.body == null ? null : String(opts.body));
                if (res.error) return Promise.reject(new TypeError(res.error));
                return Promise.resolve({
                    ok: res.status >= 200 && res.status < 300,
                    status: res.status,
                    statusText: res.statusText || '',
                    url: url,
                    headers: { get: function(n) { return (res.headers || {})[String(n).toLowerCase()] || null; } },
                    text: function() { return Promise.resolve(res.body || ''); },
                    json: function() { return Promise.resolve(JSON.parse(res.body || 'null')); }
                });
            };
            function MuseXHR() {
                this.readyState = 0; this.status = 0; this.statusText = '';
                this.responseText = ''; this.response = ''; this.onload = null;
                this.onerror = null; this.onreadystatechange = null; this._headers = {};
            }
            MuseXHR.prototype.open = function(method, url) {
                this._method = (method || 'GET').toUpperCase(); this._url = url; this.readyState = 1;
                if (this.onreadystatechange) this.onreadystatechange();
            };
            MuseXHR.prototype.setRequestHeader = function(k, v) { this._headers[k] = v; };
            MuseXHR.prototype.getAllResponseHeaders = function() {
                var out = ''; for (var k in (this._resHeaders || {})) out += k + ': ' + this._resHeaders[k] + '\r\n';
                return out;
            };
            MuseXHR.prototype.send = function(body) {
                var self = this;
                setTimeout(function() {
                    var res = __museDoHttp(self._method, self._url, self._headers,
                        body == null ? null : String(body));
                    self.readyState = 4;
                    if (res.error) {
                        self.status = 0;
                        if (self.onreadystatechange) self.onreadystatechange();
                        if (self.onerror) self.onerror(new Error(res.error));
                        return;
                    }
                    self.status = res.status;
                    self.statusText = res.statusText || '';
                    self._resHeaders = res.headers || {};
                    self.responseText = res.body || '';
                    self.response = self.responseText;
                    if (self.onreadystatechange) self.onreadystatechange();
                    if (self.onload) self.onload();
                }, 0);
            };
            window.XMLHttpRequest = MuseXHR;
            // B7-01: host 对象 — 插件可通过 host.getConfig(key) 读取宿主提供的配置值
            if (typeof host === 'undefined') {
                window.host = {
                    getConfig: function(key) {
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

    /**
     * v2.x（沙盒放开）:验证仅检查导航防护生效；网络 API 已放开，不再要求 "必抛错"。
     * 覆盖两类：window.open 被覆盖 + document.write 被禁用。
     */
    internal const val VERIFY_JS = """
        (function() {
            var issues = [];
            if (typeof window.open !== 'function') { issues.push('window.open missing'); }
            else { try { var r = window.open('about:blank'); if (r !== null) issues.push('window.open not blocked'); } catch (e) { /* 抛错也视为已阻断 */ } }
            // 必须显式 return：evaluateJavascript 取的是整个表达式的值，
            // 直接写 JSON.stringify(...) 作为语句时函数返回 undefined，回调收到 "null"，
            // 会被当成“自检没返回结果”。
            return JSON.stringify({ verified: issues.length === 0, issues: issues });
        })();
    """



    /** JS 侧「等我异步回传」的哨兵值。 */
    private const val ASYNC_SENTINEL = "__MUSE_ASYNC__"

    /** 沙盒 HTTP 单次超时（毫秒）。 */
    private const val HTTP_TIMEOUT_MS = 15_000L

    /** 沙盒 HTTP 响应体上限：2 MB，超出即截断并标记。 */
    private const val MAX_HTTP_BYTES = 2 * 1024 * 1024

    /** 供 JS 调用的异步回传入口（注册为 `__museAsync`）。 */
    @android.webkit.JavascriptInterface
    /**
     * 异步结果回传（`__museAsync`）。带 token 是因为接口方法本身不知道是哪个 WebView 调的：
     * 多实例并发时没有 token 就会把 A 的结果投给 B。
     */
    fun deliverAsyncResult(token: String, payload: String) {
        tokenIndex[token]?.deliver?.getAndSet(null)?.invoke(payload)
    }

    /**
     * 判定这次网络请求能不能放行；返回非 null 即拒绝原因（会原样回给脚本）。
     *
     * 两道检查都不该省：
     *  - **凭证**：token 由宿主在每次执行前注入，脚本无法伪造别人的身份；缺 token 说明调用方
     *    绕过了 fetch/XHR 垫片（直接调 `__museHttp`），不能让它逃过授权；
     *  - **授权**：只有插件执行才查账本，且以「上次批准的声明集合」为准 —— 改包内清单不会自动扩权。
     */
    private fun networkGateDenialReason(tokenValue: JsonPrimitive?): String? {
        val token = tokenValue?.contentOrNull
        val instance = token?.let { tokenIndex[it] }
        return when {
            // 凭证缺失/失效：脚本绕过了 fetch/XHR 垫片，或这次执行已经结束 —— 一律按未授权处理
            instance == null ->
                "缺少有效的宿主调用凭证：请使用 fetch / XMLHttpRequest，不要直接调用 __museHttp"
            // 内置默认 scope（用户显式的 JS 工具）不受插件能力门限制
            instance.scopeKey == DEFAULT_SCOPE_KEY -> null
            else -> {
                val pluginId = instance.scopeKey
                when (PluginCapabilityLedgers.sharedFromHolderOrNull()?.isApproved(pluginId, CAPABILITY_NETWORK)) {
                    null -> "插件权限账本未就绪，暂时不能联网（请稍后重试）"
                    false -> "未授权 network 能力：插件清单需声明 \"$CAPABILITY_NETWORK\"，" +
                        "并在 设置 → 工具与扩展 → 插件 → 权限 中批准"
                    true -> null
                }
            }
        }
    }

    /** 统一给脚本的错误响应形状（与 fetch 的 `{error}` 约定一致，转义交给 JsonObject）。 */
    private fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

    /**
     * 供 JS 调用的 HTTP 入口（注册为 `__museHttp`）：**由 Kotlin 侧 OkHttp 真正发请求**。
     *
     * 为什么需要它：沙盒页面是 `about:blank`，origin 是 `null`，浏览器同源策略会把
     * 绝大多数跨站请求直接拦成 CORS 错误 —— 工具描述里承诺的 fetch/XHR 实际上用不了。
     * 走原生请求就没有 CORS 这回事。
     *
     * 边界（有意保留）：只允许 http/https；不带任何 cookie/凭据；响应体上限 [MAX_HTTP_BYTES]；
     * 超时 [HTTP_TIMEOUT_MS]。@JavascriptInterface 方法运行在 WebView 的 JavaBridge 线程，
     * 阻塞在这里不会卡主线程。
     */
    @android.webkit.JavascriptInterface
    fun httpRequest(requestJson: String): String {
        val request = runCatching { AppJson.parseToJsonElement(requestJson) as JsonObject }
            .getOrNull() ?: return """{"error":"bad request json"}"""
        val url = (request["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "GET"
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            return """{"error":"only http/https allowed"}"""
        }
        val bodyText = (request["body"] as? JsonPrimitive)?.contentOrNull
        // 审计：沙盒能发任意 HTTP，就得留下「发了什么」的痕迹（host + 方法 + 体量）。
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
        // 授权门：`fetch / XMLHttpRequest` 是插件唯一的网络出口（JS 拿不到原始 socket）。
        // 非插件执行（scope 为 null，如 execute_javascript 这类用户显式工具）不受此门限制。
        networkGateDenialReason(request["token"] as? JsonPrimitive)?.let { denial ->
            Logger.w(TAG, "沙盒网络被拒 | host=$host | $denial")
            return errorJson(denial)
        }
        Logger.i(TAG, "沙盒外发请求 | method=$method | host=$host | bodyBytes=${bodyText?.length ?: 0}")
        return try {
            val builder = okhttp3.Request.Builder().url(url).method(method, null)
            (request["headers"] as? JsonObject)?.forEach { (name, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { builder.header(name, it) }
            }
            if (bodyText != null && method != "GET" && method != "HEAD") {
                val mediaType = "application/octet-stream".toMediaTypeOrNull()
                builder.method(method, okhttp3.RequestBody.create(mediaType, bodyText))
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val truncated = bytes.size > MAX_HTTP_BYTES
                // 审计（收尾）：记下状态码与体积，脚本侧拿到的就是这份。
                Logger.i(TAG, "沙盒外发完成 | host=$host | status=${response.code} | bytes=${bytes.size}")
                val text = String(
                    if (truncated) bytes.copyOf(MAX_HTTP_BYTES) else bytes,
                    Charsets.UTF_8,
                )
                val headers = buildJsonObject {
                    response.headers.forEach { (name, value) ->
                        put(name.lowercase(), JsonPrimitive(value))
                    }
                }
                buildJsonObject {
                    put("status", JsonPrimitive(response.code))
                    put("statusText", JsonPrimitive(response.message))
                    put("headers", headers)
                    put("body", JsonPrimitive(text))
                    put("truncated", JsonPrimitive(truncated))
                }.toString()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "沙盒 HTTP 请求失败: ${e.message}")
            buildJsonObject {
                put("error", JsonPrimitive(e.message ?: e::class.java.simpleName))
            }.toString()
        }
    }

    /** 沙盒网络：共享客户端（不带 cookie jar，避免脚本拿到宿主会话）。 */
    private val httpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(HTTP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

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

    /**
     * [ConcurrentHashMap] 不允许 null key：内置工具走 [execute] 时 scopeKey 为 null，
     * 若直接以 null 作 key 会在 get 时抛 NPE（"Attempt to invoke virtual method
     * 'int java.lang.Object.hashCode()' on a null object reference"）——
     * 所有内置 execute_javascript 调用因此 100% 失败（用户实测）。
     * 用固定哨兵串代替 null key，对外语义不变（null 仍代表内置/默认 scope）。
     */
    private const val DEFAULT_SCOPE_KEY = "\u0000builtin-default"

    /** 获取指定 scopeKey(插件 id)的熔断状态;null scope 表示内置工具,keyed 惰性创建。 */
    private fun stateFor(scopeKey: String?): CircuitState = circuitStates.getOrPut(scopeKey ?: DEFAULT_SCOPE_KEY) { CircuitState() }

    /** 单次执行的日志预算：行数与总字符数双限 —— 64KB 够调试，又不至于让刷屏插件吃内存。 */
    private const val MAX_LOG_LINES = 400
    private const val MAX_LOG_LINE_CHARS = 2_000
    private const val MAX_LOG_TOTAL_CHARS = 64 * 1024

    /**
     * 实例上限。再多就按最久未用回收 —— 一个 WebView 是十几 MB 级别的开销，
     * 而同时活跃的插件通常个位数；上限存在的目的是让"装了几十个插件"不会吃满内存。
     */
    private const val MAX_INSTANCES = 4

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
    suspend fun execute(
        code: String,
        timeoutMs: Long = 10000L,
        scopeKey: String? = null,
        pluginConfigJson: String? = null,
    ): Result<JsResult> = withContext(Dispatchers.Main) {
        // 审计修复 (4.4): execute 加互斥 — 原实现无并发控制,多个工具同时 execute
        // 会并发进入 WebView JS 执行,console 日志缓冲区互相污染、超时销毁与回调交错;
        // 用 Mutex 串行化,同一时刻只允许一个 JS 执行。锁内等待是挂起而非阻塞主线程。
        val instance = instanceFor(scopeKey)
        instance.mutex.withLock {
            // token 在锁内颁发：同一实例一次只有一个执行，映射不会串台。
            val token = java.util.UUID.randomUUID().toString()
            tokenIndex[token] = instance
            instance.lastUsedMs = System.currentTimeMillis()
            try {
                // C-30: 熔断状态按 scopeKey 隔离读取
                val state = stateFor(scopeKey)
                if (System.currentTimeMillis() < state.circuitBrokenUntil) {
                    return@withContext Result.failure(
                        IllegalStateException("JS 沙盒已熔断，请稍后重试(scope=${scopeKey ?: "default"})"),
                    )
                }
                val webView = ensureWebView(instance)
                // C-30: 每次执行前清理 localStorage / 缓存 / 表单数据,防止跨插件(以及跨执行)残留
                //   泄漏到下一次执行。WebView 是全局共享单例,无法按插件隔离实例;
                //   用"执行前清空数据"保证插件 A 写入的 localStorage 不会被插件 B 读到。
                clearPerExecutionData(webView)
                // 清空当前日志
                instance.clearLogs()

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
                    append("        // 脚本返回 Promise 时必须等它 settle：evaluateJavascript 只会拿到 {},\n")
                    append("        // 成功和失败都变成空对象 —— 那是静默失败，比报错更难查。\n")
                    append("        if (__result__ && typeof __result__.then === 'function') {\n")
                    append("            try {\n")
                    append("                __result__.then(\n")
                    append("                    function(v){ __museAsync.deliverAsyncResult(window.__museExecToken,JSON.stringify({\n")
                    append("                        ok: true,\n")
                    append("                        value: v === undefined ? null : (function(){\n")
                    append("                            try { return JSON.stringify(v); } catch (e) { return String(v); }\n")
                    append("                        })()\n")
                    append("                    })); },\n")
                    append("                    function(e){ __museAsync.deliverAsyncResult(window.__museExecToken,JSON.stringify({\n")
                    append("                        ok: false,\n")
                    append("                        error: (e && e.message) || String(e)\n")
                    append("                    })); }\n")
                    append("                );\n")
                    append("            } catch (e) {\n")
                    append("                __museAsync.deliverAsyncResult(window.__museExecToken,JSON.stringify({ ok: false, ")
                    append("error: 'await failed: ' + ((e && e.message) || String(e)) }));\n")
                    append("            }\n")
                    append("            return '")
                    append(ASYNC_SENTINEL)
                    append("';\n")
                    append("        }\n")
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
                        // pluginConfigJson 已经是安全的 JSON 字符串；即使为空也必须显式
                        // 覆盖旧值，避免共享 WebView 的全局状态跨插件泄漏。
                        val done = java.util.concurrent.atomic.AtomicBoolean(false)
                        fun finish(value: String?) {
                            if (done.compareAndSet(false, true) && cont.isActive) cont.resume(value)
                        }
                        val callback: (String) -> Unit = { payload -> finish(payload) }
                        instance.deliver.set(callback)
                        cont.invokeOnCancellation { instance.deliver.set(null) }
                        // INIT_JS 必须在**每次执行前**重新注入：WebView 只加载一次 about:blank，
                        // 但 loadDataWithBaseURL 会换掉整个 document，window 上的 fetch/XHR 垫片
                        // 与 host 对象会被一起清掉 —— 只在建 WebView 时注入一次等于没注入。
                        val pluginScope = instance.scopeKey.takeIf { it != DEFAULT_SCOPE_KEY }
                        val injection = buildHostInjectionScript(pluginScope, pluginConfigJson, token)
                        webView.evaluateJavascript(INIT_JS + "\n" + injection) { }
                        webView.evaluateJavascript(wrappedCode) { result ->
                            // 异步脚本：包装脚本返回哨兵，真实结果稍后由 __museAsync 回传，别在这里结束。
                            if (result != null && result.contains(ASYNC_SENTINEL)) return@evaluateJavascript
                            // withTimeout 取消后回调仍可能触发;用 isActive 守卫避免 resume 已取消的 cont
                            finish(result)
                        }
                    }
                }

                if (raw == null) {
                    // 超时:JS 仍在 V8 中跑(无法中断),但 Kotlin 侧返回超时错误
                    val logs = instance.snapshotLogs()
                    // R-SVC-04 + C-30: 超时后销毁 WebView 真正终止 JS,并累计熔断状态。
                    //   熔断计数按 scopeKey 隔离,避免一个插件死循环连坐其他 JS 工具。
                    state.consecutiveTimeouts++
                    state.totalTimedOutMs += timeoutMs
                    if (state.consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS || state.totalTimedOutMs >= MAX_TOTAL_TIMED_OUT_MS) {
                        state.circuitBrokenUntil = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS
                        Logger.e(
                            TAG,
                            "JS 沙盒超时熔断(scope=${scopeKey ?: "default"}): " +
                                "consecutive=${state.consecutiveTimeouts} total=${state.totalTimedOutMs}",
                        )
                    }
                    destroyInstance(instance)
                    return@withContext Result.success(
                        JsResult(
                            value = null,
                            consoleLogs = logs,
                            error = "执行超时(${timeoutMs}ms)",
                        ),
                    )
                }

                val logs = instance.snapshotLogs()
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
            } finally {
                // 摘 token：销毁实例后仍可能迟到的回调，既投不进结果也发不出请求。
                tokenIndex.remove(token)
                instance.deliver.set(null)
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
        val state = circuitStates[scopeKey ?: DEFAULT_SCOPE_KEY] ?: return false
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
     * 取该 scope 的沙盒实例；超过上限时按最久未用回收一个**空闲**实例。
     *
     * 回收只挑没在跑的实例：正在等结果的 WebView 一销毁，那次执行就只能靠超时收尾，
     * 白白让调用方多等 10s。
     */
    private fun instanceFor(scopeKey: String?): Instance {
        if (instances.size >= MAX_INSTANCES) evictIdleInstance()
        return instances.getOrPut(scopeKey ?: DEFAULT_SCOPE_KEY) { Instance(scopeKey ?: DEFAULT_SCOPE_KEY) }
    }

    private fun evictIdleInstance() {
        val victim = instances.values
            .filter { it.webView == null || !it.mutex.isLocked }
            .minByOrNull { it.lastUsedMs } ?: return
        instances.remove(victim.scopeKey)
        destroyInstance(victim)
        Logger.i(TAG, "沙盒实例已回收(scope=${victim.scopeKey.takeUnless { it == DEFAULT_SCOPE_KEY } ?: "default"})")
    }

    /** 只销毁这一个 scope 的实例 —— 这正是"不连坐"的关键。 */
    private fun destroyInstance(instance: Instance) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Logger.w(TAG, "destroyInstance 必须在主线程调用,已忽略")
            return
        }
        instance.webView?.let { wv ->
            wv.stopLoading()
            wv.removeAllViews()
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            runCatching { wv.destroy() }
                .onFailure { Logger.w(TAG, "WebView 销毁失败: ${it.message}") }
        }
        instance.webView = null
        instance.clearLogs()
    }

    /**
     * 销毁全部实例(可在 Application.onTerminate / 测试 tearDown 调用,释放资源)。
     * 必须在主线程调用。
     */
    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Logger.w(TAG, "destroy 必须在主线程调用,已忽略")
            return
        }
        instances.values.toList().forEach { destroyInstance(it) }
        instances.clear()
        tokenIndex.clear()
        Logger.i(TAG, "JsSandbox 全部实例已销毁")
    }

    // ── 内部实现 ──────────────────────────────────────────────────────

    /**
     * 创建/获取该 scope 自己的 WebView。必须在主线程调用。
     *
     * 每个实例都是独立的 WebView：脚本死循环时销毁它不会波及别的插件。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(instance: Instance): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "JsSandbox WebView 必须在主线程创建和访问"
        }
        instance.webView?.let { instance.lastUsedMs = System.currentTimeMillis(); return it }
        val ctx = appContext
            ?: error("JsSandbox 未初始化,请先调用 init(context) 注入 Application Context")

        val wv = WebView(ctx).apply {
            // 异步返回值通道（脚本 return Promise 时用）。只暴露一个回传方法，不提供任何读取能力。
            @Suppress("AddJavascriptInterface")
            addJavascriptInterface(JsSandbox, "__museAsync")
            // 网络垫片后端：Kotlin 侧发请求，绕开沙盒的 opaque origin（CORS）。
            addJavascriptInterface(JsSandbox, "__museHttp")
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
            // v2.x（沙盒放开）：不再阻止网络加载 —— fetch/XHR/WebSocket 直接可用。
            // 本地文件访问仍关闭（allowFileAccess=false / allowContentAccess=false），
            // 防 file:// / content:// 跨域读取。
            settings.blockNetworkLoads = false

            // ── WebViewClient:仅拦页面导航;网络请求放行（保留底线） ──
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true // 阻止任何页面跳转

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // v2.x（沙盒放开）：不再无脑阻断。仅保留 file:// / content:// 隔离（防本地文件读取）;
                    // http/https/wss 等正常放行。
                    val scheme = request?.url?.scheme?.lowercase()
                    return if (scheme == "file" || scheme == "content") {
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("sandbox-local-blocked".toByteArray()))
                    } else {
                        null // 交给 WebView 正常处理
                    }
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
                    instance.appendLog("[$level] ${consoleMessage.message()}")
                    return true
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                    // 收集 alert 日志并阻止弹窗
                    instance.appendLog("[alert] ${message ?: ""}")
                    result.cancel()
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                    instance.appendLog("[confirm] ${message ?: ""}")
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
        val verifiedResult = parseVerificationResult(raw)
        if (!verifiedResult.first) {
            Logger.w(TAG, "JsSandbox 劫持验证失败: ${verifiedResult.second.joinToString(", ")}")
            wv.destroy()
            throw JsSandboxUnavailableException("沙盒环境自检未通过: ${verifiedResult.second.joinToString(", ").ifBlank { "未知原因" }}")
        }
        instance.webView = wv
        Logger.i(TAG, "WebView 沙盒已初始化")
        return wv
    }

    /**
     * 解析自检返回值。
     *
     * [evaluateJavascript] 回调给的是 JSON 编码后的结果：正常是一条被转义的 JSON 字符串，
     * 自检脚本没返回值时是字面量 `null`。两种都要能分辨，
     * 否则失败原因会写成“解析失败”，掩盖真正的问题。
     */
    internal fun parseVerificationResult(raw: String?): Pair<Boolean, List<String>> = runCatching {
        if (raw == null || raw.isBlank() || raw == "null") {
            return@runCatching false to listOf("自检未返回结果")
        }
        val first = AppJson.parseToJsonElement(raw)
        val elem = if (first is JsonPrimitive && first.isString) {
            AppJson.parseToJsonElement(first.content)
        } else {
            first
        }
        val obj = elem as? JsonObject ?: error("自检返回格式不是对象")
        val verified = (obj["verified"] as? JsonPrimitive)?.booleanOrNull == true
        val issues = (obj["issues"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() } ?: emptyList()
        verified to issues
    }.getOrElse { false to listOf("自检结果解析失败：${it.message}") }

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
                // 不做二次编码：value 本身已是 JSON 文本，这里解析成结构化元素返回，
                // 让下游只序列化一次。否则 return {"a":1} 的字符串会被再包一层引号+转义，
                // 模型得自己反两次 —— 用户实测 `"{\"a\":1}"` 就是这么来的。
                val structured = valueStr?.let { text ->
                    runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
                }
                (structured ?: valueStr) to null
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
