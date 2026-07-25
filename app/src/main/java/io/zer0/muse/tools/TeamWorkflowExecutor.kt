package io.zer0.muse.tools

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * v1.200: 团队工作流执行器。
 *
 * 支持三种编排模式:
 * - 串行(SEQUENTIAL):按节点顺序依次执行,前序结果作为后序上下文
 * - 并行(PARALLEL):无依赖节点同时执行,最后按聚合策略合并
 * - 条件(CONDITIONAL):根据前序结果判断是否执行(当前实现为简单开关)
 *
 * 若团队未配置工作流,则退化到按 memberIds 顺序串行执行。
 *
 * v1.201: 新增 [llmAggregator] 参数,LLM_REVIEW 聚合策略时由 [LlmAggregator.review]
 * 完成综合评审;llmAggregator 为 null 时 LLM_REVIEW 降级为 EXPERT_REVIEW。
 * v1.201: 新增 [pauseManager] + [pausePolicy] 参数,支持团队工作流执行前/每个成员执行前的
 * 人机协作暂停点;pauseManager 为 null 时跳过所有暂停点。
 *
 * v1.202:
 *  - 新增 [delegationChainTracker] 参数,执行每个成员节点时把子节点状态同步到链路追踪器,
 *    使 UI 链路卡片能展示团队工作流的树形结构(parentRequestId 机制自动把子节点插入父节点 subNodes)。
 *  - 新增 [chatService] 参数,CONDITIONAL 节点执行前用 LLM 判断"根据前置结果是否应该执行此节点",
 *    回答 NO 则跳过(标记为 skipped);chatService 为 null 时 CONDITIONAL 降级为旧逻辑(直接执行)。
 *  - 工作流完成后把结果树构造成 [DelegationContract.DelegationResult.subResultTree]。
 */
class TeamWorkflowExecutor(
    /** 执行单个委派请求的回调,通常传入 SkillExecutor::delegateAgent。 */
    private val delegate: suspend (DelegationContract.DelegationRequest) -> DelegationContract.DelegationResult,
    /** v1.201: LLM 综合评审聚合器,LLM_REVIEW 策略时使用;为 null 时降级为 EXPERT_REVIEW。 */
    private val llmAggregator: LlmAggregator? = null,
    /** v1.201: 委派暂停管理器,null 时跳过所有暂停点。 */
    private val pauseManager: DelegationPauseManager? = null,
    /** v1.201: 暂停策略,仅在 pauseManager 非 null 时生效。 */
    private val pausePolicy: DelegationPauseManager.PausePolicy = DelegationPauseManager.PausePolicy(),
    /** v1.202: 委派链路追踪器,把团队成员执行状态同步到 UI 链路卡片;为 null 时不记录链路。 */
    private val delegationChainTracker: DelegationChainTracker? = null,
    /** v1.202: ChatService,用于 CONDITIONAL 节点的 LLM 条件判断;为 null 时 CONDITIONAL 节点降级为直接执行。 */
    private val chatService: ChatService? = null,
) {

    /**
     * v1.202: CONDITIONAL 节点判断为 NO 时记录的跳过 requestId 集合。
     * 用 ConcurrentHashMap 支撑的 Set,async 并发写入安全(对齐 executed 的 H-TWE1 模式)。
     * TeamWorkflowExecutor 在 SkillExecutor 中每次执行都新建实例,故无需 clear。
     */
    private val skippedRequestIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * 执行团队工作流。
     *
     * @param workflow 工作流定义
     * @param teamTask 团队总体任务描述
     * @param parentRequestId 父请求 id,用于生成子请求 id
     * @param teamMembers 团队成员 assistantId 列表,作为 workflow.nodes 为空时的退化顺序
     * @param baseContext 共享上下文消息(可选)
     * @return 汇总后的委派结果,subResults 包含每个节点的结果
     */
    suspend fun execute(
        workflow: DelegationContract.TeamWorkflow,
        teamTask: String,
        parentRequestId: String,
        teamMembers: List<String>,
        baseContext: List<UIMessage> = emptyList(),
    ): DelegationContract.DelegationResult {
        val startedAt = System.currentTimeMillis()
        val nodes = workflow.nodes.takeIf { it.isNotEmpty() }
            ?: teamMembers.mapIndexed { idx, assistantId ->
                DelegationContract.TeamWorkflowNode(
                    id = "step-$idx",
                    assistantId = assistantId,
                    name = "团队步骤 $idx",
                    taskTemplate = teamTask,
                )
            }

        if (nodes.isEmpty()) {
            return errorResult(parentRequestId, "团队没有成员,无法执行工作流", startedAt)
        }

        // 校验 assistantId
        val invalidNodes = nodes.filter { it.assistantId.isBlank() }
        if (invalidNodes.isNotEmpty()) {
            return errorResult(parentRequestId, "工作流节点缺少 assistantId: ${invalidNodes.map { it.id }}", startedAt)
        }

        // H-TWE1: 用 ConcurrentHashMap 替代 mutableMapOf,async 并发写入安全
        val executed = java.util.concurrent.ConcurrentHashMap<String, DelegationContract.DelegationResult>()
        val errors = mutableListOf<String>()

        try {
            // v1.201: 团队执行前暂停(pauseBeforeTeam)
            if (pauseManager != null && pausePolicy.pauseBeforeTeam) {
                if (pauseManager.isCancelled(parentRequestId)) {
                    return errorResult(parentRequestId, "团队工作流被用户取消", startedAt)
                }
                val pauseReq = DelegationPauseManager.PauseRequest(
                    requestId = "pause-$parentRequestId-before-team",
                    taskId = parentRequestId,
                    taskTitle = "团队工作流执行前确认",
                    taskDescription = teamTask,
                    targetType = "team",
                    targetName = parentRequestId,
                    reason = "团队工作流执行前确认",
                    options = listOf(
                        DelegationPauseManager.PauseOption.APPROVE,
                        DelegationPauseManager.PauseOption.REJECT,
                        DelegationPauseManager.PauseOption.CANCEL,
                    ),
                )
                val resp = pauseManager.awaitPauseDecision(pauseReq, pausePolicy)
                when (resp.decision) {
                    DelegationPauseManager.PauseDecision.CANCEL,
                    DelegationPauseManager.PauseDecision.REJECT ->
                        return errorResult(parentRequestId, "用户取消团队工作流", startedAt)
                    DelegationPauseManager.PauseDecision.APPROVE,
                    DelegationPauseManager.PauseDecision.MODIFY -> { /* 继续 */ }
                }
            }

            // 按依赖拓扑分层:无依赖的先执行,完成后解锁依赖它的节点
            val pending = nodes.toMutableList()
            while (pending.isNotEmpty()) {
                val ready = pending.filter { node ->
                    node.dependsOn.all { executed.containsKey(it) }
                }
                if (ready.isEmpty()) {
                    return errorResult(
                        parentRequestId,
                        "工作流存在循环依赖或无法解析的依赖: ${pending.map { it.id }}",
                        startedAt,
                    )
                }

                // v1.201: 每个成员执行前暂停(pauseBeforeEachMember)
                if (pauseManager != null && pausePolicy.pauseBeforeEachMember) {
                    if (pauseManager.isCancelled(parentRequestId)) {
                        return errorResult(parentRequestId, "团队工作流被用户取消", startedAt)
                    }
                    ready.forEach { node ->
                        val pauseReq = DelegationPauseManager.PauseRequest(
                            requestId = "pause-$parentRequestId-${node.id}",
                            taskId = "$parentRequestId/${node.id}",
                            taskTitle = node.name.ifBlank { node.id },
                            taskDescription = node.taskTemplate.ifBlank { teamTask },
                            targetType = "assistant",
                            targetName = node.assistantId,
                            reason = "团队成员 ${node.name.ifBlank { node.id }} 执行前确认",
                            options = listOf(
                                DelegationPauseManager.PauseOption.APPROVE,
                                DelegationPauseManager.PauseOption.REJECT,
                                DelegationPauseManager.PauseOption.CANCEL,
                            ),
                        )
                        val resp = pauseManager.awaitPauseDecision(pauseReq, pausePolicy)
                        when (resp.decision) {
                            DelegationPauseManager.PauseDecision.CANCEL ->
                                return errorResult(parentRequestId, "用户取消团队工作流", startedAt)
                            DelegationPauseManager.PauseDecision.REJECT -> {
                                // 跳过该节点,标记为失败
                                executed[node.id] = errorResult(
                                    "$parentRequestId/${node.id}",
                                    "用户拒绝执行该节点",
                                    System.currentTimeMillis(),
                                )
                                pending.remove(node)
                            }
                            DelegationPauseManager.PauseDecision.APPROVE,
                            DelegationPauseManager.PauseDecision.MODIFY -> { /* 继续执行 */ }
                        }
                    }
                    // 如果所有 ready 节点都被拒绝,继续下一轮
                    if (ready.all { executed.containsKey(it.id) }) {
                        pending.removeAll(ready)
                        continue
                    }
                }

                // 同一层节点按 mode 决定串行或并行
                val sequential = ready.first().mode == DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL
                val layerResults: List<DelegationContract.DelegationResult> = if (sequential) {
                    ready.map { node ->
                        executed[node.id] ?: executeNode(node, teamTask, parentRequestId, baseContext, executed).also {
                            executed[node.id] = it
                        }
                    }
                } else {
                    executeParallel(ready, teamTask, parentRequestId, baseContext, executed).also {
                        executed.putAll(it)
                    }.values.toList()
                }

                errors.addAll(layerResults.flatMap { it.collectErrors() })
                pending.removeAll(ready)
            }

            val orderedResults = nodes.mapNotNull { executed[it.id] }
            val resultText = aggregateResults(workflow.aggregationStrategy, orderedResults, teamTask)
            val finishedAt = System.currentTimeMillis()

            // v1.202: 构造子结果树,供 UI 树形展示团队工作流成员执行状态
            val subResultTree = buildSubResultTree(nodes, orderedResults, teamTask)

            return DelegationContract.DelegationResult(
                requestId = parentRequestId,
                success = errors.isEmpty(),
                resultText = resultText,
                error = errors.firstOrNull(),
                metadata = DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    durationMs = finishedAt - startedAt,
                ),
                subResults = orderedResults,
                subResultTree = subResultTree,
            )
        } catch (e: Exception) {
            return errorResult(parentRequestId, "工作流执行异常: ${e.message}", startedAt)
        }
    }

    private suspend fun executeNode(
        node: DelegationContract.TeamWorkflowNode,
        teamTask: String,
        parentRequestId: String,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): DelegationContract.DelegationResult {
        val childRequestId = "$parentRequestId/${node.id}"
        val task = buildNodeTask(node, teamTask, executed)

        // v1.202 改造 2: CONDITIONAL 节点真条件判断
        // - 仅当 mode==CONDITIONAL 且有前置依赖且 chatService 可用时,才做 LLM 判断
        // - LLM 回答 NO 则跳过此节点(记录为 skipped),不执行 delegate
        // - chatService 为 null 或 LLM 调用异常时降级为直接执行(避免误跳过)
        if (node.mode == DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL
            && node.dependsOn.isNotEmpty()
            && chatService != null
        ) {
            val shouldExecute = evaluateConditional(node, executed)
            if (!shouldExecute) {
                // 标记为 skipped:resultText 留空,自然被 aggregateResults 的 isNotBlank() 过滤
                skippedRequestIds.add(childRequestId)
                // 同步到链路追踪器:开始 + 立即结束(skipped 状态)
                // 用 "(已跳过)" 作为结果预览,UI 卡片可直接展示跳过原因
                delegationChainTracker?.onDelegationStarted(
                    requestId = childRequestId,
                    parentRequestId = parentRequestId,
                    task = task,
                    targetType = "assistant",
                    targetId = node.assistantId,
                    targetName = node.name.ifBlank { node.id },
                )
                delegationChainTracker?.onDelegationFinished(
                    requestId = childRequestId,
                    success = true,
                    resultText = "(已跳过: 条件不满足)",
                )
                return skippedResult(childRequestId)
            }
        }

        val contextMessages = buildNodeContext(node, baseContext, executed)

        // v1.202 改造 1: 通知链路追踪器委派开始
        // parentRequestId 机制会自动把此子节点插入父节点的 subNodes
        delegationChainTracker?.onDelegationStarted(
            requestId = childRequestId,
            parentRequestId = parentRequestId,
            task = task,
            targetType = "assistant",
            targetId = node.assistantId,
            targetName = node.name.ifBlank { node.id },
        )

        val request = DelegationContract.DelegationRequest(
            requestId = childRequestId,
            task = task,
            targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
            targetId = node.assistantId,
            parentSessionId = parentRequestId,
            contextMessages = contextMessages,
            timeoutSec = 120,
        )
        val result = delegate(request)

        // v1.202 改造 1: 通知链路追踪器委派完成,更新子节点状态
        delegationChainTracker?.onDelegationFinished(
            requestId = childRequestId,
            success = result.success,
            resultText = result.resultText,
            error = result.error,
        )

        return result
    }

    private suspend fun executeParallel(
        nodes: List<DelegationContract.TeamWorkflowNode>,
        teamTask: String,
        parentRequestId: String,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): Map<String, DelegationContract.DelegationResult> = coroutineScope {
        nodes.associate { node ->
            node.id to async {
                executeNode(node, teamTask, parentRequestId, baseContext, executed)
            }
        }.mapValues { it.value.await() }
    }

    private fun buildNodeTask(
        node: DelegationContract.TeamWorkflowNode,
        teamTask: String,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): String {
        val base = node.taskTemplate.ifBlank { teamTask }
        if (node.dependsOn.isEmpty()) return base
        val dependencySummary = node.dependsOn.joinToString("\n\n") { depId ->
            val dep = executed[depId]
            val header = "## 前置步骤 $depId 结果"
            when {
                dep == null -> "$header: (未执行)"
                dep.success -> "$header:\n${dep.resultText.take(2000)}"
                else -> "$header:\n错误: ${dep.error ?: "未知错误"}"
            }
        }
        return """$base

$dependencySummary""".trimIndent()
    }

    private fun buildNodeContext(
        node: DelegationContract.TeamWorkflowNode,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): List<UIMessage> {
        val messages = baseContext.toMutableList()
        if (node.mode == DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL && node.dependsOn.isNotEmpty()) {
            // 条件节点:把前置成功结果作为 system 提示注入,辅助判断是否执行/如何执行
            val conditionContext = node.dependsOn.mapNotNull { executed[it] }
                .filter { it.success }
                .joinToString("\n") { "[${it.requestId}]: ${it.resultText.take(500)}" }
            if (conditionContext.isNotBlank()) {
                messages.add(
                    UIMessage(
                        role = MessageRole.SYSTEM,
                        content = "以下条件成立时继续执行:\n$conditionContext",
                    ),
                )
            }
        }
        return messages
    }

    private suspend fun aggregateResults(
        strategy: DelegationContract.TeamWorkflow.AggregationStrategy,
        results: List<DelegationContract.DelegationResult>,
        teamTask: String,
    ): String {
        val successful = results.filter { it.success && it.resultText.isNotBlank() }
        if (successful.isEmpty()) return "(无可用结果)"

        return when (strategy) {
            DelegationContract.TeamWorkflow.AggregationStrategy.MERGE -> {
                buildString {
                    appendLine("团队执行结果汇总:")
                    successful.forEachIndexed { idx, r ->
                        appendLine()
                        appendLine("--- 子结果 ${idx + 1} [${r.metadata.assistantName ?: r.metadata.assistantId ?: r.requestId}] ---")
                        appendLine(r.resultText)
                    }
                }.trimEnd()
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.VOTE -> {
                // 简单投票:结果去重后统计频次,返回最高频结果
                val votes = successful.groupingBy { it.resultText }.eachCount()
                val best = votes.maxByOrNull { it.value }
                "投票结果(共 ${successful.size} 票): 最高频结果出现 ${best?.value} 次\n\n${best?.key ?: ""}"
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.EXPERT_REVIEW -> {
                // 专家评审:取结果最长的作为"详尽版"返回(后续可接入 LLM 综合评审)
                val expert = successful.maxByOrNull { it.resultText.length }
                "专家评审选定结果:\n\n${expert?.resultText ?: ""}"
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.FIRST_SUCCESS -> {
                successful.first().resultText
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.LLM_REVIEW -> {
                // v1.201: LLM 综合评审 — 把 DelegationResult 转换为 Candidate,
                // 调用 AgentResultAggregator.aggregate 并传入 llmAggregator?.review 作为 llmReviewer。
                // llmAggregator 为 null 或 LLM 调用失败时,AgentResultAggregator 内部降级为 EXPERT_REVIEW。
                val candidates = successful.map { r ->
                    AgentResultAggregator.Candidate(
                        source = r.metadata.assistantName
                            ?: r.metadata.assistantId
                            ?: r.requestId,
                        content = r.resultText,
                        confidence = null,
                    )
                }
                val aggregation = AgentResultAggregator.aggregate(
                    candidates = candidates,
                    strategy = AgentResultAggregator.Strategy.LLM_REVIEW,
                    question = teamTask,
                    llmReviewer = llmAggregator?.let { agg -> { c, q -> agg.review(c, q) } },
                )
                aggregation.output
            }
        }
    }

    private fun errorResult(
        requestId: String,
        error: String,
        startedAt: Long,
    ): DelegationContract.DelegationResult {
        val finishedAt = System.currentTimeMillis()
        return DelegationContract.DelegationResult(
            requestId = requestId,
            success = false,
            error = error,
            metadata = DelegationContract.DelegationResult.ResultMetadata(
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = finishedAt - startedAt,
            ),
        )
    }

    /**
     * v1.202 改造 2: CONDITIONAL 节点的跳过结果。
     *
     * - success=true(跳过不算失败,不影响 errors 聚合)
     * - resultText 留空,自然被 [aggregateResults] 的 `it.resultText.isNotBlank()` 过滤
     * - requestId 加入 [skippedRequestIds],供 [buildSubResultTree] 把 status 标记为 "skipped"
     */
    private fun skippedResult(
        requestId: String,
    ): DelegationContract.DelegationResult {
        val now = System.currentTimeMillis()
        return DelegationContract.DelegationResult(
            requestId = requestId,
            success = true,
            resultText = "",
            metadata = DelegationContract.DelegationResult.ResultMetadata(
                startedAt = now,
                finishedAt = now,
                durationMs = 0,
            ),
        )
    }

    /**
     * v1.202 改造 2: 用 LLM 判断 CONDITIONAL 节点是否应该执行。
     *
     * 实现要点:
     *  - 构造简短 prompt,把前置结果摘要 + 当前任务交给 LLM,要求只回答 YES/NO
     *  - temperature=0 保证确定性,maxTokens=10 防止 LLM 啰嗦浪费 token
     *  - 优先匹配 NO(避免同时含 YES/NO 时误判)
     *  - LLM 调用失败/超时/异常时返回 true(默认执行,避免误跳过造成工作流断链)
     *  - 前置结果全为空时直接返回 true(无依据可判,默认执行)
     */
    private suspend fun evaluateConditional(
        node: DelegationContract.TeamWorkflowNode,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): Boolean {
        val previousResults = node.dependsOn.mapNotNull { depId ->
            executed[depId]?.let { dep ->
                val status = if (dep.success) "成功" else "失败"
                "[$depId]($status): ${dep.resultText.take(500)}"
            }
        }.joinToString("\n")
        // 无前置结果可参考,默认执行
        if (previousResults.isBlank()) return true

        val nodeTask = node.taskTemplate.ifBlank { node.name.ifBlank { node.id } }
        val prompt = """根据以下前置结果,判断是否应该执行任务"$nodeTask"。回答 YES 或 NO。

前置结果:
$previousResults

只回答 YES 或 NO,不要解释。""".trimIndent()

        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                content = "你是严谨的工作流条件判断器,只回答 YES 或 NO,不要输出其他内容。",
            ),
            UIMessage(role = MessageRole.USER, content = prompt),
        )

        // resultOf{} 自动重抛 CancellationException,不破坏协程取消语义
        val completion = resultOf {
            chatService?.completeText(
                messages = messages,
                temperature = 0f,
                maxTokens = 10,
            )
        }.onError { msg, t ->
            Logger.w("TeamWorkflowExecutor", "CONDITIONAL 条件判断 LLM 调用失败,默认执行: $msg", t)
        }.getOrNull() ?: return true  // chatService 为 null 或调用异常,默认执行

        val text = completion.text.trim().uppercase()
        // 优先匹配 NO(避免 "YES, but..." 之类同时含 YES/NO 时误判为 NO)
        // startsWith("NO") 处理 "NO" / "NO." / "NO, because..." 这类开头
        return !(text.startsWith("NO") || (text.contains("NO") && !text.contains("YES")))
    }

    /**
     * v1.202 改造 1: 把工作流执行结果构造成 [DelegationContract.SubResultNode] 树。
     *
     * 工作流是扁平的成员列表,故 children 为空;未来支持嵌套工作流时再递归处理 children。
     * status 取值:success / failed / skipped(skipped 来自 [skippedRequestIds])。
     */
    private fun buildSubResultTree(
        nodes: List<DelegationContract.TeamWorkflowNode>,
        orderedResults: List<DelegationContract.DelegationResult>,
        teamTask: String,
    ): List<DelegationContract.SubResultNode> {
        if (orderedResults.isEmpty()) return emptyList()
        return orderedResults.mapIndexed { idx, result ->
            // 与 orderedResults 对齐:nodes 也按 executed 顺序映射;若 nodes 短缺则用空名兜底
            val node = nodes.getOrNull(idx)
            val assistantId = result.metadata.assistantId ?: node?.assistantId ?: ""
            val assistantName = result.metadata.assistantName
                ?: node?.name?.ifBlank { node.id }
                ?: result.requestId
            val task = node?.taskTemplate?.ifBlank { teamTask } ?: teamTask
            val status = when {
                skippedRequestIds.contains(result.requestId) -> "skipped"
                result.success -> "success"
                else -> "failed"
            }
            DelegationContract.SubResultNode(
                requestId = result.requestId,
                assistantId = assistantId,
                assistantName = assistantName,
                task = task,
                status = status,
                resultText = result.resultText.ifBlank { null },
                error = result.error,
                durationMs = result.metadata.durationMs,
                children = emptyList(),
            )
        }
    }
}
