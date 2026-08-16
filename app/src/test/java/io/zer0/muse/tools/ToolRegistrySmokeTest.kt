package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.x: 全工具烟雾测试 — 遍历 ToolRegistry 已注册的全部内置工具。
 *
 * 设计(区分两类工具,解决 Robolectric 环境挂起):
 *
 * 1. 安全工具([SAFE_EXECUTE_TOOLS]):纯 Kotlin/计算/无 Android 系统服务依赖,
 *    在测试线程直接执行,断言"不崩溃 + 返回非空"。这是工具链路的真实健康线。
 *    - echo / calculator / 编解码 / 哈希 / 随机数 / 时间等
 *
 * 2. 系统依赖工具(其余全部):依赖真实 Android 服务(WebView 内核 / TTS 引擎 /
 *    闹钟调度 / 电话短信 / ContentProvider / 网络),Robolectric 无真机能力,
 *    若执行会同步阻塞(历史卡死根因:withTimeoutOrNull 无法中断阻塞线程)。
 *    这类只验证「注册存在 + 入口可达」,运行时健康由真机冒烟覆盖。
 *
 * v1.0.72 修复史:
 *  - 第一版:全部工具 executeFromJson → 系统工具阻塞,测试卡死 17 分钟。
 *  - 第二版:withTimeoutOrNull 包协程 → 对同步阻塞无效,仍卡死。
 *  - 第三版:独立线程 + Future.get(超时) → Robolectric 主线程被 get 阻塞,
 *    工具内 Dispatchers.Main 永久排队,大量误超时。
 *  - 第四版(本版):安全/系统工具分组,安全工具主线程直跑(快且稳),
 *    系统工具仅注册校验。烟雾测试永不卡死,回归能即时暴露。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistrySmokeTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        registry = ToolRegistry(context)
    }

    /**
     * 安全工具逐个执行:不崩溃 + 返回非空。
     * 每工具 5s 协程超时兜底(防御未来新增工具意外阻塞)。
     */
    @Test
    fun `safe tools execute without crash and return non-empty result`() = runBlocking {
        val failures = mutableListOf<String>()
        for (name in SAFE_EXECUTE_TOOLS) {
            val result = try {
                withTimeoutOrNull(SAFE_TOOL_TIMEOUT_MS) {
                    registry.executeFromJson(name, "{}")
                }
            } catch (e: Throwable) {
                failures += "$name 抛异常: ${e.message}"
                continue
            }
            if (result == null) {
                failures += "$name 执行超时(阻塞)"
            } else if (result.isBlank()) {
                failures += "$name 返回空串"
            }
        }
        assertTrue(
            "安全工具未通过烟雾测试:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    /**
     * 全部内置工具静态表完整性 + ToolRegistry 自身运行时注册校验。
     *
     * B-32 关键改动:不再"静态表互查"(原实现 registered 直接取 BUILT_IN_TOOL_IDS 同一静态常量)。
     * 拆成两层:
     *  1. 运行时层(ToolRegistry(context) 自身 init{} 注册的工具)必须真实出现在 listTools()
     *     —— 验证 ToolRegistry 的 CodeExecutionTool / BrowserAutomationTool 自注册链路有效,
     *     且每个运行时 ToolDef 的 name/description 非空。
     *  2. 静态层:BUILT_IN_TOOL_IDS 声明的全部内置工具必须被分入 SAFE/SYSTEM 分组表,
     *     防止录入静态表却漏分组(原测试已覆盖,保留)。
     *
     * 说明:其余内置工具由各 Registrar 在 App 启动时经 Koin 注入 toolRegistry 注册
     * (见 ToolRegistrarBootstrapper),裸构造 ToolRegistry(context) 不会触发它们,
     * 故这里无法对全部静态 id 做运行时包含断言;其注册健康由 App 启动路径覆盖。
     */
    @Test
    fun `all built-in tools are registered and reachable`() = runBlocking {
        val staticIds = io.zer0.muse.tools.ToolRegistry.BUILT_IN_TOOL_IDS.toSet()
        assertTrue("静态表声明应至少 20 个内置工具,实际 ${staticIds.size}", staticIds.size >= 20)

        // B-32: 从真实 ToolRegistry(context) 读运行时 listTools()(返回实际注册的 ToolDef)
        val runtimeDefs = registry.listTools()
        assertTrue("运行时未注册任何工具", runtimeDefs.isNotEmpty())
        val runtimeNames = runtimeDefs.map { it.name }.toSet()

        // 运行时定义完整性:每个 ToolDef 必须 name/description 非空(定义存在)
        val malformed = runtimeDefs.filter { it.name.isBlank() || it.description.isBlank() }
            .map { "name='${it.name}' desc='${it.description}'" }
        assertTrue("运行时存在 name/description 为空的工具定义:\n$malformed", malformed.isEmpty())

        // ToolRegistry 自身 init{} 注册的工具必须真实出现在运行时 listTools()(非自证)
        val selfRegistered = SELF_REGISTERED_IN_TOOL_REGISTRY
        val missingFromRuntime = selfRegistered.filterNot { it in runtimeNames }
        assertTrue(
            "ToolRegistry 自身应注册以下工具但 listTools() 未返回(init 逻辑漂移):\n$missingFromRuntime",
            missingFromRuntime.isEmpty(),
        )

        // 静态层:安全工具 + 系统工具 = 全部静态内置工具,防止录入静态表却漏分组
        val all = SAFE_EXECUTE_TOOLS + SYSTEM_ONLY_TOOLS
        val unregistered = all.filterNot { it in staticIds }
        assertTrue("分组表中出现非内置工具:\n$unregistered", unregistered.isEmpty())

        // 防止分组遗漏:BUILT_IN_TOOL_IDS 中既不在安全也不在系统组的 → 分组表过期,必须报错
        val missingFromGroups = staticIds.filterNot { it in all }
        assertTrue(
            "以下工具不在分组表中(请更新 SAFE_EXECUTE_TOOLS / SYSTEM_ONLY_TOOLS):\n$missingFromGroups",
            missingFromGroups.isEmpty(),
        )
        // B-32: 运行时额外注册(不在静态表)仅做提示,不视为失败(Registrar/MCP 可动态注册)。
        val runtimeExtras = runtimeNames - staticIds
        if (runtimeExtras.isNotEmpty()) {
            println("[info] 运行时额外注册工具(动态/Registrar/MCP): $runtimeExtras")
        }
    }

    /**
     * B-32: 内置 skill 工具一致性护栏 — "定义存在 + execute 可路由"烟雾覆盖。
     *
     * 遍历 [SkillExecutor.BUILT_IN_SKILLS]:
     *  1. 定义存在:每个内置 skill 必须是有效定义(id/name/description 非空,parametersJson 为合法 JSON)。
     *  2. execute 可路由:implementationKotlin 必须命中 SkillExecutor.execute 的 when 专属分支
     *     (即 ∈ [SkillExecutor.ROUTABLE_SKILL_IMPL]),否则会静默落 skill_unknown_impl。
     *
     * 排除已下线的 generate_qr(B-07),避免"内置 skill 全集可路由"断言误伤。
     */
    @Test
    fun `all built-in skill tools are defined and routable`() {
        val skills = io.zer0.muse.tools.SkillExecutor.BUILT_IN_SKILLS
        // B-07: generate_qr 内置 skill 已下线,从"可路由全集"断言中排除(dispatch 分支仍保留兼容旧数据)
        val decommissioned = setOf("generate_qr")
        val definitionsBroken = mutableListOf<String>()
        val notRoutable = mutableListOf<String>()

        for (skill in skills) {
            if (skill.id in decommissioned) continue
            // 1. 定义存在 — 有效 SkillEntity
            if (skill.id.isBlank() || skill.name.isBlank() || skill.description.isBlank() ||
                !isValidJsonObject(skill.parametersJson)
            ) {
                definitionsBroken += "${skill.id}(name='${skill.name}', desc=${skill.description.length}chars, params=${skill.parametersJson})"
                continue
            }
            // 2. execute 可路由 — implementationKotlin 命中 execute 的 when 专属分支
            if (skill.implementationKotlin !in SkillExecutor.ROUTABLE_SKILL_IMPL) {
                notRoutable += "${skill.id} → implementationKotlin='${skill.implementationKotlin}'"
            }
        }

        assertTrue(
            "以下内置 skill 定义缺失/损坏(请检查 SkillExecutor.BUILT_IN_SKILLS):\n${definitionsBroken.joinToString("\n")}",
            definitionsBroken.isEmpty(),
        )
        assertTrue(
            "以下内置 skill 无法被 SkillExecutor.execute 路由(implementationKotlin 不在 ROUTABLE_SKILL_IMPL," +
                "会静默走 skill_unknown_impl):\n${notRoutable.joinToString("\n")}",
            notRoutable.isEmpty(),
        )
    }

    /** B-32: 判断字符串是否为合法的 JSON 对象(用于校验 skill 定义存在)。 */
    private fun isValidJsonObject(json: String): Boolean {
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(
                kotlinx.serialization.json.JsonObject.serializer(),
                json,
            )
        }.isSuccess
    }

    /**
     * 核心工具必须注册(回归保护: 注册表被误删时立刻暴露)。
     */
    @Test
    fun `registry contains all documented built-in tool ids`() {
        val names = io.zer0.muse.tools.ToolRegistry.BUILT_IN_TOOL_IDS.toSet()
        val required = listOf(
            "get_current_time", "calculator", "echo", "get_weather",
            "browser_navigate", "browser_extract",
            "schedule_reminder", "translate",
        )
        val missing = required.filterNot { it in names }
        assertTrue("缺少核心工具: $missing", missing.isEmpty())
    }

    companion object {
        /** 安全工具单工具超时(毫秒)。 */
        private const val SAFE_TOOL_TIMEOUT_MS = 5_000L

        /**
         * B-32: ToolRegistry 自身 init{} 注册的内置工具(与 ToolRegistry.init 一一对应)。
         *
         * CodeExecutionTool(execute_javascript)+ BrowserAutomationTool(browser_* 6 件套)。
         * 其余内置工具由各 Registrar 经 Koin 在 App 启动时注入注册(见本类顶部测试注释)。
         * 此集合用于"从真实 ToolRegistry(context).listTools() 读运行时注册"互验。
         */
        val SELF_REGISTERED_IN_TOOL_REGISTRY: List<String> = listOf(
            "execute_javascript",
            "browser_navigate", "browser_click", "browser_type",
            "browser_extract", "browser_scroll_bottom", "browser_get_html",
        )

        /**
         * 安全工具:纯计算/无系统服务,Robolectric 可真实执行。
         * 新增纯逻辑工具时加入此表(并在 SYSTEM_ONLY_TOOLS 中移除)。
         */
        val SAFE_EXECUTE_TOOLS: List<String> = listOf(
            // 纯计算
            "echo", "calculator",
            // 编解码/哈希
            "url_encode", "url_decode", "base64_encode", "base64_decode",
            "hash_text", "generate_uuid", "random_number", "generate_password",
            "json_pretty",
            // 时间(纯 LocalDateTime)
            "get_current_time",
        )

        /**
         * 系统依赖工具:Robolectric 下不执行(执行会阻塞/无真实能力)。
         * 真机回归由 instrumentation 冒烟覆盖。
         * 新增系统工具时加入此表(保持与 SAFE_EXECUTE_TOOLS 并集 = BUILT_IN_TOOL_IDS)。
         */
        val SYSTEM_ONLY_TOOLS: List<String> = listOf(
            // 网络
            "get_weather", "get_network_info", "get_public_ip", "ping_host", "dns_lookup",
            // 剪贴板/日历/闹钟
            "clipboard_read", "clipboard_write", "screen_time", "calendar_today",
            "add_calendar_event", "set_alarm", "set_timer", "schedule_reminder",
            "cancel_reminder", "list_reminders",
            // 系统/设备
            "open_app", "share_text", "get_location", "get_device_info",
            "get_contacts_count", "get_contacts_list", "send_sms", "add_contact",
            "open_system_setting", "toggle_wifi", "toggle_bluetooth", "send_email",
            "get_battery_info", "get_recent_notifications", "open_url",
            "list_installed_apps",
            // v1.136 系统/设备/编码
            "get_storage_info", "get_memory_info", "get_display_info", "get_cpu_info",
            "get_sensors_list", "get_brightness", "set_brightness", "get_volume",
            "set_volume", "toggle_flashlight", "vibrate", "get_foreground_app",
            "get_wifi_info", "get_bluetooth_devices", "make_phone_call", "open_maps",
            // 资源库/快速记录(依赖 Room DB,Robolectric 需额外配置,归系统组)
            "resource_add", "resource_list", "resource_search", "resource_get",
            "resource_delete", "quick_note_add", "quick_note_list", "quick_note_search",
            "quick_note_get", "quick_note_update", "quick_note_delete", "quick_note_pin",
            // TTS
            "speak_text",
            // 媒体生成(依赖网络 + 供应商)
            "generate_image", "generate_video", "generate_qr_code",
            // 表情包(依赖 SkillExecutor)
            "list_stickers", "send_sticker",
            // 记忆/经验(依赖 DB + LLM)
            "pin_memory", "unpin_memory", "recall_experience", "record_experience",
            "todo_write", "show_card", "notify", "current_status", "subagent_task",
            // JS 沙盒(WebView)
            "execute_javascript",
            // 浏览器(headless WebView)
            "browser_navigate", "browser_click", "browser_type", "browser_extract",
            "browser_scroll_bottom", "browser_get_html",
            // 工作区文件(依赖文件系统)
            "workspace_list", "workspace_read", "workspace_write", "workspace_delete",
            "workspace_mkdir", "workspace_move",
            // 定时任务(依赖 DB)
            "scheduled_task_create", "scheduled_task_list", "scheduled_task_update",
            "scheduled_task_delete", "scheduled_task_execute", "scheduled_task_get_history",
            // 翻译(依赖网络 + LLM)
            "translate",
        )
    }
}
