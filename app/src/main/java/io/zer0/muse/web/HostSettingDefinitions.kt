package io.zer0.muse.web

import java.util.Locale

/** 设置项的协议类型，字符串形式即下发给网页端的 `type`。 */
internal enum class HostSettingType(val wireName: String) {
    BOOLEAN("boolean"),
    NUMBER("number"),
    ENUM("enum"),
    TEXT("text"),
}

/**
 * 一个设置项的完整定义：键、类型、可写性、枚举候选（值 id → 显示名的字符串资源名）、数值范围。
 *
 * 下发（[HostSettingValue]）与写入白名单都由这一份定义派生，不另维护键表：
 * 新增一项只改这里，白名单、校验、快照三处自动跟上。
 */
internal data class HostSettingDefinition(
    val key: String,
    val type: HostSettingType,
    /**
     * 是否接受写入。false 表示只用于展示（当前音色名、是否已配置模型这类派生值），
     * 往它写会被明确拒绝而不是静默丢弃。
     */
    val writable: Boolean = true,
    /** enum 候选：值 id → 显示名的字符串资源名。 */
    val options: List<Pair<String, String>> = emptyList(),
    /** number 的下界/上界/步进（含边界）。 */
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    /** text 的最大长度；0 表示不限。 */
    val maxLength: Int = 0,
) {

    /** 把外部提交的字符串按本项类型规范化；失败时带上可直接展示的原因。 */
    fun parse(raw: String?): HostSettingParseResult {
        val text = raw?.trim().orEmpty()
        return when (type) {
            HostSettingType.BOOLEAN -> when (text.lowercase(Locale.ROOT)) {
                "true" -> HostSettingParseResult.Ok("true")
                "false" -> HostSettingParseResult.Ok("false")
                else -> HostSettingParseResult.Rejected("settings.update rejected: '$key' expects true/false")
            }

            HostSettingType.NUMBER -> parseNumber(text)
            HostSettingType.ENUM -> {
                val hit = options.firstOrNull { it.first == text }
                if (hit == null) {
                    val allowed = options.joinToString(", ") { it.first }
                    HostSettingParseResult.Rejected("settings.update rejected: '$key' expects one of: $allowed")
                } else {
                    HostSettingParseResult.Ok(hit.first)
                }
            }

            HostSettingType.TEXT -> when {
                maxLength > 0 && text.length > maxLength -> HostSettingParseResult.Rejected(
                    "settings.update rejected: '$key' is longer than $maxLength characters",
                )
                else -> HostSettingParseResult.Ok(text)
            }
        }
    }

    private fun parseNumber(text: String): HostSettingParseResult {
        val number = text.toDoubleOrNull()
            ?: return HostSettingParseResult.Rejected("settings.update rejected: '$key' expects a number")
        if (!number.isFinite()) {
            return HostSettingParseResult.Rejected("settings.update rejected: '$key' expects a finite number")
        }
        if ((min != null && number < min) || (max != null && number > max)) {
            return HostSettingParseResult.Rejected(
                "settings.update rejected: '$key' out of range [${format(min)}, ${format(max)}]",
            )
        }
        return HostSettingParseResult.Ok(canonicalNumber(text, number))
    }

    /** 回写用十进制文本：整数范围且值为整数时去掉小数点，避免 55 变成 55.0。 */
    private fun canonicalNumber(text: String, number: Double): String {
        val integralRange = isIntegral(min) && isIntegral(max)
        return if (integralRange && number % 1.0 == 0.0) number.toInt().toString() else text
    }

    private fun isIntegral(bound: Double?): Boolean = bound == null || bound % 1.0 == 0.0

    private fun format(bound: Double?): String = when {
        bound == null -> "-"
        bound % 1.0 == 0.0 -> bound.toInt().toString()
        else -> bound.toString()
    }
}

/** [HostSettingDefinition.parse] 的结果。 */
internal sealed interface HostSettingParseResult {
    /** [canonical] 是按类型规范化后的字符串，原样交给仓储写入。 */
    data class Ok(val canonical: String) : HostSettingParseResult

    data class Rejected(val reason: String) : HostSettingParseResult
}

/**
 * 全部可下发 / 可写入的设置项。
 *
 * 列表顺序即网页端子页里的渲染顺序；分类靠键前缀（`chatPreferences.` / `mediaConfig.`）
 * 与常量名体现，不再单开一份“分类 → 键”的映射。
 *
 * 只能读不能写的是派生值：`model.configured`（是否已配置模型）与
 * `mediaConfig.ttsVoiceName`（当前 TTS 音色名，网页端只展示）。它们仍然出现在
 * `values` 里，因为网页端要显示它们；写入白名单自然不含它们。
 */
internal object HostSettingDefinitions {

    /** 外观：主题模式。 */
    private val THEME_MODE = HostSettingDefinition(
        key = "themeMode",
        type = HostSettingType.ENUM,
        options = listOf(
            "system" to "settings_theme_follow_system",
            "light" to "settings_theme_light",
            "dark" to "settings_theme_dark",
        ),
    )

    /** 外观：字号档。 */
    private val FONT_SIZE_SCALE = HostSettingDefinition(
        key = "fontSizeScale",
        type = HostSettingType.ENUM,
        options = listOf(
            "small" to "settings_theme_font_small",
            "medium" to "settings_theme_font_medium",
            "large" to "settings_theme_font_large",
            "xlarge" to "settings_theme_font_xlarge",
        ),
    )

    /** 外观：液态玻璃模式。 */
    private val LIQUID_GLASS_MODE = HostSettingDefinition(
        key = "liquidGlassMode",
        type = HostSettingType.ENUM,
        options = listOf(
            "off" to "settings_glass_off",
            "water" to "settings_glass_water",
            "frost" to "settings_glass_frost",
        ),
    )

    /** 外观：玻璃强度（0..100 整数百分比）。 */
    private val LIQUID_GLASS_STRENGTH = HostSettingDefinition(
        key = "liquidGlassStrength",
        type = HostSettingType.NUMBER,
        min = 0.0,
        max = 100.0,
        step = 5.0,
    )

    private val DYNAMIC_COLOR = HostSettingDefinition(key = "dynamicColor", type = HostSettingType.BOOLEAN)

    private val HIGH_CONTRAST = HostSettingDefinition(key = "highContrast", type = HostSettingType.BOOLEAN)

    /** 聊天：发送键（回车直接发 / 回车换行）。 */
    private val CHAT_ENTER_TO_SEND = HostSettingDefinition(
        key = HostSettingKeys.CHAT_ENTER_TO_SEND,
        type = HostSettingType.BOOLEAN,
    )

    private val CHAT_SHOW_TOOL_CALLS = HostSettingDefinition(
        key = "chatPreferences.showToolCallDetails",
        type = HostSettingType.BOOLEAN,
    )

    private val CHAT_SHOW_TIMESTAMP = HostSettingDefinition(
        key = "chatPreferences.showTimestamp",
        type = HostSettingType.BOOLEAN,
    )

    private val CHAT_STREAM_RESPONSE = HostSettingDefinition(
        key = HostSettingKeys.CHAT_STREAM_RESPONSE,
        type = HostSettingType.BOOLEAN,
    )

    /** 聊天：强制返回思考（思考强度只在开启后生效）。 */
    private val CHAT_FORCE_REASONING = HostSettingDefinition(
        key = "chatPreferences.forceReasoning",
        type = HostSettingType.BOOLEAN,
    )

    /** 聊天：思考强度。 */
    private val CHAT_REASONING_LEVEL = HostSettingDefinition(
        key = "chatPreferences.reasoningLevel",
        type = HostSettingType.ENUM,
        options = listOf(
            "OFF" to "reasoning_level_off",
            "AUTO" to "reasoning_level_auto",
            "LOW" to "reasoning_level_low",
            "MEDIUM" to "reasoning_level_medium",
            "HIGH" to "reasoning_level_high",
            "XHIGH" to "reasoning_level_xhigh",
        ),
    )

    /** 语音：TTS 引擎。候选与 App 的引擎菜单同一份（[io.zer0.muse.ui.speech.TtsManager.CLOUD_TTS_ENGINES]）。 */
    private val TTS_ENGINE = HostSettingDefinition(
        key = "mediaConfig.ttsEngine",
        type = HostSettingType.ENUM,
        options = listOf(
            "system" to "settings_media_tts_engine_system",
            "openai" to "settings_media_tts_engine_openai",
            "minimax" to "settings_media_tts_engine_minimax",
            "edge" to "settings_media_tts_engine_edge",
            "gemini" to "settings_media_tts_engine_gemini",
            "dashscope" to "settings_media_tts_engine_dashscope",
            "fish" to "settings_media_tts_engine_fish",
            "elevenlabs" to "settings_media_tts_engine_elevenlabs",
            "groq" to "settings_media_tts_engine_groq",
            "qwen" to "settings_media_tts_engine_qwen",
            "step" to "settings_media_tts_engine_step",
            "xai" to "settings_media_tts_engine_xai",
        ),
    )

    /** 语音：播报语速（0.5..2.0 倍，1.0 为正常）。 */
    private val TTS_SPEECH_RATE = HostSettingDefinition(
        key = "mediaConfig.ttsSpeechRate",
        type = HostSettingType.NUMBER,
        min = 0.5,
        max = 2.0,
        step = 0.1,
    )

    /** 语音：当前音色名，只展示。 */
    private val TTS_VOICE_NAME = HostSettingDefinition(
        key = "mediaConfig.ttsVoiceName",
        type = HostSettingType.TEXT,
        writable = false,
    )

    private val PII_GUARD = HostSettingDefinition(key = HostSettingKeys.PII_GUARD, type = HostSettingType.BOOLEAN)

    private val KEEP_ALIVE = HostSettingDefinition(key = HostSettingKeys.KEEP_ALIVE, type = HostSettingType.BOOLEAN)

    private val MINI_PHONE = HostSettingDefinition(key = HostSettingKeys.MINI_PHONE, type = HostSettingType.BOOLEAN)

    private val TOKEN_ESTIMATE = HostSettingDefinition(
        key = HostSettingKeys.TOKEN_ESTIMATE,
        type = HostSettingType.BOOLEAN,
    )

    /** 记忆：总开关。 */
    private val MEMORY_ENABLED = HostSettingDefinition(key = HostSettingKeys.MEMORY, type = HostSettingType.BOOLEAN)

    /**
     * 记忆：把通知内容自动记进记忆。
     *
     * App 没有单独叫「自动保存」的开关，这一项是唯一由用户控制“自动入库”的真字段
     * （记忆自动提取本身按对话轮次自动跑，无开关）。
     */
    private val NOTIFICATION_MEMORY = HostSettingDefinition(
        key = "notificationMemoryEnabled",
        type = HostSettingType.BOOLEAN,
    )

    /** 通用：界面语言。候选与 App 语言选择器一致（跟随系统 / 中 / 英 / 日 / 韩 / 俄）。 */
    private val LANGUAGE = HostSettingDefinition(
        key = "language",
        type = HostSettingType.ENUM,
        options = listOf(
            "system" to "settings_theme_follow_system",
            "zh" to "settings_theme_language_zh",
            "en" to "settings_theme_language_en",
            "ja" to "settings_theme_language_ja",
            "ko" to "settings_theme_language_ko",
            "ru" to "settings_theme_language_ru",
        ),
    )

    /** 通用：是否已配置模型，派生值，只展示。 */
    private val MODEL_CONFIGURED = HostSettingDefinition(
        key = "model.configured",
        type = HostSettingType.BOOLEAN,
        writable = false,
    )

    /** 全部值项，顺序即网页端子页渲染顺序。 */
    val all: List<HostSettingDefinition> = listOf(
        THEME_MODE,
        FONT_SIZE_SCALE,
        LIQUID_GLASS_MODE,
        LIQUID_GLASS_STRENGTH,
        DYNAMIC_COLOR,
        HIGH_CONTRAST,
        PII_GUARD,
        CHAT_ENTER_TO_SEND,
        CHAT_SHOW_TOOL_CALLS,
        CHAT_SHOW_TIMESTAMP,
        CHAT_STREAM_RESPONSE,
        CHAT_FORCE_REASONING,
        CHAT_REASONING_LEVEL,
        TTS_ENGINE,
        TTS_SPEECH_RATE,
        TTS_VOICE_NAME,
        MEMORY_ENABLED,
        NOTIFICATION_MEMORY,
        LANGUAGE,
        MODEL_CONFIGURED,
    )

    /** 写入白名单：从 [all] 派生，与 `values` 同源，不另维护键表。 */
    val writableKeys: List<String> = all.filter { it.writable }.map { it.key }

    private val byKey: Map<String, HostSettingDefinition> = all.associateBy { it.key }

    fun find(key: String?): HostSettingDefinition? = key?.trim()?.takeIf { it.isNotEmpty() }?.let(byKey::get)
}
