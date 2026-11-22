package io.zer0.muse.transformer

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.ProfileSnapshotRefresher
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.STICKER_FREQ_FREQUENT
import io.zer0.muse.data.STICKER_FREQ_OCCASIONALLY
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantMemoryAccessPolicy
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.assistant.SpeechGuidePolicy
import io.zer0.muse.data.assistant.SpeechGuideStore
import io.zer0.muse.data.assistant.SpeechGuideStyle
import io.zer0.muse.data.assistant.UserPersonaStore
import io.zer0.muse.data.assistant.VoiceReplyFrequency
import io.zer0.muse.data.assistant.VoiceReplyPolicy
import io.zer0.muse.data.assistant.VoiceReplyPolicyStore
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.ToolCategories
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import io.zer0.muse.ui.chat.ChatViewModel

/**
 * 系统提示词里「思考语言」「回复语言」的指令片段。
 *
 * 从 `SystemPromptAssembler.kt` 切出——三个纯函数，只吃 locale 字符串吐指令文本，
 * 与组装器实例状态无关，各自可独立单测。
 */
/** Stable labels used to constrain visible reasoning language to the app interface locale. */
internal fun thinkingLanguageName(locale: String): String = when (locale.lowercase()) {
    "zh", "zh-cn", "zh-hans" -> "中文"
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
    return if (thinkingLang == "中文") {
        "思考语言\n" +
            "- 内部推理(reasoning_content)和思考过程必须使用中文\n" +
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

/** 正文语言契约：与"思考语言"分开，避免模型把"用用户语言回复"读成只对推理有效。 */
internal fun replyLanguageInstruction(locale: String): String = "回复语言\n" +
    "- 正文使用用户本轮消息所用的语言；不要因为系统提示词、工具说明或历史消息是中文就改用中文\n" +
    "- 判断不出用户语言时，使用应用界面语言：${thinkingLanguageName(locale)}\n" +
    "- 用户明确指定语言时，以用户本轮的指定为准"

/**
 * v0.30-a: 系统提示组装器(6 步工作流 第 1 步)。
 *
 * 集中组装发给 LLM 的系统提示包,替代之前散落在 ChatViewModel.launchStream 内的拼装逻辑。
 *
 * 9 个 section(按顺序拼装,空白段跳过):
 *  1. 人格定义            ← AssistantEntity.systemPrompt + messageTemplate
 *  2. 当前时间            ← 实时生成(替代 TimeReminderTransformer 的职责,内聚到 system)
 *  3. 用户画像            ← 新增:从 SettingsRepository 读(年龄/城市/MBTI/天气偏好)
 *  4. Pinned Memories     ← 新增:固定记忆条目(LLM 可通过 pin_memory 工具写入)
 *  5. 长期记忆摘要        ← MemoryTicker.readCompiledMemoryMarkdown
 *  6. 可用工具清单        ← 新增:从 ToolRegistry + Skills 生成人类可读清单
 *  7. Workspace 路径      ← 新增:filesDir 路径
 *  8. 决策树规则          ← 新增:第三步决策树的提示约束
 *  9. MOOD 格式要求       ← 新增:第六步要求的 mood 标签格式
 *
 * 不再注入 presetMessages / webSearch 结果 — 这些仍由 ChatViewModel 处理(因依赖运行时状态)。
 * TimeReminderTransformer / MemoryInjectionTransformer 的职责被本类吸收,但仍保留为可选
 * (由 context.extra 控制是否启用)。
 *
 * @param context 应用 Context(用于 filesDir)
 * @param settings 设置仓库(读用户画像)
 * @param memoryTicker 记忆系统
 * @param toolRegistry 本地工具注册表
 * @param skillRepository Skill 仓库
 */
