package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.tools.script.SkillBridge
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
        val mine = isBridge && action == ACTION_CONFIG_SET
        return when {
            !mine -> null
            ACTION_CONFIG_SET !in allowedActions -> SkillBridge.HandleResult.Failure(notAuthorized())
            else -> writeConfigValue(pluginId, manifest, obj?.get("params") as? JsonObject)
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
    }
}
