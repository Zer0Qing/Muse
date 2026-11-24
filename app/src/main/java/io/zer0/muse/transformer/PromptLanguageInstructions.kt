package io.zer0.muse.transformer

/**
 * 系统提示词里「思考语言」「回复语言」的指令片段。
 *
 * 从 `SystemPromptAssembler.kt` 切出——纯函数，只吃 locale 字符串吐指令文本，
 * 与组装器实例状态无关，各自可独立单测。
 */

/** Whether [locale] belongs to the Chinese language family (zh / zh-CN / zh-TW / zh-Hans / zh-Hant). */
internal fun isChineseLocale(locale: String): Boolean {
    val normalized = locale.trim().replace('_', '-').lowercase()
    return normalized.isEmpty() || normalized == "zh" || normalized.startsWith("zh-")
}

/** Stable labels used to constrain visible reasoning language to the app interface locale. */
internal fun thinkingLanguageName(locale: String): String = when (locale.trim().replace('_', '-').lowercase()) {
    "zh", "zh-cn", "zh-hans", "zh-sg" -> "中文"
    "zh-tw", "zh-hk", "zh-mo", "zh-hant" -> "繁體中文"
    "en", "en-us", "en-gb" -> "English"
    "ja", "ja-jp" -> "日本語"
    "ko", "ko-kr" -> "한국어"
    "es", "es-es", "es-419" -> "Español"
    "pt", "pt-br", "pt-pt" -> "Português"
    "ru", "ru-ru" -> "Русский"
    else -> "the configured system language"
}

/** Prompt contract for reasoning-capable providers and tool continuation rounds. */
internal fun thinkingInstruction(locale: String): String {
    val thinkingLang = thinkingLanguageName(locale)
    return if (isChineseLocale(locale)) {
        "思考语言\n" +
            "- 内部推理(reasoning_content)和思考过程必须使用$thinkingLang\n" +
            "- 开启思考且模型支持 reasoning 时，每一轮回复和每一轮工具调用前都先输出思考内容\n" +
            "- 模型不支持 reasoning 或未返回思考内容时，不要伪造思考\n" +
            "- 回复正文使用与用户提问一致的语言"
    } else {
        "Thinking language\n" +
            "- All internal reasoning, thinking process, and analysis must use $thinkingLang\n" +
            "- When thinking is enabled and the model supports reasoning, emit reasoning before every response and before each tool call round\n" +
            "- Never fabricate reasoning when the model does not support it or does not return it\n" +
            "- Keep the final answer in the user's language when it differs"
    }
}

/** 正文语言契约：与"思考语言"分开，按应用 locale 本地化，避免英文界面被中文提示词带偏。 */
internal fun replyLanguageInstruction(locale: String): String {
    val uiLang = thinkingLanguageName(locale)
    return if (isChineseLocale(locale)) {
        "回复语言\n" +
            "- 正文使用用户本轮消息所用的语言；不要因为系统提示词、工具说明或历史消息是其他语言就切换\n" +
            "- 判断不出用户语言时，使用应用界面语言：$uiLang\n" +
            "- 用户明确指定语言时，以用户本轮的指定为准"
    } else {
        "Reply language\n" +
            "- Reply in the language of the user's current message; do not switch languages just because system prompts, tool descriptions, or earlier messages use another language\n" +
            "- If the user's language is unclear, fall back to the app interface language: $uiLang\n" +
            "- If the user explicitly requests a specific language in this turn, follow their request"
    }
}
