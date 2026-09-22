package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.common.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-6 BrowserAutomationTool:浏览器自动化工具集(AI 可调用)。
 *
 * 把 [BrowserManager] 的能力封装为 6 个独立工具,注册到 [ToolRegistry]:
 *  1. [TOOL_NAVIGATE]   — browser_navigate(url)        导航到 URL
 *  2. [TOOL_CLICK]      — browser_click(selector)       点击元素
 *  3. [TOOL_TYPE]       — browser_type(selector, text)  输入文本
 *  4. [TOOL_EXTRACT]    — browser_extract(selector)     提取文本
 *  5. [TOOL_SCROLL_BOTTOM] — browser_scroll_bottom()    滚动到底部
 *  6. [TOOL_GET_HTML]   — browser_get_html()            获取当前页 HTML(截断)
 *
 * 桥接说明(同 [CodeExecutionTool]):
 *  - [executeFromArgs] 是同步函数,适配 ToolRegistry 的 ToolFn 签名 `(Map<String,String>) -> String`
 *  - 内部用 [runBlocking] 桥接到 [BrowserManager] 的 suspend 函数 — 因 [ToolRouteExecutionGuard.executeFromJson]
 *    在 IO 协程中调用,阻塞 IO 线程等待主线程
 *    WebView 回调,主线程未被阻塞,无死锁风险
 *
 * 注册方式(按 [ToolRegistry.init] 中的 CodeExecutionTool 注册):
 * ```kotlin
 * BrowserAutomationTool.toolDefs().forEach { def ->
 *     toolRegistry.register(def) { args ->
 *         BrowserAutomationTool.executeFromArgs(def.name, args, browserManager)
 *     }
 * }
 * ```
 */
object BrowserAutomationTool {

    private const val TAG = "BrowserAutomationTool"

    /** 工具名常量(注册到 ToolRegistry 的 id)。 */
    const val TOOL_NAVIGATE = "browser_navigate"
    const val TOOL_CLICK = "browser_click"
    const val TOOL_TYPE = "browser_type"
    const val TOOL_EXTRACT = "browser_extract"
    const val TOOL_SCROLL_BOTTOM = "browser_scroll_bottom"
    const val TOOL_GET_HTML = "browser_get_html"
    const val TOOL_SNAPSHOT = "browser_snapshot"

    /**
     * 全部工具定义列表(供 [ToolRegistry.register] 批量注册)。
     *
     * @return 6 个 [ToolRegistry.ToolDef] 的列表
     */
    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = TOOL_NAVIGATE,
            // v1.0.75 fix (工具审查 02): 补时序依赖声明
            description = "浏览器自动化:导航到指定 URL。必须先调用本工具完成导航,再使用 browser_click/browser_type/browser_extract 操作页面。所有浏览器工具返回 JSON,含 success 与 data/error。" +
                "URL 可省略 scheme(如 example.com 自动补 https://)。超时 10 秒。",
            parameters = mapOf(
                "url" to "必填,目标 URL,如 https://example.com 或 example.com",
            ),
            required = setOf("url"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_CLICK,
            description = "浏览器自动化:点击目标元素。两种定位方式二选一:" +
                "① selector(CSS 选择器,语法同 document.querySelector);" +
                "② numberId(browser_snapshot 返回的元素编号,推荐——无需了解页面结构)。",
            parameters = mapOf(
                "selector" to "可选,CSS 选择器,如 '#login-btn' 或 'button.submit'",
                "numberId" to "可选,快照元素编号(与 selector 二选一,提供时优先)",
            ),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_TYPE,
            description = "浏览器自动化:向目标 input/textarea 输入文本。" +
                "定位方式二选一:selector(CSS 选择器)或 numberId(快照编号,推荐)。" +
                "自动触发 input + change 事件以兼容 React/Vue 等框架。",
            parameters = mapOf(
                "selector" to "可选,CSS 选择器,如 '#search-input'",
                "numberId" to "可选,快照元素编号(与 selector 二选一,提供时优先)",
                "text" to "必填,要输入的文本内容",
            ),
            required = setOf("text"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_EXTRACT,
            // v1.0.75 fix (工具审查 02): 与 parse_link 区分
            description = "浏览器自动化:提取 CSS 选择器命中元素的 textContent。" +
                "未命中返回空串。可用于抓取商品价格、文章正文、列表项等。" +
                "区别于 parse_link: 本工具读浏览器当前已加载页面,parse_link 从 URL 抓取独立页面。",
            parameters = mapOf(
                "selector" to "必填,CSS 选择器,如 '.price' 或 'article h1'",
            ),
            required = setOf("selector"),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_SCROLL_BOTTOM,
            description = "浏览器自动化:滚动到页面底部。常用于无限滚动场景加载更多内容(如商品列表、动态流)。",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_GET_HTML,
            description = "浏览器自动化:获取当前页面的 HTML 源码(截断到 50KB 防止上下文爆炸)。" +
                "可用于分析页面结构、提取表单字段等。",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_SNAPSHOT,
            description = "浏览器自动化:提取当前页面的结构化快照 — 标题/URL/可交互元素" +
                "(链接/按钮/输入框,带连续编号)/正文预览。用它了解页面结构,再用 " +
                "browser_click/browser_type 的 numberId 按编号操作。",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
    )

    /**
     * 同步桥接:适配 ToolRegistry 的 ToolFn 签名。
     *
     * 内部用 [runBlocking] 调用 [BrowserManager] 的 suspend 函数:
     *  - [ToolRouteExecutionGuard.executeFromJson] 在 IO 协程中调用,
     *    当前线程为 IO 线程,阻塞等待主线程 WebView 回调时主线程空闲,无死锁风险
     *
     * @param toolName 工具名(用于分发到对应 BrowserManager API)
     * @param args 参数 map
     * @param browserManager 浏览器管理器单例
     * @return JSON 字符串,包含 success 字段(true/false)与 data/error 字段
     */
    fun executeFromArgs(
        toolName: String,
        args: Map<String, String>,
        browserManager: BrowserManager,
    ): String = runBlocking {
        try {
            when (toolName) {
                TOOL_NAVIGATE -> {
                    val url = args["url"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "参数 url 缺失或为空")
                    browserManager.navigate(url).fold(
                        onSuccess = { buildResult(success = true, data = "已导航到: $url") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "导航失败") },
                    )
                }
                TOOL_CLICK -> {
                    val numberId = args["numberId"]?.trim()?.toIntOrNull()
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                    when {
                        numberId != null -> browserManager.evaluateJs(buildNumberedClickJs(numberId)).fold(
                            onSuccess = { raw ->
                                val ok = raw.contains("OK")
                                buildResult(
                                    success = true,
                                    data = if (ok) "已点击编号 $numberId 元素" else "编号 $numberId 未找到(页面可能已刷新,请重新 browser_snapshot)",
                                )
                            },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "点击失败") },
                        )
                        selector != null -> browserManager.click(selector).fold(
                            onSuccess = { hit -> buildResult(success = true, data = if (hit) "已点击元素: $selector" else "未命中元素: $selector") },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "点击失败") },
                        )
                        else -> buildResult(success = false, error = "参数 selector 或 numberId 至少需要一个")
                    }
                }
                TOOL_TYPE -> {
                    val numberId = args["numberId"]?.trim()?.toIntOrNull()
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                    val text = args["text"] ?: return@runBlocking buildResult(success = false, error = "参数 text 缺失")
                    when {
                        numberId != null -> browserManager.evaluateJs(buildNumberedTypeJs(numberId, text)).fold(
                            onSuccess = { raw ->
                                val ok = raw.contains("OK")
                                buildResult(
                                    success = true,
                                    data = if (ok) "已向编号 $numberId 元素输入文本" else "编号 $numberId 未找到(页面可能已刷新,请重新 browser_snapshot)",
                                )
                            },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "输入失败") },
                        )
                        selector != null -> browserManager.type(selector, text).fold(
                            onSuccess = { hit -> buildResult(success = true, data = if (hit) "已输入文本到: $selector" else "未命中元素: $selector") },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "输入失败") },
                        )
                        else -> buildResult(success = false, error = "参数 selector 或 numberId 至少需要一个")
                    }
                }
                TOOL_EXTRACT -> {
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "参数 selector 缺失或为空")
                    browserManager.extractText(selector).fold(
                        onSuccess = { text -> buildResult(success = true, data = text) },
                        onFailure = { e ->
                            Logger.w(TAG, "extractText 失败: selector=$selector | url=${browserManager.currentUrl.value} | err=${e.message}")
                            buildResult(success = false, error = e.message ?: "提取失败")
                        },
                    )
                }
                TOOL_SCROLL_BOTTOM -> {
                    browserManager.scrollToBottom().fold(
                        onSuccess = { buildResult(success = true, data = "已滚动到底部") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "滚动失败") },
                    )
                }
                TOOL_SNAPSHOT -> {
                    browserManager.evaluateJs(SNAPSHOT_JS).fold(
                        onSuccess = { json -> buildResult(success = true, data = json) },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "快照提取失败") },
                    )
                }
                TOOL_GET_HTML -> {
                    val html = browserManager.currentHtml.value
                    buildResult(success = true, data = html)
                }
                else -> buildResult(success = false, error = "未知工具: $toolName")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "executeFromArgs 异常: toolName=$toolName, msg=${e.message}", e)
            buildResult(success = false, error = e.message ?: "执行异常")
        }
    }

    /**
     * 构造执行结果 JsonObject 并序列化为字符串。
     *
     * 字段约定:
     *  - success: Boolean — 执行是否成功
     *  - data: String? — 成功时的返回值(导航/点击/输入为提示文本;提取为内容;get_html 为 HTML 源码)
     *  - error: String? — 失败时的错误信息
     */
    private fun buildResult(
        success: Boolean,
        data: String? = null,
        error: String? = null,
    ): String {
        val obj: JsonObject = buildJsonObject {
            put("success", JsonPrimitive(success))
            if (data != null) {
                put("data", JsonPrimitive(data))
            } else {
                put("data", JsonNull)
            }
            if (error != null) {
                put("error", JsonPrimitive(error))
            } else {
                put("error", JsonNull)
            }
        }
        return obj.toString()
    }

    /**
     * v1.0.92: 页面结构化快照脚本 — 提取可交互元素并缓存到 window.__museEls,
     * 供 numberId 模式的 click/type 按编号操作(页面刷新后缓存失效,需重新快照)。
     *
     * 预算: 链接≤40 / 按钮≤30 / 输入框≤20,正文预览≤3000 字符。
     */
    private const val SNAPSHOT_JS = """(function(){
  try {
    function textOf(el){ return (el && (el.innerText || el.textContent) || '').replace(/\s+/g,' ').trim(); }
    var els = [];
    var links = [], buttons = [], inputs = [];
    document.querySelectorAll('a[href]').forEach(function(a){
      if (links.length >= 40) return;
      var t = textOf(a); if (!t) return;
      links.push({id: els.length, text: t.substring(0, 80), href: a.href});
      els.push(a);
    });
    document.querySelectorAll('button,[role=button],input[type=submit],input[type=button]').forEach(function(b){
      if (buttons.length >= 30) return;
      var t = textOf(b) || b.value || ''; if (!t) return;
      buttons.push({id: els.length, text: t.substring(0, 80)});
      els.push(b);
    });
    document.querySelectorAll('input:not([type=hidden]),textarea,select').forEach(function(inp){
      if (inputs.length >= 20) return;
      inputs.push({id: els.length, name: inp.name || '', placeholder: inp.placeholder || '', type: inp.type || 'text'});
      els.push(inp);
    });
    window.__museEls = els;
    var body = textOf(document.body);
    return {
      url: location.href,
      title: document.title,
      links: links,
      buttons: buttons,
      inputs: inputs,
      bodyPreview: body.substring(0, 3000),
      truncated: body.length > 3000
    };
  } catch (e) { return {error: String((e && e.message) || e)}; }
})()"""

    /** v1.0.92: numberId 模式点击脚本。 */
    private fun buildNumberedClickJs(index: Int): String =
        "(function(){var el=(window.__museEls||[])[$index];" +
            "if(!el){return 'MISS';}" +
            "try{el.scrollIntoView({block:'center'});}catch(e){}" +
            "el.click();return 'OK';})()"

    /** v1.0.92: numberId 模式输入脚本(text 经 JSON 编码防注入)。 */
    private fun buildNumberedTypeJs(index: Int, text: String): String {
        val encoded = AppJson.encodeToString(String.serializer(), text)
        return "(function(){var el=(window.__museEls||[])[$index];" +
            "if(!el){return 'MISS';}" +
            "el.focus();el.value=$encoded;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return 'OK';})()"
    }
}
