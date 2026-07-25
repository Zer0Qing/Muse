package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.ChatError
import io.zer0.muse.ui.ChatErrorType
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
) {

    private val tag = "ChatVM"

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
        val messages = state.messages
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
        val messages = accessor.snapshot.messages
        val index = messages.indexOfFirst { it.id == id }
        // v1.125: index==-1 时不再静默跳过,改为 fallback 追加新消息,
        // 防止切后台再切回时 assistant 消息不在列表中导致流式数据丢失。
        if (index == -1) {
            val fallbackMsg = UIMessage(
                id = id,
                role = io.zer0.ai.core.MessageRole.ASSISTANT,
                content = content,
                reasoning = reasoning,
                imageBase64List = imageBase64List ?: emptyList(),
                imageUrls = imageUrls ?: emptyList(),
                videoFileUri = videoFileUri,
            )
            accessor.update { it.copy(messages = messages + fallbackMsg) }
            return
        }
        val msg = messages[index]

        // v1.42: 快速路径 — 流式过程中绝大多数 chunk 不含特殊标签,直接按索引更新,避免遍历全列表与正则。
        val hasSpecialTags = content.contains("<mood>", ignoreCase = true) ||
            content.contains("<reflection>", ignoreCase = true) ||
            content.contains("<think>", ignoreCase = true)
        if (isStreaming && !hasSpecialTags) {
            val updated = msg.copy(
                content = content,
                reasoning = reasoning ?: msg.reasoning,
                imageBase64List = imageBase64List ?: msg.imageBase64List,
                imageUrls = imageUrls ?: msg.imageUrls,
                videoFileUri = videoFileUri ?: msg.videoFileUri,
            )
            val newMessages = messages.toMutableList().apply { set(index, updated) }
            accessor.update { it.copy(messages = newMessages) }
            return
        }

        // 完整路径:流式结束或 content 含特殊标签时,执行 mood/reflection/think 提取。
        val (contentAfterMood, moodContent) = if (content.contains("<mood>", ignoreCase = true)) {
            extractTagContent(content, "mood")
        } else {
            content to null
        }
        val (contentAfterReflection, reflectionContent) = if (contentAfterMood.contains("<reflection>", ignoreCase = true)) {
            extractTagContent(contentAfterMood, "reflection")
        } else {
            contentAfterMood to null
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
            reflection = reflectionContent ?: msg.reflection,
        )
        val newMessages = messages.toMutableList().apply { set(index, updated) }
        accessor.update { it.copy(messages = newMessages) }
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
        val current = msg ?: accessor.snapshot.messages.firstOrNull { it.id == assistantId } ?: return
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
        val partial = partialMsg ?: accessor.snapshot.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
        } ?: return
        val interruptedMsg = partial.copy(content = partial.content + "\n\n[已中断]")
        // 只有 partialMsg==null(即从 _state.messages 找到的消息)时才更新 UI;
        // 切页场景(partialMsg!=null)下 _state.messages 已是别的会话,不应更新。
        if (partialMsg == null) {
            accessor.update { state ->
                state.copy(
                    messages = state.messages.map { if (it.id == interruptedMsg.id) interruptedMsg else it }
                )
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
}
