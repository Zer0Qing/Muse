package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.plugin.AuthoredPluginRequest
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 助手自写插件工具实现（author_plugin）。
 *
 * 该工具是「模型产出代码」与「用户信任决定」之间唯一的桥：模型提交文本，
 * 宿主构建 ZIP 并只落库为未签名草稿（禁用、未确认、不可执行）；要真正生效，
 * 用户必须在设置→插件管理中审阅工具清单后点「签名并启用」，由本机作者密钥签名。
 *
 * 失败一律返回 `{"error": "中文原因"}`，不把异常抛给编排层。
 */
class PluginAuthoringToolsImpl(
    private val context: Context,
    private val pluginManager: PluginManager?,
) {

    suspend fun authorPlugin(args: Map<String, String>): String {
        val manager = pluginManager
            ?: return failure(context.getString(R.string.plugin_author_not_configured))
        val tools = parseTools(args["tools"])
            ?: return failure(context.getString(R.string.plugin_author_invalid_tools))
        val capabilities = parseCapabilities(args["capabilities"])
            ?: return failure(context.getString(R.string.plugin_author_invalid_capabilities))
        val request = AuthoredPluginRequest(
            id = args["id"]?.trim().orEmpty(),
            name = args["name"]?.trim().orEmpty(),
            description = args["description"]?.trim().orEmpty(),
            version = args["version"]?.trim().orEmpty(),
            code = args["code"].orEmpty(),
            tools = tools,
            capabilities = capabilities,
        )
        return manager.createAuthoredDraft(request).fold(
            onSuccess = { draft ->
                context.getString(
                    R.string.plugin_author_draft_created,
                    draft.name,
                    draft.id,
                    draft.version,
                    draft.tools.joinToString(", ") { it.name },
                )
            },
            onFailure = { error ->
                Logger.w("PluginAuthoring", "author_plugin 草稿创建失败: ${error.message}", error)
                failure(error.message ?: context.getString(R.string.skill_unknown_error))
            },
        )
    }

    /** 解析模型提交的工具数组；形状不合法返回 null（由调用方给出统一中文提示）。 */
    private fun parseTools(raw: String?): List<ToolDeclaration>? {
        if (raw.isNullOrBlank()) return null
        return resultOf {
            AppJson.decodeFromString(JsonArray.serializer(), raw).map { element ->
                val tool = element as? JsonObject ?: error("工具项必须是对象")
                ToolDeclaration(
                    name = tool.stringField("name"),
                    description = tool.stringField("description"),
                    parametersJson = tool.stringField("parametersJson"),
                    requiredJson = tool.stringField("requiredJson"),
                    functionName = tool.stringField("functionName"),
                )
            }
        }.getOrNull()
    }

    /** 解析能力数组；缺省视为不声明能力，非字符串元素视为非法。 */
    private fun parseCapabilities(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return emptyList()
        return resultOf {
            AppJson.decodeFromString(JsonArray.serializer(), raw).map { element ->
                element.jsonPrimitive.content
            }
        }.getOrNull()
    }

    /** 读取对象里的字符串字段；非字符串/缺失一律按空串处理，交给规则层给出可读原因。 */
    private fun JsonObject.stringField(name: String): String =
        this[name]?.jsonPrimitive?.content.orEmpty()

    private fun failure(reason: String): String = buildJsonObject {
        put("error", reason)
    }.toString()
}
