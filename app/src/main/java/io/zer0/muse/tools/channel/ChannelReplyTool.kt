package io.zer0.muse.tools.channel

import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * 群聊回复工具 — Phone Session 模式下 agent 用此工具发言。
 *
 * 设计按 既有实现 channel-router.ts 的 channel_reply:
 *  - 只有此工具的 content 会写入群聊消息(其他工具不会)
 *  - chatId / senderAssistantId 在工具构造时绑定,LLM 无需也无法传入
 *  - 通过 [onReply] 回调把内容交回调用方(由 GroupChatScheduler 持久化到群聊)
 *
 * 与 SkillExecutor 中已有的 channel_reply 区别:
 *  - SkillExecutor 版本:LLM 传 chatId/assistantId/body,主会话可调
 *  - 此版本:chatId/assistantId 由框架绑定,只供群聊 Phone Session 使用,
 *    避免主会话 LLM 误用群聊工具
 */
class ChannelReplyTool(
    private val groupChatId: String,
    private val senderAssistantId: String,
    private val onReply: suspend (content: String) -> Unit,
) {

    /**
     * 工具定义。
     *
     * LLM 只需提供 content 参数;chatId / assistantId 已在构造时绑定,
     * 不暴露给 LLM,避免 LLM 伪造发送者身份。
     */
    fun toolDef() = ToolRegistry.ToolDef(
        name = "channel_reply",
        description = "在群聊中发言。你的回复内容将通过此工具发送到群聊频道。" +
            "这是唯一能让你的消息出现在群聊中的方式。" +
            "如果你不想发言,请使用 channel_pass。",
        parameters = mapOf(
            "content" to "必填。你要在群聊中发送的回复内容",
        ),
        required = setOf("content"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * 执行工具:校验 content 并通过 [onReply] 回调发送。
     *
     * @param args 工具参数,LLM 传入的 arguments JSON 解析后的 map
     * @return 执行结果字符串(成功 / 错误信息)
     */
    suspend fun execute(args: Map<String, String>): String {
        val content = args["content"]?.trim()
        if (content.isNullOrEmpty()) {
            return "错误: content 不能为空"
        }
        // groupChatId / senderAssistantId 仅用于绑定上下文,不参与 LLM 可见的逻辑,
        // 但保留校验:若未绑定说明工具被错误构造,直接报错而非静默发送。
        if (groupChatId.isBlank() || senderAssistantId.isBlank()) {
            return "错误: 群聊工具未正确绑定(chatId/assistantId 为空)"
        }
        onReply(content)
        return "已发送到群聊"
    }
}
