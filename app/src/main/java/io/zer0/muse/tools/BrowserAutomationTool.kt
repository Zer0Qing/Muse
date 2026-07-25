package io.zer0.muse.tools

import io.zer0.common.Logger
import kotlinx.coroutines.runBlocking
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
 *  - 内部用 [runBlocking] 桥接到 [BrowserManager] 的 suspend 函数 — 因 ToolRegistry.execute
 *    由 GenerationHandler.executeTool(suspend)在 IO 协程中调用,阻塞 IO 线程等待主线程
 *    WebView 回调,主线程未被阻塞,无死锁风险
 *
 * 注册方式(参考 [ToolRegistry.init] 中的 CodeExecutionTool 注册):
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

    /**
     * 全部工具定义列表(供 [ToolRegistry.register] 批量注册)。
     *
     * @return 6 个 [ToolRegistry.ToolDef] 的列表
     */
    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = TOOL_NAVIGATE,
            description = "浏览器自动化:导航到指定 URL。加载完成后更新当前页状态(URL/标题/HTML)。" +
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
            description = "浏览器自动化:点击 CSS 选择器命中的第一个元素。" +
                "选择器语法同 document.querySelector,如 '#submit-btn' / '.nav a[href=\"/about\"]'。",
            parameters = mapOf(
                "selector" to "必填,CSS 选择器,如 '#login-btn' 或 'button.submit'",
            ),
            required = setOf("selector"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_TYPE,
            description = "浏览器自动化:向 CSS 选择器命中的第一个 input/textarea 输入文本。" +
                "自动触发 input + change 事件以兼容 React/Vue 等框架。",
            parameters = mapOf(
                "selector" to "必填,CSS 选择器,如 '#search-input' 或 'input[name=\"q\"]'",
                "text" to "必填,要输入的文本内容",
            ),
            required = setOf("selector", "text"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_EXTRACT,
            description = "浏览器自动化:提取 CSS 选择器命中元素的 textContent。" +
                "未命中返回空串。可用于抓取商品价格、文章正文、列表项等。",
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
    )

    /**
     * 同步桥接:适配 ToolRegistry 的 ToolFn 签名。
     *
     * 内部用 [runBlocking] 调用 [BrowserManager] 的 suspend 函数:
     *  - ToolRegistry.execute 由 GenerationHandler.executeTool(suspend)在 IO 协程中调用,
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
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "参数 selector 缺失或为空")
                    browserManager.click(selector).fold(
                        onSuccess = { hit -> buildResult(success = true, data = if (hit) "已点击元素: $selector" else "未命中元素: $selector") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "点击失败") },
                    )
                }
                TOOL_TYPE -> {
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "参数 selector 缺失或为空")
                    val text = args["text"] ?: return@runBlocking buildResult(success = false, error = "参数 text 缺失")
                    browserManager.type(selector, text).fold(
                        onSuccess = { hit -> buildResult(success = true, data = if (hit) "已输入文本到: $selector" else "未命中元素: $selector") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "输入失败") },
                    )
                }
                TOOL_EXTRACT -> {
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "参数 selector 缺失或为空")
                    browserManager.extractText(selector).fold(
                        onSuccess = { text -> buildResult(success = true, data = text) },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "提取失败") },
                    )
                }
                TOOL_SCROLL_BOTTOM -> {
                    browserManager.scrollToBottom().fold(
                        onSuccess = { buildResult(success = true, data = "已滚动到底部") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "滚动失败") },
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
}
