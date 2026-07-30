package io.zer0.muse.tools

import io.zer0.ai.core.Model

/**
 * v1.0.47 P6-2: 弱工具调用模型检测器。
 *
 * 识别工具调用能力较弱的模型(如 DeepSeek R1/V3 早期版本、部分开源模型),
 * 在 Agent Mode 下对这些模型进行降级处理:
 *  - 禁用并行工具调用(改为串行)
 *  - UI 提示用户当前模型工具调用能力较弱
 *
 * 判定依据:模型 ID 模式匹配。已知弱工具模型通过 [WEAK_TOOL_PATTERNS] 定义。
 * 未匹配的模型默认为 STRONG(正常工具调用)。
 */
object WeakToolUseDetector {

    /**
     * 已知弱工具调用模型的 ID 模式(正则,大小写不敏感)。
     *
     * - DeepSeek R1 系列(reasoning 模型,工具调用不稳定)
     * - DeepSeek V3/v3 早期版本(v3.2 之前并行工具调用有已知问题)
     * - Qwen 早期版本(Qwen2 之前工具调用格式不稳定)
     * - 部分极小模型(mini/tiny/nano 级别,参数量不足以稳定工具调用)
     */
    private val WEAK_TOOL_PATTERNS = listOf(
        Regex("(?i)deepseek-r1"),
        Regex("(?i)deepseek-v3(?!\\.2)"),  // v3 但排除 v3.2
        Regex("(?i)deepseek-chat(?!.+v3\\.2)"),
        Regex("(?i)qwen-?2(?![.\\d])"),     // Qwen2 但非 Qwen2.5+
        Regex("(?i)\\b(mini|tiny|nano)\\b.*\\b(instruct|chat)\\b"),
        Regex("(?i)gemma-?2(?![.\\d])"),    // Gemma2 但非 Gemma3
    )

    /**
     * 检测给定模型是否为弱工具调用模型。
     *
     * @param model 当前选用的模型(null 时返回 false,保守处理)
     * @return true 表示该模型工具调用能力较弱,建议降级
     */
    fun isWeakToolModel(model: Model?): Boolean {
        if (model == null) return false
        // 不支持工具调用的模型不算"弱",而是"不支持"
        if (!model.supportsToolCalling()) return false
        return WEAK_TOOL_PATTERNS.any { it.containsMatchIn(model.id) }
    }

    /**
     * 获取弱工具模型的降级建议文案。
     * 返回 null 表示非弱工具模型,无需提示。
     */
    fun getWeakToolHint(model: Model?): String? {
        // 显式判空,让编译器智能转换 model 为非空
        if (model == null || !isWeakToolModel(model)) return null
        return when {
            model.id.contains(Regex("(?i)deepseek-r1")) ->
                "DeepSeek R1 系列工具调用不稳定,已降级为串行调用"
            model.id.contains(Regex("(?i)deepseek")) ->
                "DeepSeek 早期版本并行工具调用可能异常,已降级为串行调用"
            model.id.contains(Regex("(?i)qwen")) ->
                "Qwen 早期版本工具调用格式不稳定,已降级为串行调用"
            else ->
                "当前模型工具调用能力较弱,已降级为串行调用"
        }
    }
}
