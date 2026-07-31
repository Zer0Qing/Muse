package io.zer0.muse.tools

/**
 * v1.0.52 P2-1: Passive Subagent XML 协议渲染器。
 *
 * 把子 agent 执行过程渲染为三阶段 XML,让主 agent 能清晰看到:
 *  1. <subagent_start/>    — 子 agent 启动(任务描述 + maxToolCalls 配额)
 *  2. <subagent_progress/> — 每轮工具调用进度(轮次/工具名/参数/结果预览)
 *  3. <subagent_result>    — 最终结果(成功/失败 + 总轮次 + 总工具调用数 + 总结文本)
 *
 * 设计原则(参考 Operit examples/subagent):
 *  - XML 标签结构化,便于主 agent 解析和理解子 agent 进度
 *  - 结果预览限制长度,避免撑爆主 agent 上下文
 *  - 失败时仍返回可用信息(已完成的轮次 + 错误原因),让主 agent 决策重试或换路径
 *  - 所有用户可控文本(task/args/result)均做 XML 转义,防止注入
 *
 * 与现有 [SubagentTool] 的区别:
 *  - [SubagentTool] 是非阻塞异步模式(立即返回 taskId,通过 DeferredResultStore 回灌),
 *    其进度通过 TaskState.progress 字符串展示,无固定协议
 *  - 本渲染器服务于 [SubagentRunner] 的同步阻塞模式,主 agent 等待子 agent 完成后
 *    一次性收到完整 XML(含所有进度),协议化便于主 agent 后续解析
 */
object SubagentXmlRenderer {

    /** 单个进度条目的结果预览最大字符数。 */
    private const val PROGRESS_PREVIEW_CHARS = 400

    /** 最终总结的最大字符数(超出截断并标注)。 */
    private const val RESULT_SUMMARY_CHARS = 4000

    /** 工具调用参数的最大字符数(超出截断)。 */
    private const val ARGS_PREVIEW_CHARS = 500

    /**
     * 渲染启动阶段 XML。
     *
     * @param task 委托任务描述
     * @param maxToolCalls 工具调用配额上限
     */
    fun renderStart(task: String, maxToolCalls: Int): String {
        val escapedTask = escapeXml(task)
        return "<subagent_start task=\"$escapedTask\" max_tool_calls=\"$maxToolCalls\"/>"
    }

    /**
     * 渲染单轮工具调用进度 XML。
     *
     * @param round 当前轮次(从 1 开始)
     * @param maxToolCalls 工具调用配额上限
     * @param toolName 工具名
     * @param argsJson 工具参数 JSON 字符串
     * @param result 工具执行结果
     * @param success 工具执行是否成功
     */
    fun renderProgress(
        round: Int,
        maxToolCalls: Int,
        toolName: String,
        argsJson: String,
        result: String,
        success: Boolean,
    ): String {
        val escapedArgs = escapeXml(truncate(argsJson, ARGS_PREVIEW_CHARS))
        val escapedResult = escapeXml(truncate(result, PROGRESS_PREVIEW_CHARS))
        val successAttr = if (success) "true" else "false"
        return "<subagent_progress round=\"$round\" max_tool_calls=\"$maxToolCalls\" " +
            "tool=\"$toolName\" success=\"$successAttr\" " +
            "args=\"$escapedArgs\" result=\"$escapedResult\"/>"
    }

    /**
     * 渲染最终结果 XML。
     *
     * @param success 子 agent 整体是否成功
     * @param rounds 总轮次
     * @param toolCalls 总工具调用次数
     * @param summary 最终总结文本(子 agent 最后一次非工具调用的输出)
     * @param error 失败原因(success=false 时非空)
     * @param threadId v1.0.53: 线程 id(非空时作为属性输出,供主 agent 续接)
     */
    fun renderResult(
        success: Boolean,
        rounds: Int,
        toolCalls: Int,
        summary: String,
        error: String? = null,
        threadId: String? = null,
    ): String {
        val successAttr = if (success) "true" else "false"
        val body = if (success) {
            escapeXml(truncate(summary, RESULT_SUMMARY_CHARS))
        } else {
            val errText = error?.takeIf { it.isNotBlank() } ?: "未知错误"
            "[FAILED] ${escapeXml(errText)}" +
                if (summary.isNotBlank()) "\n[PARTIAL] ${escapeXml(truncate(summary, RESULT_SUMMARY_CHARS))}" else ""
        }
        return buildString {
            append("<subagent_result success=\"$successAttr\" ")
            append("rounds=\"$rounds\" tool_calls=\"$toolCalls\"")
            // v1.0.53: 续接凭证 — 主 agent 拿到后可在下次调用时传入 thread_id 续接
            if (!threadId.isNullOrBlank()) {
                append(" thread_id=\"").append(escapeXml(threadId)).append("\"")
            }
            append(">")
            appendLine()
            append(body)
            appendLine()
            append("</subagent_result>")
        }
    }

    /**
     * 渲染达到 maxToolCalls 上限的截断提示(作为结果的一部分)。
     *
     * 当子 agent 因达到工具调用配额而停止时,在总结前插入此提示,
     * 让主 agent 知道子 agent 是被配额截断而非自然完成。
     */
    fun renderBudgetExhausted(maxToolCalls: Int): String {
        return "[NOTE] 子 agent 已达到工具调用配额上限($maxToolCalls),基于已收集信息输出总结。"
    }

    /** XML 特殊字符转义。 */
    private fun escapeXml(text: String): String {
        if (text.isEmpty()) return ""
        return buildString(text.length + 16) {
            for (ch in text) {
                when (ch) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    '\n' -> append("&#10;")
                    '\r' -> append("&#13;")
                    '\t' -> append("&#9;")
                    else -> append(ch)
                }
            }
        }
    }

    /** 截断到最大长度,超出时追加截断标记。 */
    private fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "...[truncated ${text.length - maxChars} chars]"
    }
}
