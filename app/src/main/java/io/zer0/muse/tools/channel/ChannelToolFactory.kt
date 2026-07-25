package io.zer0.muse.tools.channel

import io.zer0.muse.tools.ToolRegistry

/**
 * 群聊工具执行函数类型。
 *
 * 与 [ToolRegistry] 内部的 ToolFn 类型一致(suspend (Map<String, String>) -> String),
 * 但 ToolFn 在 ToolRegistry 中是 private,无法在外部引用,故在此公开同名类型别名。
 */
typealias ChannelToolFn = suspend (Map<String, String>) -> String

/**
 * 群聊专用工具工厂 — Phone Session 模式下动态构造 channel_* 三件套。
 *
 * 设计参考 openhanako channel-router.ts:Phone Session 注入 3 个强制决策工具,
 * agent 必须调用 channel_reply / channel_pass / channel_read_context 之一表态,
 * 否则触发 repair attempt(重试 1 次)。
 *
 * 与主会话工具注册的区别:
 *  - 主会话工具由 [io.zer0.muse.tools.HanaAgentToolsRegistrar.registerAll] 注册到
 *    全局 [ToolRegistry],所有 Assistant 都可见
 *  - channel_* 工具是**群聊专用**的,不在主会话注册(否则主会话 LLM 会看到这些工具,
 *    可能误用 / 污染工具列表)
 *  - 调用方(GroupChatScheduler)在 Phone Session 时通过 [createChannelTools] 动态
 *    构造一次性工具集,绑定当前 chatId / senderAssistantId 与回调,会话结束后丢弃
 *
 * 工具集内容:
 *  1. [ChannelReplyTool]   — channel_reply,把回复写入群聊(唯一能让消息出现的工具)
 *  2. [ChannelPassTool]    — channel_pass,本轮不发言
 *  3. [ChannelReadContextTool] — channel_read_context,读取更多群聊历史(最多 50 条)
 *
 * 用法示例(GroupChatScheduler 中):
 * ```
 * val tools = ChannelToolFactory.createChannelTools(
 *     groupChatId = chatId,
 *     senderAssistantId = assistant.id,
 *     onReply = { content -> groupChatRepository.sendMessage(...) },
 *     onPass = { reason -> Logger.i(TAG, "Agent ${assistant.name} PASS: $reason") },
 *     contextProvider = { limit -> formatHistory(groupChatRepository.getRecentMessages(chatId, limit)) },
 * )
 * // 把 tools 注册到当前会话的临时 ToolRegistry,或直接拼成 ToolDefinition 列表传给 streamChat
 * ```
 */
object ChannelToolFactory {

    /**
     * 构造群聊 Phone Session 专用工具集。
     *
     * @param groupChatId 当前群聊 id(绑定到工具,LLM 不可见)
     * @param senderAssistantId 当前发言 agent 的 id(绑定到工具,LLM 不可见)
     * @param onReply channel_reply 回调:把 content 持久化到群聊
     * @param onPass channel_pass 回调:记录本轮不发言的 activity(可选 reason)
     * @param contextProvider channel_read_context 回调:返回格式化的历史消息文本
     * @return 三件套(ToolDef, 执行函数),调用方可注册到临时 ToolRegistry 或转 ToolDefinition
     */
    fun createChannelTools(
        groupChatId: String,
        senderAssistantId: String,
        onReply: suspend (String) -> Unit,
        onPass: suspend (String?) -> Unit,
        contextProvider: suspend (Int) -> String,
    ): List<Pair<ToolRegistry.ToolDef, ChannelToolFn>> {
        // 三件套顺序与 openhanako channel-router.ts 注入顺序一致:
        // reply → pass → read_context
        val reply = ChannelReplyTool(groupChatId, senderAssistantId, onReply)
        val pass = ChannelPassTool(onPass)
        val readContext = ChannelReadContextTool(groupChatId, contextProvider)
        return listOf(
            reply.toolDef() to { args: Map<String, String> -> reply.execute(args) },
            pass.toolDef() to { args: Map<String, String> -> pass.execute(args) },
            readContext.toolDef() to { args: Map<String, String> -> readContext.execute(args) },
        )
    }
}
