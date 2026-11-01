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
            else -> null
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
    }
}
