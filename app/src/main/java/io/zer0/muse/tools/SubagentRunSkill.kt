package io.zer0.muse.tools

/**
 * v1.0.52 P2-1: subagent_run 工具入口。
 *
 * 主 agent 通过此工具委托子任务给 [SubagentRunner](独立上下文的被动子 agent)。
 *
 * 与 [SubagentTool] 的关系:
 *  - [SubagentTool](subagent_task) = 非阻塞异步模式,适合长任务,立即返回 taskId,
 *    主 agent 可继续对话,子任务完成后通过 DeferredResultStore 回灌
 *  - **[SubagentRunSkill]**(subagent_run) = 同步阻塞模式,适合短调研任务,主 agent 等待,
 *    子 agent 跑完整工具循环后返回结构化 XML 结果
 *
 * 主 agent 选择指南(在工具描述中告知 LLM):
 *  - 任务需要多步工具调用且不希望污染主上下文 → subagent_run
 *  - 任务是长耗时操作且主 agent 需要继续响应 → subagent_task
 *
 * 参数:
 *  - task: 必填,委托任务描述
 *  - context_text: 可选,限制条件/验收标准
 *  - target_paths: 可选,优先文件路径(逗号分隔)
 *  - max_tool_calls: 可选,工具调用配额(默认 8,上限 20)
 *  - timeout_ms: 可选,整体执行超时(默认 240s,范围 30s~900s)——深度任务建议拉满
 *  - thread_id: 可选(v1.0.53+),续接线程 id;传上一次 subagent_run 返回的 thread_id 继续同一子 agent 会话
 *  - close_thread: 可选(v1.0.53+),"true" 时执行完毕后关闭线程(一次性委派场景)
 *
 * 返回:三阶段 XML 协议字符串
 *  <subagent_start .../>
 *  <subagent_progress .../> (0~N 条)
 *  <subagent_result ... thread_id="...">...</subagent_result>
 */
object SubagentRunSkill {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "subagent_run",
        description = "Delegate a self-contained sub-task to a sub-agent with independent context. " +
            "The sub-agent runs a full tool loop (up to max_tool_calls) and returns a structured XML result. " +
            "Use for research, lookup or analysis sub-tasks that need several tool calls. " +
            "Pass back the returned thread_id to continue the same sub-agent session. " +
            "For long-running async tasks use subagent_task instead.",
        parameters = mapOf(
            "task" to "Required. The sub-task description to delegate.",
            "context_text" to "Optional. Constraints / acceptance criteria for the sub-task.",
            "target_paths" to "Optional. Comma-separated file paths the sub-agent should inspect first.",
            "max_tool_calls" to "Optional. Max tool calls budget (default 8, hard cap 20).",
            "timeout_ms" to "Optional. Overall execution timeout in ms " +
                "(default 240000, range 30000-900000). Raise for deep multi-tool tasks.",
            "thread_id" to "Optional. Thread id from a previous subagent_run call; continues that sub-agent session.",
            "close_thread" to "Optional. 'true' to close the thread after this run (one-shot delegation).",
            "token_budget" to "Optional. Token budget; when exhausted the sub-agent summarizes early.",
        ),
        required = setOf("task"),
        category = "built-in",
        parameterTypes = mapOf(
            "max_tool_calls" to "integer",
            "token_budget" to "integer",
            "timeout_ms" to "integer",
        ),
        // HIGH 风险:子 agent 可调用多个工具,潜在副作用较大,需用户确认
        riskLevel = ToolRiskLevel.HIGH,
    )

    /**
     * 执行 subagent_run 工具。
     *
     * @param args 工具参数(task / context_text / target_paths / max_tool_calls)
     * @param subagentRunner 子 agent 运行器
     * @return 三阶段 XML 协议字符串
     */
    suspend fun execute(args: Map<String, String>, subagentRunner: SubagentRunner): String {
        val task = args["task"]?.trim()
            ?: return errorXml("缺少必填参数 task")
        if (task.isBlank()) return errorXml("task 参数为空")

        val contextText = args["context_text"]?.trim()?.takeIf { it.isNotBlank() }
        val targetPaths = args["target_paths"]?.trim()?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        val maxToolCalls = args["max_tool_calls"]?.toIntOrNull()
            ?.coerceIn(1, SubagentRunner.MAX_TOOL_CALLS_HARD_CAP)
            ?: SubagentRunner.DEFAULT_MAX_TOOL_CALLS

        // v2.x（技术债项目 E）: 超时可透传——深度任务（多轮工具调用/大上下文检索）
        // 120s 默认值不够，主 Agent 可显式给到 600s。范围 30s~900s。
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()
            ?.coerceIn(30_000L, 900_000L)
            ?: SubagentRunner.DEFAULT_TIMEOUT_MS

        // v1.0.53: 续接参数
        val threadId = args["thread_id"]?.trim()?.takeIf { it.isNotBlank() }
        val closeAfterRun = args["close_thread"]?.trim()?.equals("true", ignoreCase = true) ?: false
        // v1.0.53 Phase 3: token 预算(可选,null=不限制)
        val tokenBudget = args["token_budget"]?.toIntOrNull()?.takeIf { it > 0 }

        val params = SubagentRunner.Params(
            task = task,
            contextText = contextText,
            targetPaths = targetPaths,
            maxToolCalls = maxToolCalls,
            timeoutMs = timeoutMs,
            threadId = threadId,
            closeAfterRun = closeAfterRun,
            tokenBudget = tokenBudget,
        )

        // 渲染启动阶段
        val startXml = SubagentXmlRenderer.renderStart(task, maxToolCalls)

        // 运行子 agent
        val result = subagentRunner.run(params)

        // 渲染进度阶段
        val progressXml = result.progressEntries.joinToString("\n") { entry ->
            SubagentXmlRenderer.renderProgress(
                round = entry.round,
                maxToolCalls = maxToolCalls,
                toolName = entry.toolName,
                argsJson = entry.argsJson,
                result = entry.result,
                success = entry.success,
            )
        }

        // 渲染结果阶段(v1.0.53: 透传 threadId 让主 agent 续接)
        // v1.0.53 Phase 3: 配额耗尽提示(maxToolCalls 与 token 预算分别标记)
        val summaryWithNote = buildString {
            if (result.budgetExhausted) {
                appendLine(SubagentXmlRenderer.renderBudgetExhausted(maxToolCalls))
            }
            if (result.tokenBudgetExhausted) {
                appendLine("[提示] 子 agent 因 token 预算耗尽提前结束,总结基于已有上下文生成。")
            }
            append(result.summary)
        }.trimEnd()
        val resultXml = SubagentXmlRenderer.renderResult(
            success = result.success,
            rounds = result.rounds,
            toolCalls = result.toolCalls,
            summary = summaryWithNote,
            error = result.error,
            threadId = result.threadId,
        )

        // 拼接三阶段
        return buildString {
            appendLine(startXml)
            if (progressXml.isNotEmpty()) {
                appendLine(progressXml)
            }
            append(resultXml)
        }
    }

    /** 渲染参数错误时的 XML(不启动子 agent,直接返回错误)。 */
    private fun errorXml(message: String): String {
        return SubagentXmlRenderer.renderResult(
            success = false,
            rounds = 0,
            toolCalls = 0,
            summary = "",
            error = message,
        )
    }
}
