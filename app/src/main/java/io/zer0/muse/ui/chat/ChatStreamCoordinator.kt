package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.registry.ModelRegistry
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.limitContextWithContext
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.R
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.serialization.json.jsonObject
import io.zer0.muse.privacy.PiiGuard
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.transformer.MoodSkinParser
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.ui.ChatError
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.CompactionState
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.util.MusePatterns
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * v1.131: <think>...</think> 标签正则已迁移到 io.zer0.muse.util.MusePatterns.THINK_TAG_REGEX。
 */

/**
 * v1.105 阶段 3: 从 ChatViewModel 抽离的流式辅助 Coordinator。
 *
 * 职责:
 *  - detachStreaming: 切页/切会话时脱离流式 UI(不停止后台生成)
 *  - notifySessionEndForCurrent: 通知 MemoryTicker 当前 session 结束
 *  - updateAssistant: 流式过程中更新 assistant 消息(含 mood/reflection/think 标签提取)
 *  - persistCurrentAssistant: 周期性落盘(流中进度持久化)
 *  - persistInterruptedAssistant: 标记 [已中断] 并把部分回复落盘
 *  - extractThinkContent / extractTagContent: 字符串标签提取工具
 *
 * launchStream 主体(835 行)因捕获大量局部变量且与 ChatViewModel 多个字段强耦合,
 * 保留在 ChatViewModel 中,通过本 Coordinator 调用辅助方法。
 */
class ChatStreamCoordinator(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val memoryTicker: MemoryTicker,
    private val settings: io.zer0.muse.data.SettingsRepository,
    private val appContext: android.content.Context,
    private val notificationManager: io.zer0.muse.notification.MuseNotificationManager,
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository,
    private val visionBridge: io.zer0.muse.vision.VisionBridge,
    private val toolRegistry: ToolRegistry,
    private val skillRepository: SkillRepository,
    private val idListJson: kotlinx.serialization.json.Json,
    private val lorebookRepository: LorebookRepository,
    private val promptInjectionRepository: PromptInjectionRepository,
    private val transformerPipeline: TransformerPipeline,
    // P1-1: Hook 注册表 — 在管道执行后调用 PromptFinalizeHook
    private val hookRegistry: io.zer0.muse.hook.HookRegistry? = null,
) {

    private val tag = "ChatVM"

    /**
     * v1.0.27 Phase 4-A.2: 表情包工具名常量,与 ChatViewModel.STICKER_TOOL_IDS 保持一致。
     * sticker 概率控制时,未命中则从 tools 过滤掉这两个工具。
     */
    private val STICKER_TOOL_IDS = setOf("list_stickers", "send_sticker")

    /**
     * v1.0.52: 情绪调制系数 — 基于最近用户消息检测情绪强度。
     *
     * 返回 ≥1.0 的倍数:中性对话 1.0,强情绪最高约 3.0。
     * 配合 stickerSendProbability 基线,让 AI 在用户情绪强烈时更愿意发贴纸。
     */
    private fun stickerEmotionBoost(): Float {
        val recentUserMessages = accessor.messagesSnapshot
            .filter { it.role == io.zer0.ai.core.MessageRole.USER && it.content.isNotBlank() }
            .takeLast(6)
        val result = StickerEmotionDetector.detectEmotion(recentUserMessages)
        return when (result.dominant) {
            StickerEmotionDetector.EmotionType.ANGRY -> 1f + result.intensity * 2f
            StickerEmotionDetector.EmotionType.SAD -> 1f + result.intensity * 2f
            StickerEmotionDetector.EmotionType.JOYFUL -> 1f + result.intensity * 1.5f
            StickerEmotionDetector.EmotionType.EXCITED -> 1f + result.intensity * 1.5f
            StickerEmotionDetector.EmotionType.NEUTRAL -> 1f
        }
    }

    /**
     * v1.0.27 Phase 4-A.2: id 列表 JSON 解析辅助。
     * 与 ChatViewModel.parseIdList 保持一致的语义,避免跨类调用。
     */
    private fun parseIdList(json: String): List<String> =
        runCatching { idListJson.decodeFromString<List<String>>(json) }
            .getOrDefault(emptyList())

    // ── 流式状态控制 ──────────────────────────────────────────────────

    /**
     * v1.97: 切页/切会话/切 Tab 时脱离流式 UI,不停止后台生成。
     *
     * 与 stop() 的区别:
     * - stop():用户主动停止,取消 SSE 连接 + 生成协程
     * - detachStreaming():切页触发,生成闭包继续在 appScope 运行
     *   - updateAssistant 因 index==-1 静默跳过(不更新错误会话的 messages)
     *   - persistCurrentAssistant 用 builder 内容直接落盘(不依赖 _state.messages)
     *   - 通知仍正常更新(notificationManager 不依赖 _state)
     *   - 切回原会话时从 DB 加载最新内容(含中间落盘)+ 恢复 isStreaming
     */
    fun detachStreaming() {
        accessor.update { it.copy(isStreaming = false) }
    }

    /**
     * 通知 ticker 当前 session 结束(fire-and-forget)。
     *
     * Phase 8.5 修复: 原实现用 `runBlocking { settings.getSelectedModel() }` 在主线程阻塞,
     * onCleared / createNewSession / switchSession 调用时会 ANR。
     * 改为:model 传 null(MemoryTicker 内部 launchTracked 异步执行,能处理 null model
     * 的降级场景),完全去掉 runBlocking。
     */
    fun notifySessionEndForCurrent() {
        // v1.136: Agent 模式用 agentSessionId,任务模式用 currentSessionId
        val state = accessor.snapshot
        val sessionId = (if (state.isAgentMode) state.agentSessionId else state.currentSessionId)
            ?: return
        val messages = accessor.messagesSnapshot
        if (messages.isNotEmpty()) {
            // onCleared 时 viewModelScope 即将取消;MemoryTicker 用自己的 application scope fire-and-forget
            // model 传 null,MemoryTicker 内部降级用默认模型
            memoryTicker.notifySessionEnd(
                sessionId,
                messages,
                model = null,
                assistantId = state.currentAssistant?.id ?: "",
            )
        }
    }

    // ── 消息更新 ──────────────────────────────────────────────────────

    /**
     * 流式过程中更新 assistant 消息。
     *
     * v1.42: 性能优化开关。
     * - true(流式中):走快速路径,跳过 mood/reflection/think 正则剥离;
     *   只在 content 中确实出现相关标签时才降级到完整提取。
     * - false(默认,流式结束或非流式更新):执行完整标签提取与合并,得到最终展示内容。
     *
     * v1.80 (M-CVM3): 性能权衡说明 —— 这里用 messages.toMutableList() 做 O(n) 全列表复制,
     * 在流式高频更新(每 40 字符)下有一定开销。理论上改用 PersistentList 可避免复制,
     * 但会牵动 ChatUiState.messages 类型与全部消费方(改动大、风险高)。
     * 当前消息量级(通常 < 数百条)下 O(n) 复制可接受,权衡后保留 toMutableList 方案。
     */
    fun updateAssistant(
        id: Uuid,
        content: String,
        reasoning: String? = null,
        imageBase64List: List<String>? = null,
        // v1.80 (M-CVM4): 新增 imageUrls 参数,允许在一次 state 更新中同时刷新图片 URL,
        // 避免调用方在 updateAssistant 之后再单独更新 messages(双重列表复制 + 双次 state 写入)。
        imageUrls: List<String>? = null,
        // v1.135: 支持助手消息附加生成的视频 URL,MessageBubble 据此渲染可播放视频卡片。
        videoFileUri: String? = null,
        isStreaming: Boolean = false,
    ) {
        val messages = accessor.messagesSnapshot
        val index = messages.indexOfFirst { it.id == id }
        // v1.0.21: index==-1 时静默跳过,不 fallback 追加。
        // v1.125 的 fallback append 会导致切会话后后台生成把旧会话的流式内容
        // 追加到新会话的消息列表中,造成跨会话消息污染(用户消息变助手消息的根因)。
        // 后台生成的持久化走 persistCurrentAssistant(msg=...) 直接落盘,不依赖 _state.messages。
        // 切回原会话时从 DB 加载最新内容(含中间落盘),不会丢失数据。
        if (index == -1) return
        val msg = messages[index]

        // v1.42: 快速路径 — 流式过程中绝大多数 chunk 不含特殊标签,直接按索引更新,避免遍历全列表与正则。
        val hasSpecialTags = content.contains("<mood>", ignoreCase = true) ||
            content.contains("<reflection>", ignoreCase = true) ||
            content.contains("<think>", ignoreCase = true) ||
            content.contains("<moodfx>", ignoreCase = true)
        if (isStreaming && !hasSpecialTags) {
            val updated = msg.copy(
                content = content,
                reasoning = reasoning ?: msg.reasoning,
                imageBase64List = imageBase64List ?: msg.imageBase64List,
                imageUrls = imageUrls ?: msg.imageUrls,
                videoFileUri = videoFileUri ?: msg.videoFileUri,
            )
            val newMessages = messages.toMutableList().apply { set(index, updated) }
            accessor.updateMessages { newMessages }
            return
        }

        // 完整路径:流式结束或 content 含特殊标签时,执行 mood/reflection/think 提取。
        val (contentAfterMood, moodContent) = if (content.contains("<mood>", ignoreCase = true)) {
            extractTagContent(content, "mood")
        } else {
            content to null
        }
        // B6-02: moodfx 独立解析,不与应用自带 <mood> 串台
        val (moodSkinContent, contentAfterMoodSkin) = if (contentAfterMood.contains("<moodfx>", ignoreCase = true)) {
            MoodSkinParser.extract(contentAfterMood)
        } else {
            null to contentAfterMood
        }
        val (contentAfterReflection, reflectionContent) = if (contentAfterMoodSkin.contains("<reflection>", ignoreCase = true)) {
            extractTagContent(contentAfterMoodSkin, "reflection")
        } else {
            contentAfterMoodSkin to null
        }
        val (cleanContent, thinkContent) = extractThinkContent(contentAfterReflection)
        // v1.62 修复:reasoning 重复问题。
        // 旧逻辑把 existingReasoning + newReasoning + thinkContent 三者拼接,
        // 导致 finalize 时翻倍(existingReasoning==newReasoning)、
        // <think> 标签模式线性增长(每次 ContentDelta 都拼接 thinkContent)。
        // 新逻辑:reasoning 参数非空时直接覆盖(来自 ReasoningDelta 累积,已是完整值);
        // reasoning 参数为 null 时,用 thinkContent(content 中 <think> 提取)作为 fallback;
        // 两者都无时保留 msg.reasoning。
        val combinedReasoning = when {
            !reasoning.isNullOrBlank() -> reasoning
            !thinkContent.isNullOrBlank() -> thinkContent
            else -> msg.reasoning
        }
        val updated = msg.copy(
            content = cleanContent,
            reasoning = combinedReasoning,
            imageBase64List = imageBase64List ?: msg.imageBase64List,
            imageUrls = imageUrls ?: msg.imageUrls,
            videoFileUri = videoFileUri ?: msg.videoFileUri,
            mood = moodContent ?: msg.mood,
            moodSkin = moodSkinContent ?: msg.moodSkin,
            reflection = reflectionContent ?: msg.reflection,
        )
        val newMessages = messages.toMutableList().apply { set(index, updated) }
        accessor.updateMessages { newMessages }
    }

    // ── 持久化 ────────────────────────────────────────────────────────

    /**
     * v1.43: 周期性落盘 — 把当前 assistant 消息的流中进度持久化到数据库,
     * 让切页/后台后的新 ViewModel 能从 DB 恢复最新内容。
     * 不提取产物(产物只在流式结束后提取),也不附加 citation(流式中 pending)。
     *
     * v1.97 (P1-2): 周期性落盘用 skipFts=true 跳过 FTS 重建(toNgram 对长文本开销大)。
     * 流式结束后最终落盘(直接 upsertMessage)会同步 FTS;中断走 persistInterruptedAssistant 也同步。
     * 若 app 崩溃导致 FTS 漂移,下次启动 ensureFtsIndexConsistent 会自动 rebuild。
     */
    fun persistCurrentAssistant(
        sessionId: String,
        assistantId: Uuid,
        msg: UIMessage? = null,
        addError: (ChatErrorType, String) -> Unit,
    ) {
        // v1.97: msg 参数用于切页后 _state.messages 已切换到新会话、原 assistantId 不在其中的场景。
        // 生成闭包用 builder 构造 UIMessage 传入,绕过 _state.messages 查找,确保中间落盘不中断。
        val current = msg ?: accessor.messagesSnapshot.firstOrNull { it.id == assistantId } ?: return
        // v1.0.54: 工具轮空占位(content/reasoning/图片全空)不落库 — 工具轮消息无用户可见内容,
        //   落库后重启加载会残留空消息(用户看到"空的对话 UI")。
        if (current.content.isBlank() && current.reasoning.isNullOrBlank() &&
            current.imageBase64List.isEmpty() && current.imageUrls.isEmpty()
        ) {
            return
        }
        // v1.80 (L-CVM4): 用 NonCancellable 包裹持久化,确保 ViewModel 销毁/协程取消时仍能落盘
        accessor.coroutineScope.launch {
            withContext(NonCancellable) {
                try {
                    sessionRepository.upsertMessage(sessionId, current, skipFts = true)
                } catch (e: Exception) {
                    Logger.e(tag, "persistCurrentAssistant failed", e)
                    // v1.65: 助手持久化失败给用户反馈
                    addError(ChatErrorType.UNKNOWN, "助手状态保存失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /**
     * v1.80 (M-CVM2): 标记当前 assistant 消息为 [已中断] 并把部分回复落盘。
     *
     * 在流式被取消(用户点停止/切会话)或异常中断时调用,确保已接收的内容不随 ViewModel 销毁丢失。
     * 持久化用 [NonCancellable] 包裹,保证协程被取消时仍能完成 DB 写入
     * (否则 suspend 调用在已取消协程中会立即抛 CancellationException,落盘失败)。
     */
    suspend fun persistInterruptedAssistant(sessionId: String, partialMsg: UIMessage? = null) {
        // v1.97: partialMsg 参数用于切页后 _state.messages 已切换到新会话的场景。
        // 生成闭包用 builder 构造 UIMessage 传入,绕过 _state.messages 查找。
        val partial = partialMsg ?: accessor.messagesSnapshot.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
        } ?: return
        val interruptedMsg = partial.copy(content = partial.content + "\n\n[已中断]")
        // 只有 partialMsg==null(即从 _state.messages 找到的消息)时才更新 UI;
        // 切页场景(partialMsg!=null)下 _state.messages 已是别的会话,不应更新。
        if (partialMsg == null) {
            accessor.updateMessages { messages ->
                messages.map { if (it.id == interruptedMsg.id) interruptedMsg else it }
            }
        }
        // NonCancellable: 协程已取消时仍完成落盘
        withContext(NonCancellable) {
            try {
                sessionRepository.upsertMessage(sessionId, interruptedMsg)
            } catch (e: Exception) {
                Logger.e(tag, "persistInterruptedAssistant upsertMessage failed", e)
            }
        }
    }

    // ── 字符串处理工具 ────────────────────────────────────────────────

    /**
     * v1.23: 从 assistant 流式 content 中提取 `<think>...</think>` 思考链。
     *
     * - 完整标签:直接抽出内容并移出正文。
     * - 未闭合标签(流式中常见):把 `<think>` 之后的内容暂存到 reasoning,
     *   正文只保留标签之前的内容,避免 raw tag 直接显示在气泡里。
     * - 忽略大小写,兼容 `<Think>` 等变体。
     *
     * @return Pair<清理后的正文, 提取出的思考内容(null 表示无)>
     */
    private fun extractThinkContent(input: String): Pair<String, String?> {
        if (!input.contains("<think>", ignoreCase = true)) return input to null

        val sb = StringBuilder()
        var remaining = input
        var match = MusePatterns.THINK_TAG_REGEX.find(remaining)
        while (match != null) {
            sb.append(match.groupValues[1])
            remaining = remaining.removeRange(match.range)
            match = MusePatterns.THINK_TAG_REGEX.find(remaining)
        }

        // 处理流式过程中标签尚未闭合的情况
        val openIdx = remaining.indexOf("<think>", ignoreCase = true)
        val partialThink: String? = if (openIdx != -1 && !remaining.contains("</think>", ignoreCase = true)) {
            val afterTag = remaining.substring(openIdx + "<think>".length)
            remaining = remaining.substring(0, openIdx)
            afterTag.trim().ifBlank { null }
        } else {
            null
        }

        val fullThink = listOfNotNull(
            sb.toString().trim().ifBlank { null },
            partialThink,
        ).joinToString("\n\n").ifBlank { null }

        return remaining.trim() to fullThink
    }

    /**
     * v1.42: 通用标签内容提取辅助(用于 mood/reflection)。
     *
     * 与 MoodTagTransformer 保持一致的逻辑,但只在确认含标签时调用,避免流式中高频正则。
     */
    private fun extractTagContent(input: String, tagName: String): Pair<String, String?> {
        if (!input.contains("<$tagName>", ignoreCase = true)) return input to null
        val regex = Regex("""<$tagName>([\s\S]*?)</$tagName>""", RegexOption.IGNORE_CASE)
        val sb = StringBuilder()
        var remaining = input
        var match = regex.find(remaining)
        while (match != null) {
            sb.append(match.groupValues[1])
            remaining = remaining.removeRange(match.range)
            match = regex.find(remaining)
        }
        return remaining.trim() to sb.toString().trim().ifBlank { null }
    }

    // ── Phase A: 历史准备 ────────────────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: 从 ChatViewModel 抽取的历史准备逻辑。
     *
     * 读取 Assistant 配置 / 推理等级 / 温度 / ExperimentsConfig / contextSize,
     * 清理孤儿 tool_call,按 contextSize 截断历史。
     */
    internal suspend fun prepareHistory(state: StreamRunState) {
        with(state) {
            // 通知:启动流式进度通知(LOW importance,不发声)
            sessionTitle = accessor.snapshot.sessions
                .firstOrNull { it.id == sessionId }?.title ?: appContext.getString(R.string.chat_new_session)
            runCatching {
                notificationManager.updateLiveProgress(sessionTitle, 0, true)
            }.onFailure { Logger.w("ChatVM", "启动进度通知失败: ${it.message}") }
            // v1.133: RAG 检索引用列表,流式结束后附加到 assistant 消息
            pendingRagCitations = emptyList()
            // Phase 8.2: 取 Assistant 配置(找不到则用默认助手)
            assistant = accessor.snapshot.currentAssistant
                ?: assistantRepository.getById("default")
            // v1.136: 用户/助手请求的推理等级
            // v1.0.47 P5-6: deepThinkingEnabled 时使用用户选择的级别(默认 HIGH),不再硬编码 HIGH
            requestedReasoningLevel = if (accessor.snapshot.deepThinkingEnabled) {
                accessor.snapshot.deepThinkingLevel
            } else assistant?.let {
                runCatching { ReasoningLevel.valueOf(it.reasoningLevel) }
                    .getOrElse { ReasoningLevel.DEFAULT }
                    .let { if (it == ReasoningLevel.AUTO) ReasoningLevel.OFF else it }
            } ?: ReasoningLevel.OFF
            // 全局温度回退
            effectiveTemperature = assistant?.temperature
                ?: accessor.snapshot.chatPreferences.globalTemperature
            // v0.32 实验性:读取 ExperimentsConfig
            experiments = runCatching { settings.experimentsCache }
                .getOrDefault(ExperimentsConfig())
            // v0.32 实验性 debugMode:重置 debugInfo,启动计时器与计数器
            if (experiments.debugMode) {
                accessor.update { it.copy(debugInfo = null) }
                Logger.d("ChatVM-Debug", "launchStream start | sessionId=$sessionId | assistantId=${assistant?.id} | requestedReasoningLevel=$requestedReasoningLevel")
            }
            streamStartedAt = System.currentTimeMillis()

            // v0.32 实验性 longMemoryCompression:context 截断阈值减半
            contextSize = assistant?.contextMessageSize?.coerceAtLeast(1) ?: 20
            if (experiments.longMemoryCompression) {
                contextSize = (contextSize / 2).coerceAtLeast(5)
                if (experiments.debugMode) {
                    Logger.d("ChatVM-Debug", "longMemoryCompression enabled | contextSize $contextSize (halved)")
                }
            }

            // 去掉占位 assistant,并按 Assistant.contextMessageSize 截断
            val messagesExceptPlaceholder = accessor.messagesSnapshot.dropLast(1)
            // v1.0.2: 防御性清理孤儿 tool_call
            rawHistory = messagesExceptPlaceholder.filterIndexed { index, msg ->
                if (msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
                    messagesExceptPlaceholder.getOrNull(index + 1)?.role == MessageRole.TOOL
                } else {
                    true
                }
            }
            if (rawHistory.size < messagesExceptPlaceholder.size) {
                Logger.w(
                    "ChatViewModel",
                    "清理孤儿 tool_call: ${messagesExceptPlaceholder.size - rawHistory.size} 条 assistant 消息被丢弃",
                )
            }
            // v1.x: 工具依赖感知截断
            truncatedHistory = rawHistory.limitContextWithContext(contextSize)
        }
    }

    // ── Phase F: PII 遮蔽 ────────────────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: 从 ChatViewModel 抽取的 PII 遮蔽逻辑。
     *
     * 发送给 LLM 前对最新一条 USER 消息做敏感信息遮蔽,
     * AI 回复中出现的占位符在写入 UI / DB 前用 unmaskPii 还原。
     * 仅遮蔽最新用户消息(保守策略,避免误检历史消息中的正常数字);
     * 工具调用循环内 conversationHistory 中保留占位符,确保下一轮 LLM 仍看到一致上下文。
     * piiMatches 与 unmaskPii 已在 [StreamRunState] 中声明,catch 块可访问。
     */
    internal suspend fun applyPiiGuard(state: StreamRunState) {
        with(state) {
            val piiGuardEnabled = settings.piiGuardEnabledCache
            if (piiGuardEnabled) {
                val lastUserIdx = conversationHistory.indexOfLast { it.role == MessageRole.USER }
                if (lastUserIdx >= 0) {
                    val originalContent = conversationHistory[lastUserIdx].content
                    if (originalContent.isNotEmpty()) {
                        val (maskedContent, matches) = PiiGuard.mask(originalContent)
                        if (matches.isNotEmpty()) {
                            conversationHistory[lastUserIdx] =
                                conversationHistory[lastUserIdx].copy(content = maskedContent)
                            piiMatches = matches
                        }
                    }
                }
            }
        }
    }

    // ── Phase G: 视觉辅助 ────────────────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: 从 ChatViewModel 抽取的视觉辅助逻辑。
     *
     * 当前模型不支持视觉时,通过视觉模型分析图片并注入描述。
     * 无论视觉分析成功或失败,都必须清空原消息的 imageBase64List,
     * 否则后续 streamChat 仍会向不支持视觉的模型发送图片,导致中转站返回 HTTP 400。
     */
    internal suspend fun prepareVisionContext(state: StreamRunState) {
        with(state) {
            val historyImageCount = conversationHistory.count { it.role == MessageRole.USER && it.imageBase64List.isNotEmpty() }
            val effectiveModelLocal = effectiveModel
            val modelSupportsVision = effectiveModelLocal?.supportsVisionInput() ?: false
            Logger.i(
                "ChatVM",
                "视觉辅助[前置检查]: effectiveModel=${effectiveModelLocal?.id} " +
                    "supportsVisionInput=$modelSupportsVision " +
                    "inputModalities=${effectiveModelLocal?.inputModalities} " +
                    "history中图片消息数=$historyImageCount",
            )
            if (effectiveModelLocal != null && !visionBridge.supportsVision(effectiveModelLocal)) {
                val userImageIndexes = conversationHistory.indices.filter { idx ->
                    conversationHistory[idx].role == MessageRole.USER &&
                        conversationHistory[idx].imageBase64List.isNotEmpty()
                }
                Logger.i("ChatVM", "视觉辅助[条件满足]: userImageIndexes=${userImageIndexes.size}")
                if (userImageIndexes.isNotEmpty()) {
                    val lastIdx = userImageIndexes.last()
                    val lastUserMsg = conversationHistory[lastIdx]
                    val imageCount = lastUserMsg.imageBase64List.size
                    Logger.i(
                        "ChatVM",
                        "视觉辅助[触发]: model=${effectiveModelLocal.id} supportsVision=false " +
                            "图片数=$imageCount 历史待清理=${userImageIndexes.size - 1}",
                    )
                    MuseToast.show(appContext.getString(R.string.vision_analysis_starting))
                    val prepareResult = run {
                        accessor.update {
                            it.copy(visionProgress = io.zer0.muse.vision.VisionProgress(
                                idle = false, index = 0, total = lastUserMsg.imageBase64List.size,
                                messageId = lastUserMsg.id.toString(),
                            ))
                        }
                        try {
                            visionBridge.prepare(
                                text = lastUserMsg.content,
                                images = lastUserMsg.imageBase64List,
                                userRequest = lastUserMsg.content,
                                sessionId = sessionId,
                                messageId = lastUserMsg.id.toString(),
                                onProgress = { current, total ->
                                    accessor.update {
                                        it.copy(visionProgress = io.zer0.muse.vision.VisionProgress(
                                            idle = false,
                                            index = current,
                                            total = total,
                                            messageId = lastUserMsg.id.toString(),
                                        ))
                                    }
                                },
                            )
                        } finally {
                            accessor.update { it.copy(visionProgress = null) }
                        }
                    }
                    conversationHistory[lastIdx] = lastUserMsg.copy(
                        content = prepareResult.text,
                        imageBase64List = prepareResult.images,
                    )
                    if (prepareResult.success) {
                        Logger.i("ChatVM", "视觉辅助[成功]: 已注入 ${prepareResult.descriptionCount} 条视觉描述")
                        MuseToast.show(appContext.getString(R.string.vision_analysis_done, prepareResult.descriptionCount))
                    } else {
                        val failDetail = prepareResult.text.take(200)
                        Logger.w("ChatVM", "视觉辅助[失败]: prepare 降级,详情=$failDetail")
                        MuseToast.show(appContext.getString(R.string.err_chat_vision_fallback))
                    }

                    accessor.update {
                        it.copy(visionAssistedMessageIds = it.visionAssistedMessageIds + lastUserMsg.id.toString())
                    }

                    for (idx in userImageIndexes) {
                        if (idx == lastIdx) continue
                        val histMsg = conversationHistory[idx]
                        if (histMsg.imageBase64List.isNotEmpty()) {
                            conversationHistory[idx] = histMsg.copy(imageBase64List = emptyList())
                            Logger.d(
                                "ChatVM",
                                "视觉辅助: 已清空历史消息 #$idx 的 ${histMsg.imageBase64List.size} 张图片" +
                                    "(避免直达纯文本模型)",
                            )
                        }
                    }
                } else {
                    Logger.i("ChatVM", "视觉辅助[跳过]: userImageIndexes 为空,未找到带图片的 USER 消息")
                }
            } else {
                Logger.i(
                    "ChatVM",
                    "视觉辅助[跳过]: effectiveModel 为空或模型支持视觉,不触发视觉辅助 " +
                        "effectiveModel=${effectiveModel?.id} supportsVisionInput=$modelSupportsVision",
                )
            }
        }
    }

    // ── Phase C+D: Transformer 管道 ──────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: 从 ChatViewModel 抽取的 Transformer 管道逻辑。
     *
     * 1. 预查询 Assistant 绑定的 Lorebook 条目
     * 2. 预查询当前模式对应的 PromptInjection 条目
     * 3. 读取用户画像(userNickname / assistantName)注入模板变量
     * 4. 构造 TransformContext,关闭已由 SystemPromptAssembler 接管的 memory/time reminder
     * 5. 执行 transformerPipeline.execute(prefixMessages + truncatedHistory, context)
     * 6. 保存 context 供后续 applyVisualTransform / applyOnGenerationFinish 复用
     */
    internal suspend fun applyTransformers(state: StreamRunState) {
        with(state) {
            // Phase 8.1 H1: Transformer 管道(职责收敛:Assembler 接管 system 提示后,
            // MemoryInjection / TimeReminder 改为禁用,只保留 Lorebook/PromptInjection/Template/ThinkTag)
            // Phase 8.5: 预查询 Assistant 绑定的 Lorebook 条目
            val lorebookIds = assistant?.let { parseIdList(it.lorebookIdsJson) } ?: emptyList()
            val lorebookEntries: List<LorebookEntity> = if (lorebookIds.isNotEmpty()) {
                resultOf { lorebookRepository.getByIdsEnabled(lorebookIds) }
                    .getOrNull() ?: emptyList()
            } else emptyList()
            // Phase 8.5: 预查询当前模式对应的 PromptInjection 条目
            val currentMode = accessor.snapshot.currentMode
            val modeInjections: List<PromptInjectionEntity> = if (currentMode != "default") {
                val injIds = assistant?.let { parseIdList(it.modeInjectionIdsJson) } ?: emptyList()
                if (injIds.isNotEmpty()) {
                    (resultOf { promptInjectionRepository.getByIdsEnabled(injIds) }
                        .getOrNull() ?: emptyList())
                        .filter { it.mode == currentMode }
                } else {
                    resultOf { promptInjectionRepository.getEnabledByMode(currentMode) }
                        .getOrNull() ?: emptyList()
                }
            } else emptyList()
            // v1.97: 读取用户画像,把 user_nickname / assistant_name 注入模板变量
            val userProfile = resultOf { settings.getUserProfile() }.getOrNull()
            val assistantName = userProfile?.assistantName
                ?: assistant?.name
            val userNickname = userProfile?.userNickName
            val context = TransformContext(
                sessionId = sessionId,
                modelId = assistant?.modelId,
                temperature = effectiveTemperature,
                maxTokens = assistant?.maxTokens,
                extras = mapOf(
                    // v0.30-a: 已由 SystemPromptAssembler 接管,关闭 Transformer 管道里的对应职责
                    "memory_enabled" to false,
                    "time_reminder_enabled" to false,
                    // Phase 8.5
                    "lorebook_entries" to lorebookEntries,
                    "prompt_injections" to modeInjections,
                    // v1.97: 助手级正则规则(预解析,供 RegexMessageTransformer 使用)
                    "regex_rules" to (assistant?.let {
                        io.zer0.muse.transformer.RegexTransformer.parseRules(it)
                    } ?: emptyList()),
                    // v1.97: 模板变量 — 用户昵称与助手名(供 {{user}} / {{char}} 等)
                    "user_nickname" to userNickname,
                    "assistant_name" to assistantName,
                    // v0.25: 长上下文压缩 — 默认启用,20 条触发,保留最近 15 条
                    // v0.32 实验性 longMemoryCompression:阈值从 20 降到 10,更早触发摘要压缩
                    "compress_enabled" to true,
                    // v1.138: 修复 compress_threshold < compress_keep_recent 导致压缩无法触发。
                    // longMemoryCompression 模式下 threshold=10,keep_recent 必须小于 threshold。
                    "compress_threshold" to if (experiments.longMemoryCompression) 10 else 20,
                    "compress_keep_recent" to if (experiments.longMemoryCompression) 8 else 15,
                ),
            )
            // v1.0.47 P1: 流式 Compaction UI — 消息数超过阈值时显示"正在压缩上下文"状态
            val compressThreshold = if (experiments.longMemoryCompression) 10 else 20
            val totalMsgCount = prefixMessages.size + truncatedHistory.size
            if (totalMsgCount > compressThreshold) {
                accessor.update { it.copy(compactionState = CompactionState.Compacting(totalMsgCount)) }
            }
            transformedMessages = transformerPipeline.execute(prefixMessages + truncatedHistory, context)

            // P1-1: 调用 PromptFinalizeHook — 在管道执行后、发送给 LLM 前做最终修改
            // 典型用途: 楼层式上下文限制(P1-4)、Worldbook 关键词触发注入(P1-2)
            if (hookRegistry != null) {
                val finalizeEvent = io.zer0.muse.hook.PromptFinalizeEvent(
                    preparedHistory = transformedMessages,
                    assistantId = accessor.snapshot.currentAssistant?.id,
                    sessionId = accessor.snapshot.currentSessionId,
                    transformContext = context,
                )
                val finalizeResult = hookRegistry.execute(
                    io.zer0.muse.hook.PromptFinalizeHook::class,
                    initial = io.zer0.muse.hook.PromptFinalizeResult(finalizeEvent.preparedHistory),
                ) { hook, acc ->
                    val event = finalizeEvent.copy(preparedHistory = acc.preparedHistory)
                    hook.beforeFinalizePrompt(event)
                }
                transformedMessages = finalizeResult.preparedHistory
            }
            // v1.0.47 P1: 压缩完成 — 更新状态为 Compacted(显示短暂提示)或清除
            if (totalMsgCount > compressThreshold) {
                val compressedCount = totalMsgCount - (if (experiments.longMemoryCompression) 8 else 15)
                accessor.update { it.copy(compactionState = CompactionState.Compacted(compressedCount.coerceAtLeast(0))) }
                // 3 秒后清除 Compacted 状态
                accessor.coroutineScope.launch {
                    kotlinx.coroutines.delay(3000)
                    accessor.update { curr ->
                        if (curr.compactionState is CompactionState.Compacted) curr.copy(compactionState = null)
                        else curr
                    }
                }
            }
            // v1.x: 三钩子接入 — 保存 context,供后续 applyVisualTransform / applyOnGenerationFinish 复用
            transformContext = context
        }
    }

    // ── Phase E: 工具定义 + 模型解析 ──────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: 从 ChatViewModel 抽取的工具与模型解析逻辑。
     *
     * 1. 按 Assistant.toolIdsJson 过滤本地工具定义
     * 2. 加载启用的 Skills(按 Assistant.skillIdsJson 过滤)转为 ToolDefinition
     * 3. 概率控制 sticker 工具暴露
     * 4. 按 name 去重(本地工具优先于 Skill)
     * 5. 解析 effectiveModel 与 effectiveProviderConfig(per-assistant 优先,回退全局)
     * 6. 工具模型路由:tools 非空且有 toolModel 时,用 toolModel 替代主模型
     * 7. 推理等级降级:effectiveModel 不支持推理时,降到 AUTO/OFF
     * 8. conversationHistory 初始化为 transformedMessages 的可变副本
     */
    internal suspend fun resolveToolsAndModel(state: StreamRunState) {
        with(state) {
            // Phase 7+8.8: 工具定义 — 按 Assistant.toolIdsJson 过滤本地工具,
            // 再合并启用的 Skills 作为额外工具(空列表表示全部启用,向后兼容)
            val enabledToolIds = assistant?.let { ast ->
                runCatching {
                    idListJson.decodeFromString<List<String>>(ast.toolIdsJson)
                }.onFailure { Logger.w("ChatVM", "toolIdsJson 解析失败: ${it.message}") }.getOrNull()
            }
            val localToolDefs = toolRegistry.listToolsAsToolDefinitions(enabledToolIds)

            // Phase 8.8: 加载启用的 Skills 并转为 ToolDefinition
            // v1.0.47 P3: 会话级 skill 覆盖 — 优先用 session.skillIdsJson(非"[]"且非空),
            // 否则回退到 assistant.skillIdsJson(默认行为不变)
            val sessionSkillIdsJson = accessor.snapshot.sessions
                .firstOrNull { it.id == sessionId }?.skillIdsJson
            val effectiveSkillIdsJson = if (!sessionSkillIdsJson.isNullOrEmpty() && sessionSkillIdsJson != "[]") {
                sessionSkillIdsJson
            } else {
                assistant?.skillIdsJson
            }
            val enabledSkillIds = effectiveSkillIdsJson?.let { json ->
                runCatching { idListJson.decodeFromString<List<String>>(json) }.getOrNull()
            }
            // B6-01: 外部插件工具默认并入工具定义,无需逐个助手开启 skill 白名单
            val enabledSkillIdsSet = enabledSkillIds?.toSet().orEmpty()
            val pluginSkills = skillRepository.listEnabled().filter { it.category == "plugin" && it.id !in enabledSkillIdsSet }
            val enabledSkills = (skillRepository.listEnabledByIds(enabledSkillIds) + pluginSkills).distinctBy { it.id }
            // 缓存 skill id → SkillEntity 映射,工具执行时用
            skillMap = enabledSkills.associateBy { it.id }
            // v1.116: 表情包概率控制 — 读取设置缓存,决定本轮是否向 LLM 暴露 sticker 工具。
            // stickerEnabled=false:完全不暴露 list_stickers / send_sticker
            // stickerEnabled=true:按 stickerSendProbability 概率掷骰子,命中才暴露
            // 这样 LLM 只能在概率命中时看到工具,实现了用户设置的概率控制
            // v1.0.52: 情绪调制 — 检测最近对话情绪,情绪强烈时放大暴露概率(封顶 100%),
            // 中性对话保持用户设置的基线概率。
            // v1.0.54: 表情包功能已弃用 — 工具永不暴露(UI 已关闭,数据保留)
            val stickerToolsEnabled = false
            val skillToolDefs = enabledSkills.map { sk ->
                // v1.0.53: send_sticker 动态注入概率引导 — 让模型真正按用户设置的概率发贴纸
                //   (概率控制只决定"工具是否暴露",发不发由模型判断;显式告知概率后
                //   模型会按此概率主动调用,100% 时每次合适回复都会尝试发)。
                val stickerProbHint = if (sk.id == "send_sticker") {
                    " 用户设置的表情包发送概率为 ${settings.stickerSendProbabilityCache}%(在设置中调整)。" +
                        "概率 ≥ 50% 时请在合适的回复中主动调用本工具发送表情包;概率 = 100% 时每次回复都应尝试发送。"
                } else ""
                io.zer0.ai.core.ToolDefinition(
                    name = sk.id,
                    description = sk.description + stickerProbHint,
                    parametersJsonSchema = normalizeSkillSchema(sk.parametersJson),
                )
            }.filter { def ->
                // 概率未命中时过滤掉 sticker 相关工具
                if (stickerToolsEnabled) true else def.name !in STICKER_TOOL_IDS
            }
            // v1.0.4 修复 HTTP 400 "Tool names must be unique":
            // `generate_image` 同时被注册为 ToolRegistry 内置工具(registerMediaTools)
            // 和 SkillExecutor.BUILT_IN_SKILLS 中的 Skill,默认助手同时启用两份,
            // 直接拼接会发出重复 tools,DeepSeek/中转站严格校验工具名唯一性会返回 400。
            // 这里按 name 去重,ToolDef(本地工具实现)优先保留,同名 Skill 被丢弃。
            tools = (localToolDefs + skillToolDefs).distinctBy { it.name }

            // v1.52: per-assistant 模型解析 — 助手配置了 modelId 则用助手专属模型,
            // 否则回退到全局 selectedModelId,再否则由 ChatService 兜底(激活 Provider 首个模型)。
            // 同时解析模型所属的 ProviderConfig,确保跨 Provider 的助手模型也能正确路由。
            val allProviders = accessor.snapshot.providers
            val assistantModelId = assistant?.modelId?.takeIf { it.isNotBlank() }
            val assistantProviderId = assistant?.providerId?.takeIf { it.isNotBlank() }
            val resolvedModel: Model? = if (assistantModelId != null && assistantProviderId != null) {
                allProviders.firstOrNull { it.id == assistantProviderId }
                    ?.models?.firstOrNull { it.id == assistantModelId }
            } else {
                // v1.0.53+: 激活 Provider 优先 — 助手只配了 modelId（未配 providerId）时，
                // 优先在激活 Provider 的模型里找同 id，避免多个 provider 存在同 id 模型
                // （如 deepseek-v4-flash 同时存在于 opencode-go 与官方渠道）时 flatMap 全局匹配
                // 命中非激活 provider，导致“切了 Provider 聊天请求仍走旧渠道”。
                // 激活 Provider 找不到（如助手绑定的是该 Provider 没有的模型）才全局兜底。
                assistantModelId?.let { aid ->
                    val inActive = allProviders.firstOrNull { it.id == accessor.snapshot.activeProviderId }
                        ?.models?.firstOrNull { it.id == aid }
                    inActive ?: allProviders.flatMap { it.models }.firstOrNull { it.id == aid }
                }
            } ?: accessor.snapshot.selectedModelId?.let { sid ->
                // 与上面相同的激活 Provider 优先策略
                val inActive = allProviders.firstOrNull { it.id == accessor.snapshot.activeProviderId }
                    ?.models?.firstOrNull { it.id == sid }
                inActive ?: allProviders.flatMap { it.models }.firstOrNull { it.id == sid }
            }
            // 兜底:selectedModelId 为 null 且 assistant 未配 modelId 时,
            // 优先用激活 Provider 的首个模型,避免跨 Provider 误选其他 provider 的模型。
            // v1.0.28 修复: 之前用 allProviders.firstOrNull 不考虑激活 provider,
            // 在多 provider 场景下会误选 SiliconFlow 免费模型(若 SiliconFlow 排在 allProviders 前面)。
            // 场景:用户切到 OpenCode 但未点选具体模型(让默认选首个),应选 OpenCode 的首个模型,
            // 而非 SiliconFlow 的 GLM-4-9B。
            ?: allProviders.firstOrNull { it.id == accessor.snapshot.activeProviderId && it.models.isNotEmpty() }?.let { p ->
                p.models.firstOrNull()
            }
            // 二级兜底:激活 Provider 无模型(如未拉取/未填 apiKey),才退回首个有模型的 provider
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }?.let { p ->
                p.models.firstOrNull()
            }
            val resolvedProviderConfig = resolvedModel?.let { m ->
                allProviders.firstOrNull { it.id == m.providerId }
            } ?: allProviders.firstOrNull { it.id == accessor.snapshot.activeProviderId && it.models.isNotEmpty() }
                ?: allProviders.firstOrNull { it.models.isNotEmpty() }

            // v1.60-A: 工具模型路由 — 工具调用轮次优先使用用户配置的轻量 toolModel
            // v1.0.53: per-assistant 优先 — 助手自己配了 toolModelId 时用它,否则回退全局 toolModelId
            val assistantToolModelId = accessor.snapshot.currentAssistant?.toolModelId?.takeIf { it.isNotBlank() }
            val toolModelId = assistantToolModelId ?: accessor.snapshot.toolModelId
            // v1.0.53: 解析时主模型 provider 优先 — 同一模型 id 可能存在于多个 provider,
            //   直接 flatMap.firstOrNull 会匹配到无关 provider(如 kimi-k2.6 匹配到 opencode 的),
            //   导致 Agent 模式跨 provider 跳变。先找主模型所在 provider,找不到再全局兜底。
            val toolModel: Model? = toolModelId?.let { tid ->
                val inMainProvider = resolvedModel?.let { rm ->
                    allProviders.firstOrNull { it.id == rm.providerId }
                        ?.models?.firstOrNull { it.id == tid }
                }
                inMainProvider ?: allProviders.flatMap { it.models }.firstOrNull { it.id == tid }
            }
            val toolProviderConfig = toolModel?.let { m ->
                allProviders.firstOrNull { it.id == m.providerId }
            }
            // v1.0.53: 工具模型可用性 — 助手显式配置的工具模型允许任意 provider;
            //   回退全局的工具模型要求与主模型同 provider,避免 Agent 模式跨 provider 跳变
            //   (主对话走 tokenrhythm、工具轮却跳 opencode 的割裂观感)。
            val toolModelUsable = toolModel != null && (
                assistantToolModelId != null ||
                    resolvedModel == null ||
                    toolModel.providerId == resolvedModel.providerId
                )
            // 工具轮(tools 非空)且有可用 toolModel 时,用 toolModel 替代主模型
            val rawEffectiveModel = if (tools.isNotEmpty() && toolModelUsable) toolModel else resolvedModel
            // v1.135: 用 ModelRegistry 增强模型能力识别,解决 opencode-go/ 等前缀导致
            // supportsVision / supportsReasoning 误判的问题。ChatService 内部也会再增强一次。
            effectiveModel = rawEffectiveModel?.let { ModelRegistry.enhanceModel(it) }
            effectiveProviderConfig = if (tools.isNotEmpty() && toolModelUsable) toolProviderConfig else resolvedProviderConfig

            // v1.136: 若当前模型不支持推理,将推理等级降级到 AUTO/OFF。
            // 避免向非推理模型发送 reasoning_effort 导致简单问题过度思考,或对不支持的模型返回 400。
            val effectiveModelForReasoning = effectiveModel
            reasoningLevel = if (effectiveModelForReasoning != null && !effectiveModelForReasoning.supportsReasoning()) {
                if (requestedReasoningLevel == ReasoningLevel.OFF) ReasoningLevel.OFF else ReasoningLevel.AUTO
            } else requestedReasoningLevel

            // 累积的对话历史(含工具调用结果,每轮可能追加 assistant+tool 消息)
            conversationHistory = transformedMessages.toMutableList()

            // B3-10: 弱工具模型不暴露委派/子代理工具,避免无效轮次与连续失败等待
            if (io.zer0.muse.tools.WeakToolUseDetector.isWeakToolModel(effectiveModel)) {
                val delegationToolNames = setOf(
                    "delegate_agent",
                    "subagent_run",
                    "subagent_task",
                    "task_plan",
                    "update_plan_step",
                    "subagent_close",
                )
                tools = tools.filterNot { it.name in delegationToolNames }
            }
        }
    }
}

/**
 * 规范化 Skill 参数 schema。
 *
 * OpenAI 兼容 API 要求函数参数必须是 JSON Schema 的 `{"type":"object",...}` 结构。
 * 外部插件包（如 pelle-d-umore）可能把参数写成空 `{}`（无 type 字段），
 * 直接透传会被上游拒绝（HTTP 400: schema must be a JSON Schema of type object）。
 * 空对象 / 空串 / 缺 type 时统一补成标准空对象 schema。
 */
private fun normalizeSkillSchema(raw: String): String {
    if (raw.isBlank()) return """{"type":"object","properties":{}}"""
    return runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        if (element.containsKey("type")) {
            raw
        } else {
            kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                element.forEach { (k, v) -> put(k, v) }
            }.toString()
        }
    }.getOrElse { """{"type":"object","properties":{}}""" }
}
