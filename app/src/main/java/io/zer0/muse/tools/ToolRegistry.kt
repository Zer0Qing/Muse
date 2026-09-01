package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.ToolDefinition
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5-H: 工具注册表(简化版 MCP 框架)。
 *
 * Phase 7 扩展:接入 LLM function calling —
 *  - [listToolsAsToolDefinitions] 生成 OpenAI 兼容的 ToolDefinition 列表
 *  - [executeFromJson] 从 LLM 返回的 arguments JSON 字符串执行工具
 *
 * Phase 8.8 扩展:
 *  - 改为动态注册(mutableMap + [register] API),支持运行时增删工具
 *  - 内置 7 个本地工具(本地工具 7 件套,无 QuickJS):
 *    1. get_current_time — 获取当前时间(支持时区参数)
 *    2. calculator — 简易计算器(四则运算)
 *    3. echo — 回显(测试用)
 *    4. clipboard_read — 读取系统剪贴板
 *    5. clipboard_write — 写入系统剪贴板
 *    6. screen_time — 获取今日屏幕使用时间统计(UsageStatsManager)
 *    7. calendar_today — 获取今日日历事件(CalendarContract)
 *  - 手机端工具(10 个,Android 系统 API):
 *    8. set_alarm — 设置闹钟(AlarmClock,支持每周重复)
 *    9. set_timer — 设置倒计时(AlarmClock)
 *    10. open_app — 打开应用(PackageManager,支持 Deep Link)
 *    11. share_text — 分享文本(ACTION_SEND)
 *    12. get_location — 获取粗略位置(LocationManager,需 ACCESS_COARSE_LOCATION)
 *    13. get_device_info — 获取设备信息(Build/BatteryManager)
 *    14. get_contacts_count — 联系人数量(ContactsContract,需 READ_CONTACTS)
 *    15. get_contacts_list — 联系人列表(增强版,需 READ_CONTACTS)
 *    16. send_sms — 发送短信(SmsManager,需 SEND_SMS)
 *    17. add_contact — 新建联系人(Intent.ACTION_INSERT)
 *  - 系统控制与邮件工具(5 个):
 *    18. open_system_setting — 打开系统设置页(支持 wifi/bluetooth/display 等分类)
 *    19. toggle_wifi — 开关 WiFi(Android 10+ 跳设置页,可读状态)
 *    20. toggle_bluetooth — 开关蓝牙(可读状态/关闭/跳设置页开启)
 *    21. send_email — 发送邮件(ACTION_SENDTO 打开邮件应用)
 *    22. get_battery_info — 获取电池信息(电量/充电状态)
 *  - [listToolsAsToolDefinitions] 支持 [enabledToolIds] 参数按 Assistant 过滤
 *
 * MCP 协议对标:
 *  - tools/list: [listTools] / [listToolsAsToolDefinitions]
 *  - tools/call: [execute] / [executeFromJson]
 *  - 后续扩展:从配置加载外部 MCP server(stdio/sse,Phase 9)
 *
 * @param context 应用 Context(用于需要系统服务的工具:Clipboard/UsageStats/Calendar)
 */
class ToolRegistry(
    private val context: Context,
    private val browserManager: BrowserManager = BrowserManager(context),
) {

    /** 工具定义(UI 展示用,parameters 是参数名 → 描述)。
     *
     * v1.???: 新增 [parameterTypes] 支持为每个参数指定 JSON Schema type,解决
     * execute_javascript 的 timeout_ms 等数字/布尔参数被 LLM 误传为 string 的问题。
     * 未指定时默认 type=string,保持与旧 [parameters] 行为的兼容性。
     */
    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: Map<String, String>, // 参数名 → 描述
        /** 是否必填。 */
        val required: Set<String> = emptySet(),
        /** 工具分类(用于 UI 分组展示):built-in / local / mcp。 */
        val category: String = "built-in",
        /** 参数名 → JSON Schema type(如 integer / boolean),未指定默认 string。 */
        val parameterTypes: Map<String, String> = emptyMap(),
        /** 工具风险等级,用于会话权限体系。 */
        val riskLevel: ToolRiskLevel = ToolRiskLevel.SAFE,
        /** 原始 JSON Schema。MCP 工具使用此字段保留嵌套对象、数组、枚举等完整结构。 */
        val rawParametersJsonSchema: String? = null,
    )

    /**
     * 工具执行函数:参数 map → 结果字符串。
     *
     * v1.134 P0-3: 改为 suspend 函数类型,消除 McpRegistry 的 runBlocking 反模式。
     * 内置工具大多是同步 IO(ContentResolver 查询 / LocationManager 读取等),
     * 加 suspend 关键字不影响内部实现,只是让方法引用类型匹配;
     * MCP 远程调用可直接在协程上下文中执行,超时用 withTimeoutOrNull 替代线程池超时。
     */
    private typealias ToolFn = suspend (Map<String, String>) -> String

    /** 需要宿主会话边界的工具，context 由执行链路注入，模型不能伪造。 */
    typealias ContextToolFn = suspend (Map<String, String>, ToolExecutionContext) -> String

    /** v1.0.53: 结构化结果执行函数(返回 [ToolOutcome])。 */
    private typealias ToolOutcomeFn = suspend (Map<String, String>) -> ToolOutcome

    /** MCP/JSON 工具执行函数,保留数字、布尔、数组和对象参数的原始类型。 */
    internal typealias JsonToolFn = suspend (JsonObject) -> String

    // M-TR2: 改用 ConcurrentHashMap,保证 register/unregister/execute 并发安全
    private val tools = ConcurrentHashMap<String, ToolFn>()
    private val contextTools = ConcurrentHashMap<String, ContextToolFn>()
    // v1.0.53: 结构化结果工具通道(优先于 [tools] 查找)
    private val outcomeTools = ConcurrentHashMap<String, ToolOutcomeFn>()
    private val jsonTools = ConcurrentHashMap<String, JsonToolFn>()
    private val toolDefs = ConcurrentHashMap<String, ToolDef>()
    private val _revision = MutableStateFlow(0L)
    /** 动态工具注册/注销版本，供工具页和请求组装器订阅刷新。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // v1.136: 定时提醒、资源库
    // v1.0.17: 快速记录改用 Room(MuseDb.get(context).quickNoteDao()),不再持有 QuickNoteStore

    init {
        // 注册内置 7 件套
        // JS 沙盒:让 AI 能执行 JavaScript 代码(数学计算/数据处理/简单算法)
        // 底层用 WebView 的 V8 引擎,不新增大型依赖(既有实现 QuickJS 思路)
        register(CodeExecutionTool.toolDef()) { args ->
            CodeExecutionTool.executeFromArgs(args, context)
        }

        // P2-6: 浏览器自动化工具集(navigate/click/type/extract/scroll_bottom/get_html)
        // 与 Koin 注册的 BrowserManager 单例共享同一实例(由 AppToolModule 注入),
        // 保证 AI 工具操作与 UI 状态胶囊实时同步。
        BrowserAutomationTool.toolDefs().forEach { def ->
            register(def) { args ->
                BrowserAutomationTool.executeFromArgs(def.name, args, browserManager)
            }
        }
    }

    /**
     * Phase 8.8: 注册工具(动态扩展点,后续 MCP server 加载用)。
     * @param def 工具定义
     * @param fn 执行函数
     */
    fun register(def: ToolDef, fn: ToolFn) {
        tools[def.name] = fn
        contextTools.remove(def.name)
        outcomeTools.remove(def.name)
        jsonTools.remove(def.name)
        toolDefs[def.name] = def
        _revision.value += 1
    }

    /** 注册需要宿主会话 scope/space 边界的工具。 */
    fun registerWithContext(def: ToolDef, fn: ContextToolFn) {
        contextTools[def.name] = fn
        tools.remove(def.name)
        outcomeTools.remove(def.name)
        jsonTools.remove(def.name)
        toolDefs[def.name] = def
        _revision.value += 1
    }

    /**
     * v1.0.53: 注册结构化结果工具(返回 [ToolOutcome])。
     * 优先于旧 String 通道;同名的 String 注册会覆盖回旧通道。
     */
    fun registerOutcome(def: ToolDef, fn: ToolOutcomeFn) {
        outcomeTools[def.name] = fn
        tools.remove(def.name)
        jsonTools.remove(def.name)
        toolDefs[def.name] = def
        _revision.value += 1
    }

    /**
     * 注册保留原始 JSON 参数的工具。
     *
     * MCP 的 inputSchema 允许 number / boolean / array / object 参数;
     * 普通 [ToolFn] 为兼容内置工具会把参数压成 String,因此 MCP 工具必须走此通道。
     */
    internal fun registerJson(def: ToolDef, fn: JsonToolFn) {
        jsonTools[def.name] = fn
        tools.remove(def.name)
        outcomeTools.remove(def.name)
        toolDefs[def.name] = def
        _revision.value += 1
    }

    /** 注销工具。 */
    fun unregister(name: String) {
        tools.remove(name)
        contextTools.remove(name)
        outcomeTools.remove(name)
        jsonTools.remove(name)
        toolDefs.remove(name)
        _revision.value += 1
    }

    private fun registerBuiltIn(
        name: String,
        description: String,
        parameters: Map<String, String>,
        required: Set<String>,
        fn: ToolFn,
        riskLevel: ToolRiskLevel = ToolRiskLevel.SAFE,
        parameterTypes: Map<String, String> = emptyMap(),
    ) {
        register(ToolDef(name, description, parameters, required, "built-in", parameterTypes, riskLevel), fn)
    }

    /**
     * v1.0.53: 注册结构化结果内置工具(返回 [ToolOutcome])。
     * 覆盖同名 String 通道。
     */
    private fun registerBuiltInOutcome(
        name: String,
        description: String,
        parameters: Map<String, String>,
        required: Set<String>,
        fn: ToolOutcomeFn,
        riskLevel: ToolRiskLevel = ToolRiskLevel.SAFE,
        parameterTypes: Map<String, String> = emptyMap(),
    ) {
        registerOutcome(ToolDef(name, description, parameters, required, "built-in", parameterTypes, riskLevel), fn)
    }

    // v1.0.53: 工具分类注册表启动断言 — 每个内置工具必须有分类
    // 只检查 category="built-in"(MCP/插件工具豁免);debug 构建缺失时抛异常,release 仅记日志
    init {
        val builtInNames = toolDefs.values.filter { it.category == "built-in" }.map { it.name }.toSet()
        val uncovered = ToolCategories.assertCoverage(builtInNames)
        if (uncovered.isNotEmpty()) {
            val msg = "内置工具分类注册表缺失: ${uncovered.joinToString(", ")} (见 ToolCategories.kt)"
            if (io.zer0.muse.BuildConfig.DEBUG) {
                throw IllegalStateException(msg)
            } else {
                Logger.w("ToolRegistry", msg)
            }
        }
    }

    /** 列出所有可用工具(对标 MCP tools/list)。 */
    fun listTools(): List<ToolDef> = toolDefs.values.toList()

    /**
     * 获取指定工具的风险等级,未注册时返回 null。
     *
     * 供 [ToolPermissionResolver] 结合会话权限模式判断是否需要审批。
     */
    fun getToolRiskLevel(name: String): ToolRiskLevel? = toolDefs[name]?.riskLevel

    /**
     * Phase 8.8: 按工具 id 列表过滤(为 Assistant 绑定工具子集用)。
     * @param enabledToolIds 启用的工具 id 列表;null 或空列表表示全部启用
     */
    fun listTools(enabledToolIds: List<String>? = null): List<ToolDef> {
        val all = toolDefs.values.toList()
        if (enabledToolIds.isNullOrEmpty()) return all
        return all.filter { it.name in enabledToolIds }
    }

    /**
     * Phase 7: 生成 OpenAI 兼容的 ToolDefinition 列表(供 ChatService.streamChat(tools=...) 使用)。
     * Phase 8.8: 支持 [enabledToolIds] 按 Assistant 过滤。
     * 把 [ToolDef.parameters](参数名 → 描述) 转换为 JSON Schema 字符串。
     *
     * @param enabledToolIds 启用的工具 id 列表;null 或空列表表示全部启用
     */
    fun listToolsAsToolDefinitions(enabledToolIds: List<String>? = null): List<ToolDefinition> =
        listTools(enabledToolIds).map { def ->
            val schema = def.rawParametersJsonSchema
                ?.takeIf { raw -> runCatching { AppJson.decodeFromString(JsonObject.serializer(), raw) }.isSuccess }
                ?.let { raw -> AppJson.decodeFromString(JsonObject.serializer(), raw) }
                ?: buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        def.parameters.forEach { (name, desc) ->
                            put(name, buildJsonObject {
                                put("type", def.parameterTypes[name] ?: "string")
                                put("description", desc)
                            })
                        }
                    })
                    if (def.required.isNotEmpty()) {
                        put("required", kotlinx.serialization.json.JsonArray(
                            def.required.map { JsonPrimitive(it) }
                        ))
                    }
                }
            ToolDefinition(
                name = def.name,
                description = def.description,
                parametersJsonSchema = AppJson.encodeToString(JsonObject.serializer(), schema),
            )
        // v1.0.4 修复 HTTP 400 "Tool names must be unique":
        // 防御性按 name 去重,即使 ToolRegistry 内部因多 Registrar 注册同名工具也能拦截。
        }.distinctBy { it.name }

    /**
     * 执行工具(对标 MCP tools/call)。
     *
     * v1.134 P0-3: 改为 suspend 函数,与 [ToolFn] 类型对齐。
     * 调用方无须再手动 withContext(Dispatchers.IO) 包裹(但保留也无害)。
     *
     * @param name 工具名
     * @param args 参数 map
     * @return 执行结果字符串;工具不存在或参数错误返回错误信息
     */
    suspend fun execute(
        name: String,
        args: Map<String, String>,
        cancellationToken: () -> Boolean = { false },
    ): ToolOutcome = executeInternal(name, args, null, cancellationToken)

    /** 带宿主上下文执行工具；需要隔离边界的工具必须走此入口。 */
    suspend fun execute(
        name: String,
        args: Map<String, String>,
        executionContext: ToolExecutionContext,
        cancellationToken: () -> Boolean = { false },
    ): ToolOutcome = executeInternal(name, args, executionContext, cancellationToken)

    private suspend fun executeInternal(
        name: String,
        args: Map<String, String>,
        executionContext: ToolExecutionContext?,
        cancellationToken: () -> Boolean,
    ): ToolOutcome {
        jsonTools[name]?.let { fn ->
            return executeJson(name, stringArgsToJson(args), fn, cancellationToken)
        }
        // v1.0.53: 内容级安全规则(执行前硬边界,不受审批模式影响)
        val content = args.values.joinToString("\n")
        ContentSafetyRules.check(name, content)?.let { rule ->
            Logger.w("ToolRegistry", "内容安全规则命中: ${rule.ruleId} (tool=$name)")
            return ToolOutcome.error("${rule.reason}(${rule.ruleId})")
        }
        // v1.0.53: 取消令牌(停止生成时置 true,长工具尽快返回)
        if (cancellationToken()) {
            return ToolOutcome.error("工具调用已取消")
        }
        // P1: 工具参数 schema 校验 — 缺失/类型错误在执行前拦截,
        // 返回结构化错误给 LLM,避免上游 400 或工具静默失败。
        val def = toolDefs[name]
        val validation = ToolArgValidator.validate(name, args, def)
        if (!validation.valid) {
            val detail = validation.errors.joinToString("; ")
            Logger.w("ToolRegistry", "工具参数校验失败: $name -> $detail")
            return ToolOutcome.error(
                detail,
                mapOf("errorType" to ToolArgValidator.ERROR_TYPE, "tool" to name, "errors" to validation.errors),
            )
        }
        val validArgs = validation.coercedArgs
        contextTools[name]?.let { fn ->
            val executionContext = executionContext
                ?: return ToolOutcome.error("工具 $name 需要会话执行上下文")
            return resultOf { fn(validArgs, executionContext) }
                .onError { msg, _ -> Logger.w("ToolRegistry", "工具 $name 执行异常: $msg") }
                .getOrNull()?.let { ToolOutcome.ok(it) }
                ?: ToolOutcome.error(this.context.getString(R.string.tool_exec_exception))
        }
        // v1.0.53: 优先结构化通道
        outcomeTools[name]?.let { fn ->
            return resultOf { fn(validArgs) }
                .onError { msg, _ -> Logger.w("ToolRegistry", "工具 $name 执行异常: $msg") }
                .getOrNull() ?: ToolOutcome.error(context.getString(R.string.tool_exec_exception))
        }
        val fn = tools[name]
            ?: return ToolOutcome.error(context.getString(R.string.tool_not_found, name, tools.keys.joinToString(", ")))
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf { fn(validArgs) }
            .onError { msg, _ -> Logger.w("ToolRegistry", "工具 $name 执行异常: $msg") }
            .getOrNull()?.let { ToolOutcome.ok(it) }
            ?: ToolOutcome.error(context.getString(R.string.tool_exec_exception))
    }

    /**
     * Phase 7: 从 LLM 返回的 arguments JSON 字符串执行工具。
     * @param name 工具名
     * @param argumentsJson LLM 返回的参数 JSON 字符串(如 {"expression":"1+2*3"})
     * @return 执行结果字符串
     */
    suspend fun executeFromJson(name: String, argumentsJson: String): String =
        executeFromJsonInternal(name, argumentsJson, null)

    suspend fun executeFromJson(
        name: String,
        argumentsJson: String,
        executionContext: ToolExecutionContext,
    ): String = executeFromJsonInternal(name, argumentsJson, executionContext)

    private suspend fun executeFromJsonInternal(
        name: String,
        argumentsJson: String,
        executionContext: ToolExecutionContext?,
    ): String {
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        val obj = resultOf {
            parseArgumentsLenient(argumentsJson)
        }.onError { msg, _ ->
            Logger.w("ToolRegistry", "executeFromJson 参数解析失败: $msg(原始: $argumentsJson)")
        }.getOrNull() ?: return context.getString(R.string.tool_param_parse_failed, argumentsJson)

        jsonTools[name]?.let { fn ->
            val outcome = executeJson(name, obj, fn)
            val content = outcome.content.ifBlank {
                context.getString(R.string.tool_exec_empty_result, name)
            }
            return if (outcome.isError && !content.startsWith("Error:", ignoreCase = true)) {
                "Error: $content"
            } else {
                content
            }
        }

        val args = resultOf {
            obj.entries.associate { (k, v) -> k to v.toString().trim('"') }
        }.onError { msg, _ ->
            Logger.w("ToolRegistry", "executeFromJson 参数转换失败: $msg(原始: $argumentsJson)")
        }.getOrNull() ?: return context.getString(R.string.tool_param_parse_failed, argumentsJson)
        // v1.0.53: execute 返回 ToolOutcome,取 content 保持 String 语义。
        // 空字符串不能继续向上游传播,否则工具卡片只能显示“执行中”而没有终态。
        val outcome = if (executionContext != null) {
            execute(name, args, executionContext)
        } else {
            execute(name, args)
        }
        val content = outcome.content.ifBlank {
            context.getString(R.string.tool_exec_empty_result, name)
        }
        return if (outcome.isError && !content.startsWith("Error:", ignoreCase = true)) {
            "Error: $content"
        } else {
            content
        }
    }

    private suspend fun executeJson(
        name: String,
        rawArgs: JsonObject,
        fn: JsonToolFn,
        cancellationToken: () -> Boolean = { false },
    ): ToolOutcome {
        val stringArgs = rawArgs.entries.associate { (k, v) -> k to v.toString().trim('"') }
        val content = stringArgs.values.joinToString("\n")
        ContentSafetyRules.check(name, content)?.let { rule ->
            Logger.w("ToolRegistry", "内容安全规则命中: ${rule.ruleId} (tool=$name)")
            return ToolOutcome.error("${rule.reason}(${rule.ruleId})")
        }
        if (cancellationToken()) return ToolOutcome.error("工具调用已取消")
        val def = toolDefs[name]
        val validation = ToolArgValidator.validate(name, stringArgs, def)
        if (!validation.valid) {
            val detail = validation.errors.joinToString("; ")
            Logger.w("ToolRegistry", "工具参数校验失败: $name -> $detail")
            return ToolOutcome.error(
                detail,
                mapOf("errorType" to ToolArgValidator.ERROR_TYPE, "tool" to name, "errors" to validation.errors),
            )
        }
        return try {
            ToolOutcome.ok(fn(rawArgs))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w("ToolRegistry", "JSON 工具 $name 执行异常: ${e.message}")
            ToolOutcome.error(context.getString(R.string.tool_exec_exception))
        }
    }

    private fun stringArgsToJson(args: Map<String, String>): JsonObject = buildJsonObject {
        args.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }

    /**
     * v1.x: 容错解析工具参数 JSON。
     *
     * 模型(尤其深度思考模式下经中转站的推理模型)可能输出畸形参数:
     *  - 拼接多个 JSON 对象,如 `{}{\"selector\": \"h1\"}` 或 `{\"selector\": \"h1\"}{}`
     *  - 前后多余空白
     *
     * 策略:先标准解析;失败后按最外层花括号配对拆分所有片段,逐个解析并合并
     * (后者覆盖同名键),任一片段解析成功即返回。
     */
    internal fun parseArgumentsLenient(json: String): JsonObject {
        // 1. 标准解析
        runCatching { AppJson.decodeFromString(JsonObject.serializer(), json) }
            .getOrNull()?.let { return it }
        // 2. 容错:按最外层 {} 配对拆分
        val fragments = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            fragments += json.substring(start, i + 1)
                            start = -1
                        }
                    }
                }
            }
        }
        if (fragments.size > 1) {
            val merged = buildJsonObject {
                fragments.forEach { frag ->
                    runCatching { AppJson.decodeFromString(JsonObject.serializer(), frag) }
                        .getOrNull()?.forEach { (k, v) -> put(k, v) }
                }
            }
            if (merged.isNotEmpty()) return merged
        }
        // 3. 兜底:把原始内容当作字符串参数(key = 首个声明参数或 "value")
        return buildJsonObject {
            put("value", JsonPrimitive(json.trim()))
        }
    }

    // ── 内置工具实现 ──────────────────────────────────────────────────────────

    /** Phase 8.8: 读取系统剪贴板文本。 */


    // ── v1.136: 资源库工具 ───────────────────────────────────────────────────

    // ── v1.136: 网络/编码/TTS 工具 ──────────────────────────────────────────

    /** Ping 指定的域名或 IP。 */

    /** TTS 朗读实现(挂起直到初始化完成并加入队列)。 */

    companion object {
        // v1.95: 所有内置 tool id 列表(与 init 块注册的工具一一对应)
        // 供 AssistantRepository.ensureDefaultExists 静态读取,无需 ToolRegistry 实例
        val BUILT_IN_TOOL_IDS: List<String> = listOf(
            "get_weather", "get_current_time", "calculator", "echo", "clipboard_read", "clipboard_write",
            "screen_time", "calendar_today", "add_calendar_event",
            "set_alarm", "set_timer", "open_app", "share_text", "get_location",
            "get_device_info", "get_contacts_count", "get_contacts_list",
            "send_sms", "add_contact",
            "open_system_setting", "toggle_wifi", "toggle_bluetooth", "send_email",
            "get_battery_info", "get_recent_notifications",
            "open_url", "list_installed_apps", "get_network_info",
            // v1.136: 新增系统/设备/编码工具
            "get_storage_info", "get_memory_info", "get_display_info", "get_cpu_info", "get_sensors_list",
            "get_brightness", "set_brightness", "get_volume", "set_volume", "toggle_flashlight", "vibrate",
            "get_foreground_app", "get_wifi_info", "get_bluetooth_devices", "make_phone_call", "open_maps",
            "url_encode", "url_decode", "base64_encode", "base64_decode", "hash_text", "generate_uuid", "random_number",
            // v1.136: 定时提醒与资源库工具
            "schedule_reminder", "cancel_reminder", "list_reminders",
            "resource_add", "resource_list", "resource_search", "resource_get", "resource_delete",
            // v1.136: 快速记录工具
            "quick_note_add", "quick_note_list", "quick_note_search", "quick_note_get",
            "quick_note_update", "quick_note_delete", "quick_note_pin",
            // v1.136: 网络/编码/TTS 工具
            "ping_host", "dns_lookup", "get_public_ip", "json_pretty", "generate_password", "speak_text",
            // v1.135: 媒体生成工具(ChatViewModel 注册,此处登记用于默认助手启用)
            "generate_image", "generate_video", "generate_qr_code",
            // v1.95: 表情包库工具(SkillExecutor 实现,此处登记便于统一识别)
            "list_stickers", "send_sticker",
            // HanaAgent port: additional tools
            "pin_memory", "unpin_memory",
            "recall_experience", "record_experience",
            "todo_write", "show_card", "notify", "current_status",
            "subagent_task",
            // JS 沙盒工具(WebView evaluateJavascript,CodeExecutionTool 实现)
            "execute_javascript",
            // P2-6: 浏览器自动化工具(BrowserAutomationTool 实现,headless WebView)
            "browser_navigate", "browser_click", "browser_type",
            "browser_extract", "browser_scroll_bottom", "browser_get_html",
            // P2-7: 工作区文件管理工具(WorkspaceToolsRegistrar 注册)
            "workspace_list", "workspace_read", "workspace_write",
            "workspace_delete", "workspace_mkdir", "workspace_move",
            // v1.0.17: 定时任务工具(助手可创建/管理定时任务)
            "scheduled_task_create", "scheduled_task_list", "scheduled_task_update",
            "scheduled_task_delete", "scheduled_task_execute", "scheduled_task_get_history",
            // v1.0.17: 翻译工具(与 SkillExecutor translate skill 对齐,统一走 ToolRegistry)
            "translate",
        )

        /**
         * v1.131: Tool 内常用日期格式器 — ThreadLocal 缓存,避免每次 LLM 工具调用都新建 SimpleDateFormat。
         * LLM 高频调用工具(getCalendarEvents / parseDateTime / getNotifications 等),
         * 旧实现每次都 `SimpleDateFormat(pattern, Locale).format(...)` 造成 GC 压力。
         * SimpleDateFormat 非线程安全,用 ThreadLocal 保证每线程独立实例。
         */
        private val FMT_DATE = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

        /** parseDateTime 尝试的所有格式(线程安全列表,内部 SimpleDateFormat 通过 ThreadLocal 隔离)。 */
        private val PARSE_FORMATS_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )
        private val PARSE_FORMATS_TL = ThreadLocal.withInitial {
            PARSE_FORMATS_PATTERNS.map { SimpleDateFormat(it, Locale.getDefault()) }
        }
    }
}

