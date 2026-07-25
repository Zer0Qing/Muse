package io.zer0.muse.transformer

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRegex
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.97: 正则替换 Transformer — 对消息文本应用助手级正则规则。
 *
 * 在消息管道中执行,位于 PlaceholderTransformer 之后、发送给 LLM 之前。
 * 仅对文本内容做替换,不影响消息结构。
 *
 * 注意:本 object 是工具类,非 Transformer 接口实现。实际 Transformer 实现见 RegexMessageTransformer。
 */
object RegexTransformer {

    private const val TAG = "RegexTransformer"

    /** JSON 序列化器。 */
    private val serializer = ListSerializer(AssistantRegex.serializer())

    /**
     * M-REG1: 正则编译缓存,以规则字符串为 key。
     * 规则集通常稳定(用户配置后多轮复用),缓存编译结果避免每条消息都重新编译 Regex。
     * 用 ConcurrentHashMap 保证多协程并发访问安全。
     */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    /**
     * 从 AssistantEntity 解析正则规则列表。
     */
    fun parseRules(assistant: AssistantEntity): List<AssistantRegex> {
        return parseRulesFromJson(assistant.regexRulesJson)
    }

    /**
     * 从 JSON 字符串解析正则规则列表(供 UI 直接调用,无需构造完整 AssistantEntity)。
     */
    fun parseRulesFromJson(json: String): List<AssistantRegex> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            AppJson.decodeFromString(serializer, json)
        } catch (e: Exception) {
            Logger.w(TAG, "解析正则规则失败", e)
            emptyList()
        }
    }

    /**
     * 序列化正则规则列表为 JSON 字符串(供持久化)。
     */
    fun serializeRules(rules: List<AssistantRegex>): String {
        return AppJson.encodeToString(serializer, rules)
    }

    /**
     * 对文本应用正则替换规则。
     *
     * @param text 原始文本
     * @param rules 正则规则列表
     * @param scope 当前消息范围:"user" 或 "assistant"
     * @param visualOnly true=仅过滤 visualOnly 规则;false=仅过滤非 visualOnly 规则
     * @return 替换后的文本
     */
    fun applyRules(
        text: String,
        rules: List<AssistantRegex>,
        scope: String,
        visualOnly: Boolean = false,
    ): String {
        if (rules.isEmpty() || text.isEmpty()) return text

        var result = text
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.findRegex.isBlank()) continue
            // 检查范围匹配
            val scopeMatch = when (rule.affectingScope) {
                "both" -> true
                scope -> true
                else -> false
            }
            if (!scopeMatch) continue
            // 检查 visualOnly 匹配
            if (rule.visualOnly != visualOnly) continue

            result = try {
                // M-REG1: 复用缓存的 Regex 实例,避免每条消息都重新编译(rule.findRegex 通常稳定)
                val regex = regexCache.getOrPut(rule.findRegex) { Regex(rule.findRegex) }
                regex.replace(result, rule.replaceString)
            } catch (e: Exception) {
                Logger.w(TAG, "正则替换失败: ${rule.name}(${rule.findRegex})", e)
                result
            }
        }
        return result
    }
}
