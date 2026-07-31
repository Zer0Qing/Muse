package io.zer0.muse.tools

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.0.52 P2-1: Passive Subagent 运行器 — 同步阻塞式独立子 agent。
 *
 * 与现有 [SubagentTool] / [SkillExecutor.delegateAgent] 的区别:
 *
 * | 维度       | [SubagentTool] (v1.202)   | [SkillExecutor.delegateAgent] (v1.200) | **本类 [SubagentRunner]** (P2-1) |
 * |-----------|---------------------------|----------------------------------------|----------------------------------|
 * | 阻塞模式   | 非阻塞(立即返回 taskId)   | 阻塞(单轮 LLM)                          | **阻塞(完整工具循环)**            |
 * | 上下文     | 续接 threadId 历史         | 继承父会话 contextMessages             | **完全独立(仅 task + contextText)**|
 * | 工具循环   | 单轮(委托 delegateAgent)  | 单轮 + 一轮 delegate_agent tool_call   | **完整多轮循环(maxToolCalls 限制)**|
 * | 系统提示词 | 用子助手 systemPrompt      | 用子助手 systemPrompt                   | **子 agent 专用 prompt(专注委托)**|
 * | 进度反馈   | TaskState.progress 字符串 | 无                                     | **XML 协议三阶段(start/progress/result)**|
 * | 续接能力   | 支持(reply/close)         | 不支持                                  | **v1.0.53 起支持(threadId 续接)**|
 *
 * P2-1 的核心价值:主 agent 把"需要多步工具调用"的子任务委托给子 agent,
 * 子 agent 在独立上下文里跑完整工具循环,主 agent 等待并收到结构化 XML 结果。
 * 这样主 agent 上下文不被工具调用历史污染,适合"调研类"/"检索类"子任务。
 *
 * 安全约束:
 *  - 工具白名单:只允许 SAFE + NORMAL 风险工具,禁止 HIGH
 *  - 递归防护:禁止 subagent_run / subagent_task / delegate_agent(避免子 agent 再委派导致递归爆炸)
 *  - 配额限制:maxToolCalls 上限(默认 8,硬上限 20),达到后强制停止并让 LLM 总结
 *  - 超时限制:整体执行超时(默认 120s)
 *
 * @param chatService LLM 调用服务(非流式 completeText)
 * @param toolRegistry 工具注册表(执行工具 + 生成 ToolDefinition)
 * @param threadStore v1.0.53: 子 agent 线程账本(持久化版,统一重构)
 * @param concurrencyLimiter v1.0.53 Phase 0: 全局并发限流器
 */
class SubagentRunner(
    private val chatService: ChatService,
    private val toolRegistry: ToolRegistry,
    private val threadStore: io.zer0.muse.data.subagent.SubagentThreadStore,
    private val concurrencyLimiter: AgentConcurrencyLimiter,
    /** v1.0.53 Phase 4: 工具审批策略存储(用于 deny_on_prompt)。 */
    private val toolConfigStore: ToolConfigStore,
) {

    companion object {
        private const val TAG = "SubagentRunner"

        /** 默认最大工具调用次数(参考 Operit examples/subagent)。 */
        const val DEFAULT_MAX_TOOL_CALLS = 8

        /** 工具调用次数硬上限(防止 LLM 失控跑满配额)。 */
        const val MAX_TOOL_CALLS_HARD_CAP = 20

        /** 默认整体执行超时(毫秒)。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 单轮 LLM 调用的最大 token 数。 */
        const val MAX_TOKENS_PER_ROUND = 1500

        /** 子 agent 递归防护:禁止子 agent 再调用的工具(避免无限递归)。 */
        private val RECURSIVE_FORBIDDEN_TOOLS: Set<String> = setOf(
            "subagent_run",
            "subagent_task",
            "delegate_agent",
            "task_plan",
            "update_plan_step",
            // v1.0.53: 子 agent 不允许关闭线程(防自关,关闭后自身无法续接)
            "subagent_close",
        )

        /**
         * 显式禁止的高风险工具(HIGH 风险中特别危险的)。
         *
         * 即使 ToolRiskLevel 标注为 NORMAL 的也在此列的,是因为它们可能
         * 破坏系统状态或执行任意代码,不适合子 agent 使用。
         */
        private val EXPLICIT_FORBIDDEN_TOOLS: Set<String> = setOf(
            // 递归委派(已在 RECURSIVE_FORBIDDEN_TOOLS)
            // 代码执行
            "execute_javascript",
            // Skill 管理(子 agent 不应安装/卸载 skill)
            "install_skill",
            "uninstall_skill",
            "disable_skill",
            "list_skills",
            // 浏览器自动化(子 agent 不应操作浏览器,UI 状态隔离)
            "browser_navigate",
            "browser_click",
            "browser_type",
            "browser_extract",
            "browser_scroll_bottom",
            "browser_get_html",
            // 群聊(子 agent 不应参与群聊)
            "channel_reply",
            "channel_pass",
            "channel_read_context",
            // 通知(子 agent 不应发通知,避免干扰用户)
            "notify",
            "show_card",
            // 表情包(子 agent 不应发表情包)
            "send_sticker",
            // 生成图片(子 agent 不应生成图片,耗时且占用资源)
            "generate_image",
        )
    }

    /** 子 agent 运行参数。 */
    data class Params(
        /** 委托任务描述(必填)。 */
        val task: String,
        /** 限制条件/验收标准(可选,作为 user 消息的额外段落)。 */
        val contextText: String? = null,
        /** 优先文件路径提示(可选,在 systemPrompt 中提示子 agent 优先查看)。 */
        val targetPaths: List<String>? = null,
        /** 最大工具调用次数(默认 8,硬上限 20)。 */
        val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
        /** 整体执行超时毫秒(默认 120s)。 */
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /** 温度(默认 0.3,子 agent 偏向确定性执行)。 */
        val temperature: Float = 0.3f,
        // v1.0.53 Phase 1: 续接 + 持久化参数(全部可选,向后兼容)
        /** 续接的线程 id;非空时恢复该线程的子会话历史继续执行。 */
        val threadId: String? = null,
        /** 是否记录会话历史到磁盘(供续接)。默认 true。 */
        val persistSession: Boolean = true,
        /** 子 agent 访问级别(read=只读,write=可写)。影响工具白名单(预留,当前未差异化)。 */
        val access: String = "read",
        /** 是否在执行完成后关闭线程(一次性委派场景)。默认 false。 */
        val closeAfterRun: Boolean = false,
        /** v1.0.53 Phase 3: token 预算上限(prompt + completion 合计);null=不限制。 */
        val tokenBudget: Int? = null,
    )

    /** 子 agent 运行结果。 */
    data class Result(
        /** 是否成功完成(无异常且产出了总结文本)。 */
        val success: Boolean,
        /** 最终总结文本(子 agent 最后一次非工具调用的输出)。 */
        val summary: String,
        /** 失败原因(success=false 时非空)。 */
        val error: String? = null,
        /** 总轮次(每轮 = 一次 LLM 调用)。 */
        val rounds: Int = 0,
        /** 总工具调用次数。 */
        val toolCalls: Int = 0,
        /** 是否因达到 maxToolCalls 而截断。 */
        val budgetExhausted: Boolean = false,
        /** 每轮工具调用的进度记录(用于 XML 渲染)。 */
        val progressEntries: List<ProgressEntry> = emptyList(),
        /** v1.0.53: 本次执行所属线程 id(新线程时由 threadStore 生成)。 */
        val threadId: String? = null,
        /** v1.0.53: 子会话文件路径(供续接/诊断)。 */
        val sessionPath: String? = null,
        /** v1.0.53 Phase 3: 是否因 token 预算耗尽而截断(与 [budgetExhausted] 的 maxToolCalls 截断区分)。 */
        val tokenBudgetExhausted: Boolean = false,
    )

    /** 单轮工具调用进度记录。 */
    data class ProgressEntry(
        val round: Int,
        val toolName: String,
        val argsJson: String,
        val result: String,
        val success: Boolean,
    )

    /**
     * 运行子 agent 工具循环。
     *
     * v1.0.53 流程升级:
     *  0. 解析/创建线程(threadStore.getOrCreate)— 续接时恢复历史
     *  1. 全局并发限流(concurrencyLimiter.run)+ 同 thread 串行化(runSerialized)
     *  2. 构造上下文(续接时 loadMessages 恢复;全新时 system + user)
     *  3. 循环调用 completeText,若返回 toolCalls 则执行工具并回填结果
     *  4. 每轮增量持久化到 JSONL(appendMessages),App 重启后可续接
     *  5. 直到 LLM 不再调用工具 / 达到 maxToolCalls / 超时 / 异常
     *  6. 收尾:recordRun 更新账本;可选 closeAfterRun 关闭线程
     *  7. 返回最终总结 + 进度记录 + threadId/sessionPath
     *
     * @param params 运行参数(含 v1.0.53 续接参数 threadId/persistSession/closeAfterRun)
     * @return 运行结果(含总结、进度、统计、threadId、sessionPath)
     */
    suspend fun run(params: Params): Result {
        val task = params.task.trim()
        if (task.isBlank()) {
            return Result(success = false, summary = "", error = "任务描述为空")
        }

        // v1.0.53: 解析/创建线程(统一账本)
        val (threadId, isNew) = try {
            threadStore.getOrCreate(
                threadId = params.threadId,
                parentSessionId = "subagent_run",  // 路径 B 无父会话,用占位
                assistantId = "passive_subagent",
                access = params.access,
            )
        } catch (e: Exception) {
            Logger.w(TAG, "线程解析失败: ${e.message}")
            return Result(success = false, summary = "", error = "线程解析失败: ${e.message}")
        }
        val sessionPath = threadStore.sessionPathOf(threadId)

        // v1.0.53: 串行化 + 全局并发限流(同 threadId 排队,且整体走 limiter)
        return try {
            val result = concurrencyLimiter.run {
                threadStore.runSerialized(threadId) {
                    runInternal(params, threadId, isNew)
                }
            }
            // 统一补 threadId/sessionPath(runInternal 内部不关心这两个字段)
            result.copy(threadId = threadId, sessionPath = sessionPath)
        } catch (e: IllegalStateException) {
            // 线程已关闭(runSerialized 抛) / limiter backstop 超 maxTotal
            Logger.w(TAG, "子 agent 被拒绝执行: ${e.message}")
            Result(
                success = false,
                summary = "",
                error = e.message ?: "子 agent 执行被拒绝",
                threadId = threadId,
                sessionPath = sessionPath,
            )
        }
    }

    /**
     * v1.0.53: 内部执行循环(在 limiter + runSerialized 包裹内调用)。
     *
     * 与原 run() 的区别:
     *  - 续接时从 [threadStore.loadMessages] 恢复历史,而非每次新建上下文
     *  - 每轮工具调用后 [threadStore.appendMessages] 增量持久化
     *  - 收尾 [recordRunAndMaybeClose] 更新线程账本
     */
    private suspend fun runInternal(params: Params, threadId: String, isNew: Boolean): Result {
        val maxToolCalls = params.maxToolCalls.coerceIn(1, MAX_TOOL_CALLS_HARD_CAP)
        val allowedTools = buildAllowedToolDefinitions()
        val systemPrompt = buildSystemPrompt(maxToolCalls, params.targetPaths)

        val history = mutableListOf<UIMessage>()
        // v1.0.53: 续接时恢复历史;全新执行时构造 system + user
        if (!isNew && params.threadId != null) {
            val restored = threadStore.loadMessages(threadId)
            if (restored.isNotEmpty()) {
                history.addAll(restored)
                // 续接时在历史后追加一条 user 消息(新任务)
                history.add(UIMessage(role = MessageRole.USER, content = buildUserMessage(params)))
            } else {
                // 线程存在但无历史(文件被清理):回退全新执行
                history.add(UIMessage(role = MessageRole.SYSTEM, content = systemPrompt))
                history.add(UIMessage(role = MessageRole.USER, content = buildUserMessage(params)))
            }
        } else {
            // 全新执行(原逻辑)
            history.add(UIMessage(role = MessageRole.SYSTEM, content = systemPrompt))
            history.add(UIMessage(role = MessageRole.USER, content = buildUserMessage(params)))
        }

        val progressEntries = mutableListOf<ProgressEntry>()
        var rounds = 0
        var totalToolCalls = 0
        var lastText = ""
        var budgetExhausted = false
        // v1.0.53 Phase 3: token 预算(null=不限制)
        val tokenBudget = AgentTokenBudget.of(params.tokenBudget)
        var tokenBudgetExhausted = false

        // v1.0.53 Phase 3: 总结轮次(预算/配额耗尽时强制让 LLM 产出总结文本,保证有输出)。
        // 不带 tools,强制纯文本输出;同时累加 token 消耗(总结轮也计费)。
        suspend fun summarize() {
            rounds++
            val summaryCompletion = chatService.completeText(
                messages = history.toList(),
                temperature = params.temperature,
                maxTokens = MAX_TOKENS_PER_ROUND,
                tools = null,
                reasoningLevel = ReasoningLevel.OFF,
                mode = ChatRequestMode.UTILITY,
            )
            tokenBudget?.accumulate(summaryCompletion.usageTokens)
            lastText = summaryCompletion.text.trim()
            if (params.persistSession && lastText.isNotBlank()) {
                threadStore.appendMessages(threadId, listOf(UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = lastText,
                )))
            }
        }

        try {
            // 整体超时包裹
            val timedOut = withTimeoutOrNull(params.timeoutMs) {
                while (totalToolCalls < maxToolCalls) {
                    rounds++
                    val completion = chatService.completeText(
                        messages = history.toList(),
                        temperature = params.temperature,
                        maxTokens = MAX_TOKENS_PER_ROUND,
                        tools = allowedTools,
                        reasoningLevel = ReasoningLevel.OFF,
                        mode = ChatRequestMode.UTILITY,
                    )

                    // v1.0.53 Phase 3: 累加本轮 token 消耗(null=Provider 未返回,跳过)
                    tokenBudget?.accumulate(completion.usageTokens)

                    val toolCalls = completion.toolCalls
                    // 无工具调用 → LLM 给出最终总结,结束循环
                    if (toolCalls.isNullOrEmpty()) {
                        lastText = completion.text.trim()
                        // v1.0.53: 持久化最终总结
                        if (params.persistSession && lastText.isNotBlank()) {
                            threadStore.appendMessages(threadId, listOf(UIMessage(
                                role = MessageRole.ASSISTANT,
                                content = lastText,
                            )))
                        }
                        break
                    }

                    // v1.0.48 对齐:过滤无效 toolCalls(空 name 或空 arguments)
                    val validToolCalls = toolCalls.filter { tc ->
                        tc.name.isNotBlank() && tc.arguments.isNotBlank()
                    }
                    if (validToolCalls.isEmpty()) {
                        // 全部无效,取 text 作为总结
                        lastText = completion.text.trim()
                        if (params.persistSession && lastText.isNotBlank()) {
                            threadStore.appendMessages(threadId, listOf(UIMessage(
                                role = MessageRole.ASSISTANT,
                                content = lastText,
                            )))
                        }
                        break
                    }

                    // v1.0.53 Phase 3: token 预算耗尽 → 不执行工具,直接进入总结路径(避免消耗更多 token)
                    if (tokenBudget?.isExhausted == true) {
                        tokenBudgetExhausted = true
                        summarize()
                        break
                    }

                    // 把 assistant 消息(含 toolCalls)加入历史
                    val assistantMsg = UIMessage(
                        role = MessageRole.ASSISTANT,
                        content = completion.text,
                        toolCalls = validToolCalls,
                    )
                    history.add(assistantMsg)
                    // v1.0.53: 收集本轮增量消息(assistant + 各 tool results)用于持久化
                    val roundMessages = mutableListOf(assistantMsg)

                    // 逐个执行工具并回填结果
                    for (tc in validToolCalls) {
                        totalToolCalls++
                        val toolResult = executeAllowedTool(tc)
                        progressEntries.add(ProgressEntry(
                            round = rounds,
                            toolName = tc.name,
                            argsJson = tc.arguments,
                            result = toolResult.result,
                            success = toolResult.success,
                        ))
                        // 把工具结果作为 TOOL 消息加入历史
                        val toolMsg = UIMessage(
                            role = MessageRole.TOOL,
                            content = toolResult.result,
                            toolCallId = tc.id,
                        )
                        history.add(toolMsg)
                        roundMessages.add(toolMsg)

                        // 达到配额立即停止(不再执行剩余 toolCalls)
                        if (totalToolCalls >= maxToolCalls) {
                            budgetExhausted = true
                            break
                        }
                    }

                    // v1.0.53: 持久化本轮增量(assistant + tool results)
                    if (params.persistSession) {
                        threadStore.appendMessages(threadId, roundMessages)
                    }

                    if (budgetExhausted) {
                        // 配额耗尽,调一次 LLM 总结(不带 tools,强制纯文本输出)
                        summarize()
                        break
                    }
                }
            }

            // timedOut == null 表示整体超时
            if (timedOut == null) {
                recordRunAndMaybeClose(params, threadId, status = "aborted", summary = lastText)
                return Result(
                    success = false,
                    summary = lastText,
                    error = "子 agent 执行超时(${params.timeoutMs}ms)",
                    rounds = rounds,
                    toolCalls = totalToolCalls,
                    budgetExhausted = budgetExhausted,
                    progressEntries = progressEntries,
                    tokenBudgetExhausted = tokenBudgetExhausted,
                )
            }

            // 配额未耗尽但 lastText 为空(可能是第一轮就无 toolCalls 且 text 为空)
            if (lastText.isBlank() && progressEntries.isEmpty()) {
                recordRunAndMaybeClose(params, threadId, status = "failed", summary = "")
                return Result(
                    success = false,
                    summary = "",
                    error = "子 agent 未产出任何内容",
                    rounds = rounds,
                    toolCalls = totalToolCalls,
                    budgetExhausted = false,
                    progressEntries = progressEntries,
                )
            }

            recordRunAndMaybeClose(params, threadId, status = "resolved", summary = lastText)
            return Result(
                success = true,
                summary = lastText,
                rounds = rounds,
                toolCalls = totalToolCalls,
                budgetExhausted = budgetExhausted,
                progressEntries = progressEntries,
                tokenBudgetExhausted = tokenBudgetExhausted,
            )
        } catch (e: Exception) {
            Logger.w(TAG, "子 agent 执行异常: task=${params.task.take(80)}", e)
            recordRunAndMaybeClose(params, threadId, status = "failed", summary = lastText)
            return Result(
                success = false,
                summary = lastText,
                error = e.message ?: e.javaClass.simpleName,
                rounds = rounds,
                toolCalls = totalToolCalls,
                budgetExhausted = budgetExhausted,
                progressEntries = progressEntries,
                tokenBudgetExhausted = tokenBudgetExhausted,
            )
        }
    }

    /**
     * v1.0.53: 收尾 — 记录 run 结果到线程账本 + 可选关闭线程。
     *
     * @param status resolved|failed|aborted
     */
    private suspend fun recordRunAndMaybeClose(
        params: Params,
        threadId: String,
        status: String,
        summary: String,
    ) {
        threadStore.recordRun(
            threadId = threadId,
            status = status,
            summary = summary.takeIf { it.isNotBlank() },
            sessionPath = null,  // childSessionPath 已在 getOrCreate 时记录
        )
        if (params.closeAfterRun) {
            threadStore.close(threadId)
        }
    }

    /**
     * 构建允许子 agent 使用的工具定义列表。
     *
     * 过滤规则:
     *  1. 风险等级 = SAFE 或 NORMAL(禁止 HIGH)
     *  2. 不在 [RECURSIVE_FORBIDDEN_TOOLS] 递归黑名单中
     *  3. 不在 [EXPLICIT_FORBIDDEN_TOOLS] 显式黑名单中
     */
    private fun buildAllowedToolDefinitions(): List<ToolDefinition> {
        val allowedDefs = toolRegistry.listTools().filter { def ->
            def.riskLevel != ToolRiskLevel.HIGH &&
                def.name !in RECURSIVE_FORBIDDEN_TOOLS &&
                def.name !in EXPLICIT_FORBIDDEN_TOOLS
        }
        // 转换为 ToolDefinition(供 completeText(tools=...) 使用)
        return toolRegistry.listToolsAsToolDefinitions(allowedDefs.map { it.name })
    }

    /**
     * 执行单个工具调用(已通过白名单过滤)。
     *
     * 若工具名不在白名单(被 LLM 幻觉调用),返回错误字符串而非执行。
     */
    private suspend fun executeAllowedTool(tc: ToolCall): ToolExecOutcome {
        val allowedNames = buildAllowedToolNames()
        if (tc.name !in allowedNames) {
            return ToolExecOutcome(
                result = "Error: 工具 '${tc.name}' 不在子 agent 允许的工具列表中",
                success = false,
            )
        }
        // v1.0.53 Phase 4: deny_on_prompt 审批策略
        // 子 agent 无权请求用户审批(不能弹窗)。遇到需审批(ASK_EVERY_TIME)或被禁用(ALWAYS_DENY)
        // 的工具直接拒绝并向主 agent 说明原因,让主 agent 改用允许的工具或向用户说明需手动处理。
        when (toolConfigStore.getPolicy(tc.name)) {
            ToolApprovalPolicy.ALWAYS_DENY -> return ToolExecOutcome(
                result = "Error: 工具 '${tc.name}' 已被用户禁用,子 agent 无权调用",
                success = false,
            )
            ToolApprovalPolicy.ASK_EVERY_TIME -> return ToolExecOutcome(
                result = "Error: 工具 '${tc.name}' 需要用户审批,子 agent 无权请求审批。" +
                    "请改用允许的工具,或向用户说明此操作需要手动处理。",
                success = false,
            )
            ToolApprovalPolicy.ALWAYS_ALLOW -> { /* 继续执行 */ }
        }
        return try {
            val result = toolRegistry.executeFromJson(tc.name, tc.arguments)
            // 判断成功:结果不以 "Error:" / "失败" 开头视为成功
            val success = !result.startsWith("Error:") && !result.contains("执行异常")
            ToolExecOutcome(result = result, success = success)
        } catch (e: Exception) {
            ToolExecOutcome(
                result = "Error: ${e.message ?: e.javaClass.simpleName}",
                success = false,
            )
        }
    }

    /** 缓存允许的工具名集合(同一轮内复用)。 */
    private fun buildAllowedToolNames(): Set<String> {
        return toolRegistry.listTools()
            .filter { def ->
                def.riskLevel != ToolRiskLevel.HIGH &&
                    def.name !in RECURSIVE_FORBIDDEN_TOOLS &&
                    def.name !in EXPLICIT_FORBIDDEN_TOOLS
            }
            .map { it.name }
            .toSet()
    }

    /** 工具执行结果。 */
    private data class ToolExecOutcome(
        val result: String,
        val success: Boolean,
    )

    /**
     * 构造子 agent 系统提示词。
     *
     * 子 agent 专用 prompt(不复用主 agent 的 systemPrompt),强调:
     *  - 独立上下文,专注委托任务
     *  - 工具调用配额限制
     *  - 完成后输出清晰总结
     *  - 禁止递归委派
     */
    private fun buildSystemPrompt(maxToolCalls: Int, targetPaths: List<String>?): String {
        return buildString {
            appendLine("你是一个被动子 agent(Passive Subagent),由主 agent 委托执行独立子任务。")
            appendLine()
            appendLine("## 工作准则")
            appendLine("1. 你有独立的上下文,看不到主对话历史,只看到委托给你的任务描述")
            appendLine("2. 你可以使用提供的工具完成任务,最多 $maxToolCalls 次工具调用")
            appendLine("3. 工具调用要精炼,避免不必要的重复;优先用最少调用完成任务")
            appendLine("4. 完成任务后,输出清晰的总结,包含:")
            appendLine("   - 任务完成情况(已完成/部分完成/无法完成)")
            appendLine("   - 关键发现或结果")
            appendLine("   - 遇到的问题(如有)")
            appendLine("5. 如果任务无法完成,明确说明原因,不要编造结果")
            appendLine("6. 禁止调用 subagent_run / subagent_task / delegate_agent 等递归委派工具")
            appendLine("7. 用中文输出总结")
            appendLine()
            if (!targetPaths.isNullOrEmpty()) {
                appendLine("## 优先查看的文件路径")
                targetPaths.forEach { appendLine("- $it") }
                appendLine()
            }
        }
    }

    /** 构造 user 消息(task + contextText)。 */
    private fun buildUserMessage(params: Params): String {
        return buildString {
            appendLine("## 任务")
            appendLine(params.task.trim())
            if (!params.contextText.isNullOrBlank()) {
                appendLine()
                appendLine("## 限制条件 / 验收标准")
                appendLine(params.contextText.trim())
            }
        }
    }
}
