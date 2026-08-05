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
                description = "调用语言模型翻译文本。",
                parameters = mapOf(
                    "text" to "必填,要翻译的原文",
                    "target_language" to "必填,目标语言(中文/English/日本語 等)",
                    "source_language" to "可选,源语言,不填则自动检测",
                    "style" to "可选,通用/学术/商务/口语化/润色/简洁",
                ),
                required = setOf("text", "target_language"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execTranslate(args) }
    }
}
