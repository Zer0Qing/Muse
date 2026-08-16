package io.zer0.muse.tools

/**
 * P1-3b 拆域：翻译工具注册器。
 *
 * 注册 translate。实现位于 [TranslateToolsImpl.kt]。
 */
class TranslateToolsRegistrar(
    private val toolRegistry: ToolRegistry,
) {
    private val impl = TranslateToolsImpl()

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "translate",
                // v1.0.75 fix (工具审查 02): 补触发场景(用户明确要求翻译时才用)
                description = "翻译文本(走独立翻译流程,质量比直接回答更稳定)。用户明确要求翻译/转换语言时调用。返回翻译结果,不含解释。",
                parameters = mapOf(
                    "text" to "必填,要翻译的原文",
                    "target_language" to "必填,目标语言(中文/English/日本語 等)",
                    "source_language" to "可选,源语言,不填则自动检测",
                    "style" to "可选,通用/学术/商务/口语化(翻译风格;润色/简洁需求请在 prompt 中描述)",
                ),
                required = setOf("text", "target_language"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execTranslate(args) }
    }
}
