package io.zer0.muse.tools.channel

import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * 群聊上下文读取工具 — Phone Session 模式下 agent 读取更多群聊历史。
 *
 * 设计参考 openhanako channel-router.ts 的 channel_read_context:
 *  - 默认 agent 只能看到最近几条消息,需要更多上下文时调用此工具
 *  - 最多读取 50 条(openhanako 限制)
 *  - 通过 [contextProvider] 回调由调用方提供历史消息文本(已格式化)
 *
 * 与 SkillExecutor 中已有的 channel_read_context 区别:
 *  - SkillExecutor 版本:LLM 传 chatId/limit,主会话可调
 *  - 此版本:chatId 由框架绑定,只接收 limit 参数,只供群聊 Phone Session 使用
 */
class ChannelReadContextTool(
    private val groupChatId: String,
    private val contextProvider: suspend (limit: Int) -> String,
) {

    /**
     * 工具定义。
     *
     * limit 为可选参数,parameterTypes 标注为 integer,使 LLM 生成 JSON Schema 时
     * 类型为 integer(避免 LLM 误传 "20" 字符串导致解析歧义)。
     * 默认值 20,范围 1..50(openhanako 限制)。
     */
    fun toolDef() = ToolRegistry.ToolDef(
        name = "channel_read_context",
        description = "读取更多群聊历史消息,帮助你理解当前对话的完整上下文。" +
            "默认你只能看到最近几条消息,如果需要更多上下文请调用此工具。",
        parameters = mapOf(
            "limit" to "可选。要读取的历史消息数量,范围 1-50,默认 20",
        ),
        required = emptySet(),
        category = "built-in",
        parameterTypes = mapOf("limit" to "integer"),
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * 执行工具:解析 limit(默认 20,范围 1..50),通过 [contextProvider] 回调获取历史。
     *
     * @param args 工具参数,LLM 传入的 arguments JSON 解析后的 map
     * @return 格式化后的群聊历史文本
     */
    suspend fun execute(args: Map<String, String>): String {
        if (groupChatId.isBlank()) {
            return "错误: 群聊工具未正确绑定(chatId 为空)"
        }
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
        return contextProvider(limit)
    }
}
