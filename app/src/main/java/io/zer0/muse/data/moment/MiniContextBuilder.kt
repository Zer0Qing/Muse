package io.zer0.muse.data.moment

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.transformer.LorebookTransformer
import io.zer0.muse.transformer.PromptInjectionTransformer
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.transformer.ThinkTagTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.transformer.TransformerPipeline
import kotlinx.serialization.json.Json

/**
 * 小手机（私信对话 / 主动消息 / 动态）这类"旁路生成"的上下文构建器。
 *
 * 为什么需要它：
 * 这些场景此前只做 `"你是 $name" + 素材` 的三段字符串拼接，完全绕过
 * [SystemPromptAssembler]，导致**世界书、记忆、用户画像、提示词注入、正则、MOOD、
 * 表情包指南**全部不生效。同一个角色在主对话和小手机里会表现出两个人格。
 *
 * 与主链路的区别：
 * - 不碰 [io.zer0.muse.ui.ChatViewModel]（3000+ 行可变状态集合，且持有协程作用域与 UI 状态，
 *   绝不能拉进后台 Worker / 定时任务）
 * - 不跑 MemoryInjection / TimeReminder / ContextCompress 等"对话历史相关"环节，
 *   旁路生成没有历史，跑这些只会产生空转或错误预算
 * - 只跑与"人格一致性"直接相关的三个 Transformer：世界书、提示词注入、think 标签剥离
 *
 * 线程模型：所有方法都是 suspend，内部读取均为一线程一次性调用，可在
 * WorkManager 的 CoroutineWorker 里直接使用（Koin 通过 GlobalContext 解析）。
 */
class MiniContextBuilder(
    private val systemPromptAssembler: SystemPromptAssembler,
    private val lorebookRepository: LorebookRepository,
    private val promptInjectionRepository: PromptInjectionRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val idListJson = Json { ignoreUnknownKeys = true }

    /**
     * 构建一次旁路生成所需的完整消息列表。
     *
     * @param assistant 角色身份（含人设、世界书绑定、提示词注入绑定）。null 时退化为默认助手。
     * @param userContent 本轮要交给模型的具体素材（对话里最后一条用户消息 / 主动消息由头 / 动态素材）
     * @param sessionId 可空；仅用于 TransformContext 透传，旁路生成通常没有真实会话
     * @param voiceMessageEnabled 是否在系统指令末尾追加语音消息约定。只有小手机私信需要：
     *        动态与主动消息没有语音出口，标记只会污染正文，因此默认关闭。
     * @return 形如 [system, ..., user] 的消息列表，可直接交给 `ChatService.completeText`
     */
    suspend fun buildMessages(
        assistant: AssistantEntity?,
        userContent: String,
        sessionId: String? = null,
        voiceMessageEnabled: Boolean = false,
    ): List<UIMessage> {
        val systemMessages = buildSystemMessages(assistant, voiceMessageEnabled)
        val userMessage = UIMessage(
            role = MessageRole.USER,
            content = userContent,
            createdAt = System.currentTimeMillis(),
        )
        // 世界书 / 提示词注入需要"最后一条 USER 消息"作为关键词匹配文本，
        // 所以先拼好原始列表再交给管道处理。
        val raw = systemMessages + userMessage
        return applyTransformers(raw, assistant, sessionId)
    }

    /**
     * 取系统提示消息。
     *
     * `forSubagent = true` 的理由：旁路生成是"隔离的单次任务"，不该拿团队花名册、
     * 委派工具清单、Agent 收件箱摘要——那些只属于主对话。但它仍保留基础人格、
     * 世界书与记忆注入，这正是本次修复要补的部分。
     *
     * 语音约定（[voiceMessageInstruction]）排在系统消息最后一条：人格与世界书先立人设，
     * 输出格式类的指令紧随其后，模型对靠近用户消息的约束更敏感。
     */
    private suspend fun buildSystemMessages(assistant: AssistantEntity?, voiceMessageEnabled: Boolean): List<UIMessage> {
        val memoryEnabled = assistant?.memoryEnabled ?: true
        val timeReminderEnabled = assistant?.enableTimeReminder ?: true
        val base = resultOf {
            systemPromptAssembler.build(
                assistant = assistant,
                memoryEnabled = memoryEnabled,
                timeReminderEnabled = timeReminderEnabled,
                forSubagent = true,
            )
        }.onError { msg, t ->
            Logger.w(TAG, "构建系统提示失败，退化为空系统提示: ${t?.message ?: msg}")
        }.getOrNull() ?: emptyList()
        if (!voiceMessageEnabled) return base
        // 系统提示构建失败时也照常带上约定：没有它模型不会输出标记，语音功能等于不存在
        return base + UIMessage(role = MessageRole.SYSTEM, content = voiceMessageInstruction())
    }

    /** 只跑与人格一致性相关的 Transformer。 */
    private suspend fun applyTransformers(messages: List<UIMessage>, assistant: AssistantEntity?, sessionId: String?): List<UIMessage> {
        val lorebookEntries = loadLorebookEntries(assistant)
        val modeInjections = loadPromptInjections(assistant)
        if (lorebookEntries.isEmpty() && modeInjections.isEmpty()) {
            // 没有可注入内容时仍要剥 think 标签，避免推理文本混入正文
            return runCatching { ThinkTagTransformer().transform(messages, emptyContext(sessionId, assistant)) }
                .getOrElse { messages }
        }

        val pipeline = TransformerPipeline(
            listOf(
                LorebookTransformer(lorebookRepository),
                PromptInjectionTransformer(),
                ThinkTagTransformer(),
            ),
        )
        val context = TransformContext(
            sessionId = sessionId,
            modelId = assistant?.modelId,
            temperature = assistant?.temperature,
            maxTokens = assistant?.maxTokens,
            extras = buildMap {
                put("lorebook_entries", lorebookEntries)
                put("prompt_injections", modeInjections)
                // 用户画像 → 模板变量（与 ChatStreamCoordinator 的 key 保持一致）
                val profile = resultOf { settingsRepository.getUserProfile() }.getOrNull()
                put("user_nickname", profile?.userNickName)
                put("assistant_name", profile?.assistantName ?: assistant?.name)
                // 这些在旁路生成里没有意义，显式关闭以免 Transformer 误判
                put("memory_enabled", false)
                put("time_reminder_enabled", false)
            },
        )
        return resultOf { pipeline.execute(messages, context) }
            .onError { msg, t -> Logger.w(TAG, "Transformer 管道失败，退化为原始消息: ${t?.message ?: msg}") }
            .getOrNull() ?: messages
    }

    private fun emptyContext(sessionId: String?, assistant: AssistantEntity?) = TransformContext(
        sessionId = sessionId,
        modelId = assistant?.modelId,
    )

    /** 反查助手绑定的世界书条目（只取 enabled 的，与主链路一致）。 */
    private suspend fun loadLorebookEntries(assistant: AssistantEntity?): List<LorebookEntity> {
        val ids = parseIdList(assistant?.lorebookIdsJson)
        if (ids.isEmpty()) return emptyList()
        return resultOf { lorebookRepository.getByIdsEnabled(ids) }
            .onError { msg, t -> Logger.w(TAG, "读取世界书条目失败: ${t?.message ?: msg}") }
            .getOrNull() ?: emptyList()
    }

    /**
     * 反查助手绑定的提示词注入条目。
     *
     * 旁路生成没有"当前模式"概念，因此**不做 mode 过滤**——助手显式绑定到某个模式的
     * 注入项如果在小手机里被吞掉，用户会认为"设置了不生效"。宁可多注入，也不静默丢弃。
     * 未绑定时退回该模式下的全局启用项（同样不按 mode 过滤）。
     */
    private suspend fun loadPromptInjections(assistant: AssistantEntity?): List<PromptInjectionEntity> {
        val ids = parseIdList(assistant?.modeInjectionIdsJson)
        if (ids.isNotEmpty()) {
            return resultOf { promptInjectionRepository.getByIdsEnabled(ids) }
                .onError { msg, t -> Logger.w(TAG, "读取提示词注入失败: ${t?.message ?: msg}") }
                .getOrNull() ?: emptyList()
        }
        val mode = DEFAULT_PROMPT_MODE
        return resultOf { promptInjectionRepository.getEnabledByMode(mode) }
            .onError { msg, t -> Logger.w(TAG, "读取全局提示词注入失败: ${t?.message ?: msg}") }
            .getOrNull() ?: emptyList()
    }

    /** 与 ChatViewModel.parseIdList 同语义。 */
    private fun parseIdList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { idListJson.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "MiniContext"

        /** 旁路生成使用的默认提示词模式（与助手默认绑定一致）。 */
        private const val DEFAULT_PROMPT_MODE = "default"
    }
}

/**
 * 小手机私信的语音输出约定。
 *
 * 宿主侧按 `[语音]……[/语音]` 切段：标记里的内容合成语音发出，标记外的当普通文本；
 * 单轮最多合成 5 段，标记内没有中文的段落会被跳过。这些规则在模型侧无法自发现，
 * 所以必须显式写进系统指令，否则模型永远不会输出标记。
 *
 * 只给私信路径用：动态与主动消息也走同一个 [MiniContextBuilder]，但它们没有语音出口。
 */
internal fun voiceMessageInstruction(): String = buildString {
    appendLine("<voice_message>")
    appendLine("想说出口的台词用 [语音] 开头、[/语音] 结尾包起来，一对方括号包一段。系统只把标记里的内容合成语音发出去，对方听到的是声音，看不到标记；标记外的文字照常当消息发。")
    appendLine("- 一次最多 5 段。")
    appendLine("- 旁白、动作描写、心理活动、英文原文不要放进去：这些不会被念出来，纯英文的一段会被直接跳过。")
    appendLine("- 不必每轮都发语音。情绪重、话短、想让对方听见的时候才用。")
    appendLine("- 标记不要嵌套，也不要跨段复用：一段台词一对标记，说完就收起。")
    appendLine("</voice_message>")
}.trim()
