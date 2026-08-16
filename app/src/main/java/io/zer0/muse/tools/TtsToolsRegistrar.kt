package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b 拆域：TTS 工具注册器。
 *
 * 注册 speak_text。实现位于 [TtsToolsImpl.kt]。
 */
class TtsToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = TtsToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "speak_text",
                // v1.0.75 fix (工具审查 02): 补触发场景与返回说明
                description = "用系统 TTS 把文本朗读出来(用户要求'读出来/念一下/播报'时调用)。返回朗读是否成功的文字确认,不返回音频文件。",
                parameters = mapOf(
                    "text" to "必填,要朗读的文本",
                    "language" to "可选,语言代码,如 zh-CN/en-US,默认跟随系统",
                    "rate" to "可选,语速倍率,如 0.8/1.0/1.5,默认 1.0",
                ),
                required = setOf("text"),
                category = "built-in",
                parameterTypes = mapOf("rate" to "number"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.speak(args) }
    }
}
