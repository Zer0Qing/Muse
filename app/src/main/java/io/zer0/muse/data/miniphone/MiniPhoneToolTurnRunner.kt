package io.zer0.muse.data.miniphone

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 小手机私信里的一轮工具生成。
 *
 * 这里没有会话审批链路,也没有人正看着屏幕:工具面由 [MiniPhoneToolPolicy] 收窄,
 * 只跑白名单内、且风险等级不是 HIGH 的工具。执行结果回填成 TOOL 消息后继续要下一轮,
 * 轮次用完或模型不再要工具时收口成一条文本。
 *
 * 对外只发 [MpReplyEvent]:模块拿不到工具名、参数与返回值,只能看到「在做什么」。
 *
 * @param chatService 生成入口;为空(模型未配置)时这一轮直接失败
 * @param toolRegistry 工具来源;为空时不给模型任何工具,退化成纯文本回复
 * @param timeoutMs 整轮(含所有工具轮)的硬超时
 * @param now 时间源,测试用它钉死回填消息的 `createdAt`
 */
class MiniPhoneToolTurnRunner(
    private val chatService: ChatService?,
    private val toolRegistry: ToolRegistry?,
    private val timeoutMs: Long = REPLY_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 冷流:每收集一次跑一轮完整生成。
     *
     * 事件顺序:若干 [MpReplyEvent.Activity](每条工具调用前一条) → 一条
     * [MpReplyEvent.Text];超时、没有任何文本或生成入口不可用时只有一条 [MpReplyEvent.Failed]。
     */
    fun reply(
        messages: List<UIMessage>,
        temperature: Float?,
        maxTokens: Int?,
    ): Flow<MpReplyEvent> = flow {
        val service = chatService
        if (service == null) {
            emit(MpReplyEvent.Failed)
            return@flow
        }
        val tools = resolveTools()
        val conversation = messages.toMutableList()
        val answer = withTimeoutOrNull(timeoutMs) {
            converge(service, tools, conversation, temperature, maxTokens) { kind ->
                emit(MpReplyEvent.Activity(kind))
            }
        }
        val text = answer?.trim().orEmpty()
        if (text.isEmpty()) emit(MpReplyEvent.Failed) else emit(MpReplyEvent.Text(text))
    }

    /**
     * 跑到拿到一句不需要工具的话为止。
     *
     * 上面几轮带工具请求;轮次用完仍要工具、或一开始就没有工具可用时,这一轮不带工具收尾。
     */
    private suspend fun converge(
        service: ChatService,
        tools: List<ToolDefinition>,
        conversation: MutableList<UIMessage>,
        temperature: Float?,
        maxTokens: Int?,
        onActivity: suspend (MpActivityKind) -> Unit,
    ): String {
        var rounds = 0
        while (true) {
            val useTools = tools.takeIf { rounds < MAX_TOOL_ROUNDS && it.isNotEmpty() }
            val completion = request(service, conversation, useTools, temperature, maxTokens)
            val calls = completion.toolCalls.orEmpty()
            if (useTools == null || calls.isEmpty()) return completion.text
            rounds += 1
            conversation += completion.toAssistantMessage(calls)
            calls.forEach { call ->
                onActivity(MiniPhoneToolPolicy.activityKindOf(call.name))
                conversation += executeTool(call)
            }
        }
    }

    /** 一次非流式请求。收尾那一轮显式不带工具,也不再指定 toolChoice。 */
    private suspend fun request(
        service: ChatService,
        conversation: List<UIMessage>,
        tools: List<ToolDefinition>?,
        temperature: Float?,
        maxTokens: Int?,
    ): ChatCompletion = service.completeText(
        messages = conversation,
        temperature = temperature,
        maxTokens = maxTokens,
        tools = tools,
        toolChoice = TOOL_CHOICE.takeIf { tools != null },
    )

    /** 执行一条工具调用,把结果包成回填用的 TOOL 消息。 */
    private suspend fun executeTool(call: ToolCall): UIMessage = UIMessage(
        role = MessageRole.TOOL,
        content = toolOutput(call),
        toolCallId = call.id,
        createdAt = now(),
    )

    /**
     * 工具返回值(或一句降级说明)。
     *
     * 执行前再核一次白名单:模型可能吐出请求里没有的工具名,不能凭它一句话就去执行。
     */
    private suspend fun toolOutput(call: ToolCall): String {
        val registry = toolRegistry ?: return TOOLS_UNAVAILABLE
        if (!MiniPhoneToolPolicy.isAllowed(call.name)) return TOOL_NOT_ALLOWED
        return resultOf { registry.executeFromJson(call.name, call.arguments) }
            .onError { msg, t -> Logger.w(TAG, "小手机工具执行异常: ${t?.message ?: msg}") }
            .getOrNull()
            ?: TOOL_FAILED
    }

    /** 当前可用工具;装配失败按「这一轮没有工具」处理,不影响文本回复。 */
    private fun resolveTools(): List<ToolDefinition> {
        val registry = toolRegistry ?: return emptyList()
        return resultOf { MiniPhoneToolPolicy.filter(registry.listToolsAsToolDefinitions(null)) }
            .onError { msg, t -> Logger.w(TAG, "装配小手机工具失败，本轮不提供工具: ${t?.message ?: msg}") }
            .getOrNull()
            .orEmpty()
    }

    private fun ChatCompletion.toAssistantMessage(calls: List<ToolCall>): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        content = text,
        toolCalls = calls,
        createdAt = now(),
    )

    private companion object {
        private const val TAG = "MiniPhoneToolTurnRunner"

        /** 与定时任务同口径:三轮工具足够查一次再答一次,再多就是模型在原地打转。 */
        private const val MAX_TOOL_ROUNDS = 3

        /** 私信是同步等待的:超过一分钟用户早走了,不如落一句「对方没有回应」。 */
        private const val REPLY_TIMEOUT_MS = 60_000L

        private const val TOOL_CHOICE = "auto"

        private const val TOOLS_UNAVAILABLE = "[工具不可用] 当前没有可执行的工具"
        private const val TOOL_NOT_ALLOWED = "[未授权] 小手机里不能调用该工具"
        private const val TOOL_FAILED = "[失败] 工具执行出错"
    }
}
