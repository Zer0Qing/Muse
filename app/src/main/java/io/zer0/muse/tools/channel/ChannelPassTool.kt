package io.zer0.muse.tools.channel

import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * 群聊跳过工具 — Phone Session 模式下 agent 选择本轮不发言。
 *
 * 设计按 既有实现 channel-router.ts 的 channel_pass:
 *  - agent 必须在 channel_reply / channel_pass / channel_read_context 三者中
 *    至少调用一个表态,否则触发 repair attempt(重试 1 次)
 *  - 调用此工具即明确表态"本轮不发言",由调用方记录 activity
 *  - 可选 reason 参数,用于调试 / 审计为什么不发言
 *
 * 与 SkillExecutor 中已有的 channel_pass 区别:
 *  - SkillExecutor 版本:LLM 传 chatId/assistantId,主会话可调
 *  - 此版本:不接收 chatId/assistantId 参数,框架层绑定,
 *    只供群聊 Phone Session 使用
 */
class ChannelPassTool(
    private val onPass: suspend (reason: String?) -> Unit,
) {

    /**
     * 工具定义。
     *
     * reason 为可选参数,LLM 可说明为什么不发言,便于调试与审计。
     */
    fun toolDef() = ToolRegistry.ToolDef(
        name = "channel_pass",
        description = "本轮选择不发言。如果你觉得没有必要回复,或想等其他成员先说,请调用此工具。" +
            "你可以提供 reason 说明为什么不发言(可选)。",
        parameters = mapOf(
            "reason" to "可选。不发言的原因,便于调试与审计",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * 执行工具:取出可选的 reason,通过 [onPass] 回调通知调用方。
     *
     * @param args 工具参数,LLM 传入的 arguments JSON 解析后的 map
     * @return 执行结果字符串
     */
    suspend fun execute(args: Map<String, String>): String {
        val reason = args["reason"]?.trim()?.takeIf { it.isNotBlank() }
        onPass(reason)
        return "已跳过本轮"
    }
}
