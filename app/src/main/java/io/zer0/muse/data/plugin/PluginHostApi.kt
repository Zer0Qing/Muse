package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.tools.ToolCallGate
import io.zer0.muse.tools.script.SkillBridge
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

/**
 * 宿主侧状态写入类动作的统一入口（当前是 `config_set`；后续 `chat_append` 等也走这里）。
 *
 * 为什么不塞进 [SkillBridge]：那边管的是"设备能力"（文件、网络、剪贴板、通知），
 * 而这些动作要的是插件注册表与配置存储；混在一起会让 SkillBridge 依赖整个管理器，
 * 也就没法在 JVM 上单独测。
 *
 * **授权不在这里判**：调用方必须把账本算出的动作集传进来（[allowedActions]），
 * 本类只在"已被允许"的前提下做参数与语义校验 —— 少传一步就是一个绕过口。
 */
class PluginHostApi(
    private val writeConfig: suspend (pluginId: String, key: String, value: JsonElement?) -> Unit,
    /** 宿主未接入时 `tool_invoke` 一律拒绝（fail-closed），不静默降级成"当作没这个动作"。 */
    private val toolBridge: PluginToolInvokeBridge? = null,
    /** 会话读取通道；[sessionId] 由宿主链路注入，插件自己传的一律不用。 */
    private val chatBridge: PluginChatBridge? = null,
    private val sessionId: String = PluginChatBridge.SESSION_PLACEHOLDER,
    /** 模型调用通道（`model_call`）。缺通道时动作一律拒绝。 */
    private val modelBridge: PluginModelBridge? = null,
    /** 写会话通道（`app_insert_message`）。缺通道时动作一律拒绝。 */
    private val messageBridge: PluginMessageBridge? = null,
    /** 外观通道（`app_set_theme_mode`）。缺通道时动作一律拒绝。 */
    private val appearanceBridge: PluginAppearanceBridge? = null,
) {

    /** 返回 null 表示这个动作不归本类管，调用方继续交给 [SkillBridge]。 */
    suspend fun handle(
        pluginId: String,
        manifest: PluginManifest,
        valueJson: String,
        allowedActions: Set<String>,
    ): SkillBridge.HandleResult? {
        val obj = runCatching { AppJson.parseToJsonElement(valueJson) as? JsonObject }.getOrNull()
        val isBridge = (obj?.get("__bridge__") as? JsonPrimitive)?.booleanOrNull == true
        val action = (obj?.get("action") as? JsonPrimitive)?.contentOrNull
        return when {
            !isBridge -> null
            action == ACTION_CONFIG_SET -> handleConfigSet(pluginId, manifest, obj, allowedActions)
            action == ACTION_TOOL_INVOKE -> handleToolInvoke(pluginId, obj, allowedActions)
            action == ACTION_CHAT_READ -> handleChatRead(obj, allowedActions)
            action == ACTION_MODEL_CALL -> handleModelCall(obj, allowedActions)
            action == ACTION_APP_NAVIGATE -> handleAppNavigate(obj, allowedActions)
            action == ACTION_APP_INSERT_MESSAGE -> handleAppInsertMessage(obj, allowedActions)
            action == ACTION_APP_SET_THEME_MODE -> handleAppSetThemeMode(obj, allowedActions)
            action == ACTION_APP_SET_SETTING -> handleAppSetSetting(obj, allowedActions)
            else -> null
        }
    }

    /**
     * 让宿主跳到某个页面（`app.navigate`）。
     *
     * 只做两件事：校验目标、投递给导航总线。**不自己执行导航** —— 导航必须发生在
     * 有 Activity 的地方；插件跑在后台协程里，那里没有界面可跳。
     */
    private fun handleAppNavigate(obj: JsonObject, allowedActions: Set<String>): SkillBridge.HandleResult {
        if (ACTION_APP_NAVIGATE !in allowedActions) return SkillBridge.HandleResult.Failure(notAuthorized())
        val target = (obj["params"] as? JsonObject)?.get("target")?.let { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }
        return when (val parsed = PluginAppNavigation.parse(target)) {
            is PluginAppNavigation.Parsed.Invalid -> SkillBridge.HandleResult.Failure(parsed.reason)
            is PluginAppNavigation.Parsed.Ok -> {
                if (AppNavigationBus.request(parsed.uri)) {
                    SkillBridge.HandleResult.Output("{\"ok\":true,\"target\":\"${parsed.uri}\"}")
                } else {
                    // 没有订阅者说明宿主还没起来：如实说"没送到"，别假装成功
                    SkillBridge.HandleResult.Failure("当前没有可用的界面来执行跳转（应用可能还没进入前台）")
                }
            }
        }
    }

    /**
     * 往当前会话里写一条消息（`app.messages`）。
     *
     * 会话 id **只认宿主注入的那一个**：插件参数里的 `session_id` 一律不读 ——
     * 否则一个插件就能往任意会话里塞话，而用户会以为那是助手说的。
     */
    private suspend fun handleAppInsertMessage(
        obj: JsonObject,
        allowedActions: Set<String>,
    ): SkillBridge.HandleResult {
        if (ACTION_APP_INSERT_MESSAGE !in allowedActions) return SkillBridge.HandleResult.Failure(notAuthorized())
        // 构造参数优先，其次读进程级持有：两者都没有才算"未接入"。
        // 双保险的理由是这条通道的安装点在 Application 启动流程里，
        // 而调用方可能来自不同装配路径（工具、命令、界面提交）—— 漏传一处就变成"动作永远不可用"。
        val bridge = messageBridge ?: PluginMessageChannels.current
            ?: return SkillBridge.HandleResult.Failure("宿主未接入消息写入通道，app_insert_message 不可用")
        val params = obj["params"] as? JsonObject
        val text = (params?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val role = (params?.get("role") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (val appended = bridge.append(sessionId, role, text)) {
            is PluginMessageBridge.Appended.Rejected -> SkillBridge.HandleResult.Failure(appended.reason)
            is PluginMessageBridge.Appended.Ok ->
                SkillBridge.HandleResult.Output("{\"ok\":true,\"messageId\":\"${appended.messageId}\"}")
        }
    }

    /** 切明暗模式（`app.theme`）。只认 light / dark / system，具体皮肤留给用户自己挑。 */
    private suspend fun handleAppSetThemeMode(
        obj: JsonObject,
        allowedActions: Set<String>,
    ): SkillBridge.HandleResult {
        if (ACTION_APP_SET_THEME_MODE !in allowedActions) return SkillBridge.HandleResult.Failure(notAuthorized())
        val bridge = appearanceBridge ?: PluginAppearanceChannels.current
            ?: return SkillBridge.HandleResult.Failure("宿主未接入外观通道，app_set_theme_mode 不可用")
        val mode = (obj["params"] as? JsonObject)?.get("mode")?.let { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }
        return when (val applied = bridge.setMode(mode)) {
            is PluginAppearanceBridge.Applied.Rejected -> SkillBridge.HandleResult.Failure(applied.reason)
            is PluginAppearanceBridge.Applied.Ok ->
                SkillBridge.HandleResult.Output("{\"ok\":true,\"mode\":\"${mode?.trim()}\"}")
        }
    }

    /** 写白名单里的设置项（`app.settings`）。只开放外观类、可逆的四项。 */
    private suspend fun handleAppSetSetting(
        obj: JsonObject,
        allowedActions: Set<String>,
    ): SkillBridge.HandleResult {
        if (ACTION_APP_SET_SETTING !in allowedActions) return SkillBridge.HandleResult.Failure(notAuthorized())
        val bridge = appearanceBridge ?: PluginAppearanceChannels.current
            ?: return SkillBridge.HandleResult.Failure("宿主未接入外观通道，app_set_setting 不可用")
        val params = obj["params"] as? JsonObject
        val key = (params?.get("key") as? JsonPrimitive)?.contentOrNull
        val value = (params?.get("value") as? JsonPrimitive)?.contentOrNull
        return when (val applied = bridge.setSetting(key, value)) {
            is PluginAppearanceBridge.Applied.Rejected -> SkillBridge.HandleResult.Failure(applied.reason)
            is PluginAppearanceBridge.Applied.Ok ->
                SkillBridge.HandleResult.Output("{\"ok\":true,\"key\":\"${key?.trim()}\"}")
        }
    }

    private suspend fun handleConfigSet(
        pluginId: String,
        manifest: PluginManifest,
        obj: JsonObject,
        allowedActions: Set<String>,
    ): SkillBridge.HandleResult = when {
        ACTION_CONFIG_SET !in allowedActions -> SkillBridge.HandleResult.Failure(notAuthorized())
        else -> writeConfigValue(pluginId, manifest, obj["params"] as? JsonObject)
    }

    /**
     * 插件调用其它工具：**同一套审批闸门**（[ToolCallGate]），不是第二条链。
     *
     * 深度限 1：被调用的工具如果本身是插件工具，它里面再发起 `tool_invoke` 会被拒 ——
     * 否则"插件 A 调 B 调 C"既能把审批拖成一串，也可能绕开对 A 的那次判定。
     */
    private suspend fun handleToolInvoke(
        pluginId: String,
        obj: JsonObject,
        allowedActions: Set<String>,
    ): SkillBridge.HandleResult {
        val params = obj["params"] as? JsonObject
        val toolName = (params?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()
        val argumentsJson = params?.get("arguments")?.toString()?.takeIf { it != "null" } ?: "{}"
        val bridge = toolBridge
        val problem = when {
            ACTION_TOOL_INVOKE !in allowedActions ->
                "动作 'tool_invoke' 未获授权：插件需在清单声明能力 '$CAPABILITY_TOOL_INVOKE'，" +
                    "并在 设置 → 工具与扩展 → 插件 → 权限 中批准"
            coroutineContext[PluginToolCallMarker] != null ->
                "工具调用链已达深度上限 1：本次调用由插件发起，其中的工具不得再触发 tool_invoke"
            bridge == null -> "宿主未接入工具调用通道，tool_invoke 不可用"
            toolName.isNullOrBlank() -> "tool_invoke 缺少 name 参数（要调用的工具名）"
            else -> null
        }
        if (problem != null) return SkillBridge.HandleResult.Failure(problem)
        return runToolInvoke(requireNotNull(bridge), requireNotNull(toolName), pluginId, argumentsJson)
    }

    /**
     * `chat_read`：读**当前这次调用所在**的会话最近若干条。
     *
     * 两道拒绝都是隐私相关的，不是可有可无的校验：
     *  - 没有真实会话（占位 id）时必须拒 —— 否则插件会把"读历史"落到一个不存在或别人的会话上；
     *  - 行数由宿主夹紧，插件不能一次要走整个会话。
     */
    private suspend fun handleChatRead(obj: JsonObject, allowedActions: Set<String>): SkillBridge.HandleResult {
        if (ACTION_CHAT_READ !in allowedActions) {
            return SkillBridge.HandleResult.Failure(
                "动作 'chat_read' 未获授权：插件需在清单声明能力 '$CAPABILITY_CHAT_READ'，" +
                    "并在 设置 → 工具与扩展 → 插件 → 权限 中批准（读取聊天内容属隐私面）",
            )
        }
        val bridge = chatBridge
            ?: return SkillBridge.HandleResult.Failure("宿主未接入会话读取通道，chat_read 不可用")
        if (sessionId.isBlank() || sessionId == PluginChatBridge.SESSION_PLACEHOLDER) {
            return SkillBridge.HandleResult.Failure(
                "本次调用没有绑定真实会话，无法读取历史；请在一次会话内的对话中调用该插件",
            )
        }
        val params = obj["params"] as? JsonObject
        val wanted = (params?.get("limit") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: DEFAULT_CHAT_LIMIT
        val limit = wanted.coerceIn(1, PluginChatBridge.MAX_LINES)
        return runCatching {
            SkillBridge.HandleResult.Output(PluginChatBridge.render(bridge.readRecent(sessionId, limit)))
        }.getOrElse { SkillBridge.HandleResult.Failure("chat_read 读取失败: ${it.message}") }
    }

    /**
     * `model_call`：让插件用用户已配置的模型做一次生成。
     *
     * 成本面，三道闸：能力未授予直接拒；输入超长直接拒（不静默截断——被截掉的半边提示
     * 会让模型给出看着合理其实错的答案）；输出长度封顶由桥本身负责。
     */
    private suspend fun handleModelCall(obj: JsonObject, allowedActions: Set<String>): SkillBridge.HandleResult {
        val params = obj["params"] as? JsonObject
        val prompt = (params?.get("prompt") as? JsonPrimitive)?.contentOrNull
        val bridge = modelBridge
        val problem = when {
            ACTION_MODEL_CALL !in allowedActions ->
                "动作 'model_call' 未获授权：插件需在清单声明能力 '$CAPABILITY_MODEL_CALL'（会计入你的模型用量），" +
                    "并在 设置 → 工具与扩展 → 插件 → 权限 中批准"
            bridge == null -> "宿主未接入模型调用通道，model_call 不可用"
            prompt.isNullOrBlank() -> "model_call 缺少 prompt 参数"
            prompt.length > PluginModelBridge.MAX_PROMPT_CHARS ->
                "model_call 输入 ${prompt.length} 字超过上限(${PluginModelBridge.MAX_PROMPT_CHARS} 字)：" +
                    "请自行先缩写，宿主不静默截断——半截提示会让模型给出看着合理其实错的结果"
            else -> null
        }
        if (problem != null) return SkillBridge.HandleResult.Failure(problem)
        val system = (params?.get("system") as? JsonPrimitive)?.contentOrNull
        val maxTokens = ((params?.get("maxTokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: PluginModelBridge.DEFAULT_MAX_TOKENS)
            .coerceIn(1, PluginModelBridge.MAX_TOKENS_CAP)
        return runCatching { requireNotNull(bridge).complete(prompt.orEmpty(), system, maxTokens) }.fold(
            onSuccess = { text ->
                val capped = text.take(PluginModelBridge.MAX_OUTPUT_CHARS)
                SkillBridge.HandleResult.Output(
                    buildJsonObject {
                        put("text", JsonPrimitive(capped))
                        put("truncated", JsonPrimitive(text.length > capped.length))
                    }.toString(),
                )
            },
            onFailure = { SkillBridge.HandleResult.Failure("model_call 失败：${it.message ?: it.javaClass.simpleName}") },
        )
    }

    /** 先审批、后执行：审批走 [ToolCallGate]（与主会话同一条链），执行在标记上下文里进行。 */
    private suspend fun runToolInvoke(
        bridge: PluginToolInvokeBridge,
        toolName: String,
        pluginId: String,
        argumentsJson: String,
    ): SkillBridge.HandleResult {
        val outcome = bridge.gate(toolName, "plugin:$pluginId:${System.nanoTime()}", argumentsJson)
        if (!outcome.allowed) return SkillBridge.HandleResult.Failure(outcome.reason)
        return withContext(PluginToolCallMarker(pluginId)) {
            runCatching { bridge.execute(toolName, argumentsJson) }.fold(
                onSuccess = { SkillBridge.HandleResult.Output(it) },
                onFailure = { SkillBridge.HandleResult.Failure("tool_invoke 执行失败: ${it.message}") },
            )
        }
    }

    private suspend fun writeConfigValue(
        pluginId: String,
        manifest: PluginManifest,
        params: JsonObject?,
    ): SkillBridge.HandleResult {
        val (request, problem) = parseConfigSet(manifest, params)
        if (problem != null) return SkillBridge.HandleResult.Failure(problem)
        val target = requireNotNull(request)
        return runCatching { writeConfig(pluginId, target.item.key, target.value) }.fold(
            onSuccess = { SkillBridge.HandleResult.Output("""{"ok":true,"key":${JsonPrimitive(target.item.key)}}""") },
            onFailure = { SkillBridge.HandleResult.Failure("config_set 写入失败: ${it.message}") },
        )
    }

    /**
     * 一次校验完所有失败原因，返回 `(请求, null)` 或 `(null, 可读原因)`。
     *
     * 写成"算出一个 problem"而不是层层 return，是为了让**每一条拒绝都必须被读到**：
     * 层层早退最容易漏掉某一条（而漏掉的那条只有插件作者会撞上）。
     */
    private fun parseConfigSet(
        manifest: PluginManifest,
        params: JsonObject?,
    ): Pair<ConfigSetRequest?, String?> {
        val key = (params?.get("key") as? JsonPrimitive)?.contentOrNull
        val value = params?.get("value")
        val item = key?.let { k -> manifest.contributes?.configuration?.firstOrNull { it.key == k } }
        val problem = when {
            key == null -> "config_set 缺少 key 参数"
            value == null -> "config_set 缺少 value 参数"
            item == null -> "config_set 只能写插件自己声明过的配置项；可写的键：" +
                (manifest.contributes?.configuration?.map { it.key }?.joinToString("、") ?: "（该插件未声明配置项）")
            item.secret -> "配置项 '${item.key}' 是敏感项（凭据类），只能由用户在 设置 → 插件 → 配置 里填写，" +
                "插件不得自行改写"
            else -> typeMismatchMessage(item, value)
        }
        return when {
            problem != null -> null to problem
            else -> ConfigSetRequest(requireNotNull(item), requireNotNull(value)) to null
        }
    }

    /** 值必须符合声明类型；下拉项还必须落在候选值里。返回 null 表示合法。 */
    private fun typeMismatchMessage(item: ConfigItem, value: JsonElement): String? {
        val primitive = value as? JsonPrimitive
            ?: return "配置项 '${item.key}' 需要 ${item.type.name} 类型的值，收到的是对象或数组"
        val ok = when (item.type) {
            ConfigItemType.string -> primitive.isString
            ConfigItemType.boolean -> primitive.booleanOrNull != null ||
                primitive.contentOrNull?.toBooleanStrictOrNull() != null
            ConfigItemType.number -> primitive.contentOrNull?.toBigDecimalOrNull() != null
            ConfigItemType.select -> {
                val text = primitive.contentOrNull
                text != null && item.options.map { it.value }.contains(text)
            }
        }
        return if (ok) {
            null
        } else {
            when (item.type) {
                ConfigItemType.select -> "配置项 '${item.key}' 只能取 ${item.options.joinToString(" / ") { it.value }}"
                else -> "配置项 '${item.key}' 需要 ${item.type.name} 类型的值"
            }
        }
    }

    private fun notAuthorized(): String =
        "动作 'config_set' 未获授权：插件需在清单声明能力 '$CAPABILITY_CONFIG_WRITE'，" +
            "并在 设置 → 工具与扩展 → 插件 → 权限 中批准"

    /** 校验通过的写入请求。 */
    private data class ConfigSetRequest(val item: ConfigItem, val value: JsonElement)

    companion object {
        const val ACTION_CONFIG_SET = "config_set"
        const val CAPABILITY_CONFIG_WRITE = "config.write"
        const val ACTION_TOOL_INVOKE = "tool_invoke"
        const val CAPABILITY_TOOL_INVOKE = "host.tool.invoke"
        const val ACTION_CHAT_READ = "chat_read"
        const val CAPABILITY_CHAT_READ = "chat.read"
        const val ACTION_MODEL_CALL = "model_call"

    /** 跳转（`app.navigate`）。 */
    const val ACTION_APP_NAVIGATE = "app_navigate"

    /** 往会话里写消息（`app.messages`）。 */
    const val ACTION_APP_INSERT_MESSAGE = "app_insert_message"

    /** 切明暗模式（`app.theme`）。 */
    const val ACTION_APP_SET_THEME_MODE = "app_set_theme_mode"

    /** 写白名单里的设置项（`app.settings`）。 */
    const val ACTION_APP_SET_SETTING = "app_set_setting"
        const val CAPABILITY_MODEL_CALL = "host.model.call"
        const val DEFAULT_CHAT_LIMIT = 10
    }
}
