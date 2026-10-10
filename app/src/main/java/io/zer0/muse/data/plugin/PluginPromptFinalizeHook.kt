package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.hook.PromptFinalizeEvent
import io.zer0.muse.hook.PromptFinalizeHook
import io.zer0.muse.hook.PromptFinalizeResult
import io.zer0.muse.tools.script.WebViewSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v2.x（插件 Hook 体系 MVP）：把插件的 JS 函数接入「提示词定稿」Hook 点。
 *
 * 插件侧约定（main.js）：
 * ```js
 * function onPromptFinalize(event) {
 *   // event = { preparedHistory: [ {role, content}, ... ] }
 *   // 返回 { preparedHistory: [...] }（可增删改条目）
 *   return event;
 * }
 * ```
 *
 * 设计：
 *  - 每次调用都跑一次 JS（插件函数可能是动态的）；超时沿用引擎默认（10s）。
 *  - 失败/返回不合法时**保留原历史**（fail-open），不阻断生成。
 *  - role 只接受 user/assistant/system/tool；未知 role 的条目按 assistant 处理。
 */
internal class PluginPromptFinalizeHook(
    private val pluginId: String,
    private val entryCode: String,
    private val configJson: String?,
) : PromptFinalizeHook {

    override val id: String = "plugin:$pluginId:prompt_finalize"
    override val priority: Int = 0
    override val enabled: Boolean = true

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override suspend fun beforeFinalizePrompt(event: PromptFinalizeEvent): PromptFinalizeResult {
        val result =
            runCatching { WebViewSkillEngine().eval(buildCallScript(event), scopeKey = pluginId, pluginConfigJson = configJson) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "插件 prompt_finalize 执行失败(保留原历史): ${e.message}")
                    return fallback(event)
                }
        if (result is SkillEngineResult.Error) {
            Logger.w(TAG, "插件 prompt_finalize 出错(保留原历史): ${result.message}")
            return fallback(event)
        }
        val raw = (result as SkillEngineResult.Success).valueJson
        val parsed = parseHistory(raw)
            ?: run {
                Logger.d(TAG, "插件 prompt_finalize 无有效返回(保留原历史)")
                return fallback(event)
            }
        if (parsed.isEmpty()) {
            Logger.w(TAG, "插件 prompt_finalize 返回空历史(保留原历史,防空上下文)")
            return fallback(event)
        }
        Logger.d(TAG, "插件 prompt_finalize 应用: ${event.preparedHistory.size} → ${parsed.size} 条")
        return PromptFinalizeResult(parsed)
    }

    /** 拼一次 JS 调用：加载入口代码 → 注入事件 → 调 onPromptFinalize → 序列化返回。 */
    private fun buildCallScript(event: PromptFinalizeEvent): String {
        val historyJson = event.preparedHistory.joinToString(",") { msg ->
            val contentJson = AppJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(msg.content))
            """{"role":"${msg.role.wireName()}","content":$contentJson}"""
        }
        return buildString {
            append(entryCode)
            append("\n;(function(){ var __ev = {preparedHistory: [")
            append(historyJson)
            append("]}; var __out;")
            append("try { __out = (typeof onPromptFinalize === 'function') ? onPromptFinalize(__ev) : null; }")
            append("catch (e) { __out = { __error: String(e && e.message || e) }; }")
            append("return JSON.stringify(__out === undefined || __out === null ? null : __out); })();")
        }
    }

    /** 解析插件返回的历史数组；结构不合法返回 null（调用方保留原历史）。 */
    private fun parseHistory(raw: String?): List<UIMessage>? {
        val out = runCatching { AppJson.parseToJsonElement(raw ?: "null") }.getOrNull()
        val arr = (out as? JsonObject)?.get("preparedHistory") as? JsonArray
        return arr
            ?.mapNotNull { it.jsonObject.toUIMessageOrNull() }
            ?.takeIf { mapped -> mapped.isNotEmpty() }
    }

    private fun fallback(event: PromptFinalizeEvent): PromptFinalizeResult = PromptFinalizeResult(event.preparedHistory)

    private fun JsonObject.toUIMessageOrNull(): UIMessage? {
        val role = this["role"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val content = this["content"]?.jsonPrimitive?.contentOrNull
        if (role == null || content == null) return null
        val messageRole = when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "tool" -> MessageRole.TOOL
            else -> MessageRole.ASSISTANT
        }
        return UIMessage(role = messageRole, content = content)
    }

    private fun MessageRole.wireName(): String = when (this) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.SYSTEM -> "system"
        MessageRole.TOOL -> "tool"
    }

    private companion object {
        const val TAG = "PluginHook"
    }
}
