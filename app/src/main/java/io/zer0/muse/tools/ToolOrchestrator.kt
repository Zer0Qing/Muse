package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallSanitizer
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ToolCallRecord
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.uuid.Uuid

/** 工具调用超时阈值(2 分钟),超时则终止,避免阻塞流式输出。 */
internal const val TOOL_TIMEOUT_MS = 120_000L

/**
 * 单个工具结果送入 LLM 上下文的最大字符数,防止超长结果撑爆上下文。
 *
 * v1.x: 从 8K 提升到 32K,同时引入 [TOOL_RESULT_PREVIEW_CHARS] 预览机制:
 *  - ≤ 32K: 完整结果直接送入 LLM
 *  - > 32K: 写入 filesDir/tool_outputs/ 完整文件,LLM 上下文仅保留 4K 预览 + 文件引用,
 *    LLM 可后续通过 read_file 工具按需读取完整内容(按需读取模式)。
 */
internal const val MAX_TOOL_RESULT_CHARS = 32 * 1024

/** 工具输出超长截断时,LLM 上下文中保留的预览字符数。 */
internal const val TOOL_RESULT_PREVIEW_CHARS = 4 * 1024

/** 工具输出文件保留时长(毫秒),超过后由 [cleanupOldToolOutputs] 清理。 */
internal const val TOOL_OUTPUT_RETENTION_MS = 24L * 60 * 60 * 1000

/** 工具输出文件存储子目录(位于 filesDir 下),供 [ToolOrchestrator] 与 [cleanupOldToolOutputs] 共享。 */
internal const val TOOL_OUTPUTS_DIR = "tool_outputs"

/** 工具调用循环内 conversationHistory 的工具链部分最大消息条数。 */
internal const val MAX_TOOL_CHAIN_MESSAGES = 30

/** 连续工具失败早停阈值,避免跑满 maxToolRounds 白耗 API 额度。 */
internal const val MAX_CONSECUTIVE_TOOL_FAILURES = 3

/** R-TEST-10: 连续失败早停纯逻辑。 */
internal fun shouldAbortToolLoop(consecutiveFailures: Int): Boolean =
    consecutiveFailures >= MAX_CONSECUTIVE_TOOL_FAILURES

/** v1.x: 简单任务(无 task_plan)的默认最大轮次。 */
internal const val DEFAULT_MAX_TOOL_ROUNDS = 10

/** v1.x: 工具调用循环绝对上限(防死循环兜底),即使 task_plan 步骤再多也不超过此值。 */
internal const val MAX_TOOL_ROUNDS_HARD_CAP = 25

/** v1.x: 连续 N 轮 LLM 返回相同 tool_call(同名同参数)时判定卡死,提前终止。 */
internal const val MAX_NO_PROGRESS_ROUNDS = 2

/**
 * 单轮 LLM 流式请求的输入参数。
 *
 * @param round 当前轮次(从 1 开始)
 * @param history 本轮要发送的对话历史(已含 system prefix 和工具结果)
 * @param currentAssistantId 当前占位 assistant 消息的 id
 * @param builder assistant 正文累积器
 * @param reasoningBuilder assistant reasoning/think 累积器
 */
data class StreamRoundParams(
    val round: Int,
    val history: List<UIMessage>,
    val currentAssistantId: Uuid,
    val builder: StringBuilder,
    val reasoningBuilder: StringBuilder,
    // v1.0.1 (P4): 每轮工具调用的流式重试计数,递归调用 streamRound 时递增。
    //   原计数器声明在 launchStream 外层,跨所有 tool round 共享,导致第 1 轮耗尽后后续轮次无法重试。
    //   移入 params 后每轮独立,且递归重试通过 copy(retryCount = ...) 传递。
    val retryCount: Int = 0,
    /**
     * v1.0.17: StreamInterrupted 智能续传标志 — true 时跳过 builder/reasoningBuilder 的 clear()。
     *
     * 适用场景:已收部分内容后网络中断(StreamInterrupted),网络恢复后重试,
     * 重新发完整 prompt 但保留已显示的部分内容,UI 仅追加新内容(不闪回到首字)。
     *
     * 注意:此标志仅控制是否清空累积器;B3-03 已把已显示内容作为 resumeFromText
     * 注入到 ChatService,重试时作为末尾 assistant 消息让模型从中断处继续,避免从头重生成。
     */
    val preservePartialContent: Boolean = false,
)

/**
 * 单轮 LLM 流式请求的结果。
 */
sealed class StreamRoundResult {
    /**
     * 流式成功结束。
     *
     * @param assistantMessage 最终 finalized 的 assistant 消息(含 toolCalls,如果有)
     * @param hasToolCalls 本轮是否产生了工具调用
     * @param contentLength 本轮 assistant 正文字符数(用于性能统计)
     * @param firstTokenTime 首 token 到达时的系统时间,未到达则为 0
     */
    data class Success(
        val assistantMessage: UIMessage,
        val hasToolCalls: Boolean,
        val contentLength: Int,
        val firstTokenTime: Long,
    ) : StreamRoundResult()

    /**
     * 流式失败(网络/限流/API 错误等,且已耗尽自动重试)。
     *
     * @param type 错误类型
     * @param message 用户友好错误消息
     * @param partialContent 已接收的部分正文
     * @param partialReasoning 已接收的部分 reasoning
     */
    data class Error(
        val type: ChatErrorType,
        val message: String,
        val partialContent: String? = null,
        val partialReasoning: String? = null,
    ) : StreamRoundResult()
}

/**
 * 工具调用循环的宿主回调。
 *
 * 由 [ChatViewModel] 实现,负责真正的流式请求、UI 更新、工具审批等。
 * [ToolOrchestrator] 只编排轮次,不直接操作 UI。
 */
interface ToolLoopHost {
    /**
     * 执行一轮 LLM 流式请求并返回结果。
     *
     * 实现方需要:
     *  - 调用 chatService.streamChat
     *  - 收集 ContentDelta / ReasoningDelta / ToolCallDelta / ImageDelta
     *  - 实时更新 UI(节流)
     *  - 处理 NETWORK/RATE_LIMIT 自动重试
     *  - finalize assistant 消息(mood/reflection/think 提取)
     *  - 返回含/不含 toolCalls 的 assistant 消息
     */
    suspend fun streamRound(params: StreamRoundParams): StreamRoundResult

    /**
     * 请求用户审批工具调用,挂起直到用户做出决定。
     *
     * @param args v1.0.53: 完整工具参数,供参数化权限判定(带默认值,旧实现不受影响)
     */
    suspend fun requestToolApproval(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?> = emptyMap(),
    ): ToolApprovalState

    /**
     * 工具调用循环内部发生非致命错误时回调(如 DB 落盘失败)。
     */
    fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean = true)

    /**
     * v1.x: 单个工具开始执行时回调(可用于 UI 进度提示/日志)。
     *
     * 默认空实现,宿主可选覆盖,用于细粒度进度通知。
     */
    fun onToolStart(toolCallId: String, toolName: String) {}

    /**
     * v1.x: 单个工具执行结束回调。
     *
     * @param success 是否成功(按工具结果判定)
     * @param durationMs 工具执行耗时(毫秒,含审批等待)
     * 默认空实现,宿主可选覆盖。
     */
    fun onToolFinish(toolCallId: String, toolName: String, success: Boolean, durationMs: Long) {}
}

/**
 * 工具调用循环的参数。
 *
 * @param sessionId 当前会话 id
 * @param initialAssistantId 初始占位 assistant 消息 id
 * @param baseHistorySize 不可截断的初始上下文大小(system prefix + 历史)
 * @param maxRounds 最大工具调用轮次
 * @param tools 本轮暴露给 LLM 的工具定义列表
 * @param skillMap 已启用 skill 的 id → SkillEntity 映射
 * @param model 实际使用的模型
 * @param providerConfig 实际使用的 Provider 配置
 * @param temperature 温度
 * @param maxTokens 最大 token 数
 * @param reasoningLevel 推理等级
 * @param webSearchEnabled 是否启用联网搜索(用于从 web_search 工具结果提取 citation URL)
 * @param experiments 实验配置(仅用于 debug 日志)
 * @param assistant 当前 Assistant 配置(可选,用于 delegate_agent 等)
 */
data class ToolLoopParams(
    val sessionId: String,
    val initialAssistantId: Uuid,
    val baseHistorySize: Int,
    val maxRounds: Int,
    val tools: List<ToolDefinition>,
    val skillMap: Map<String, SkillEntity>,
    val model: Model?,
    val providerConfig: ProviderConfig?,
    val temperature: Float?,
    val maxTokens: Int?,
    val reasoningLevel: ReasoningLevel,
    val webSearchEnabled: Boolean = false,
    val experiments: ExperimentsConfig = ExperimentsConfig(),
    val assistant: AssistantEntity? = null,
    /** B7-04: 首轮预置已产出正文(继续生成时从断点续写)。 */
    val initialBuilderContent: String = "",
    /** B7-04: 首轮预置已产出 reasoning。 */
    val initialReasoningContent: String = "",
)

/**
 * 工具调用循环的错误信息。
 */
data class ToolLoopError(
    val type: ChatErrorType,
    val message: String,
    val partialContent: String? = null,
    val partialReasoning: String? = null,
)

/**
 * 工具调用循环的结果。
 */
data class ToolLoopResult(
    val finalAssistantId: Uuid,
    val round: Int,
    val totalToolCallCount: Int,
    val totalCharCount: Int,
    val firstTokenTime: Long,
    val citationUrls: List<String>,
    val success: Boolean,
    val error: ToolLoopError? = null,
    /** 工具调用循环正常结束时(无 tool_calls)的最终 assistant 消息;达到轮次上限等异常退出时为 null。 */
    val finalAssistantMessage: UIMessage? = null,
)

/**
 * Phase 2: 工具调用循环编排器。
 *
 * 把 [ChatViewModel] 中厚重的 `while (hasToolCalls && round < maxToolRounds)` 循环抽离出来,
 * 职责:
 *  - 控制多轮工具调用流程(截断、轮次上限、连续失败早停)
 *  - 并行执行多个工具调用并回填结果
 *  - 维护任务卡(TaskCard)状态
 *  - 从 web_search 工具结果中提取 citation URL,供最终 assistant 消息引用
 *
 * 真正的流式请求、UI 更新、工具审批通过 [ToolLoopHost] 回调交给 ChatViewModel。
 */
@Suppress("LongParameterList")
class ToolOrchestrator(
    private val toolRegistry: ToolRegistry,
    private val skillRepository: SkillRepository,
    private val skillExecutor: SkillExecutor,
    private val assistantRepository: AssistantRepository,
    private val sessionRepository: SessionRepository,
    // v1.x: 注入 Context 用于把超长工具输出落盘到 filesDir/tool_outputs/,
    // 让 LLM 通过 read_file 工具按需读取完整内容。
    private val context: Context,
    // P1-1: Hook 注册表 — 在工具调用各阶段调用 ToolLifecycleHook
    private val hookRegistry: io.zer0.muse.hook.HookRegistry? = null,
    // P2-4: 审计日志记录器(工具审批放行时记录)。
    private val auditLogger: AuditLogger? = null,
    // v1.x: 会话级浏览器实例注册表(每个会话独立 WebView)。
    private val browserManagerRegistry: BrowserManagerRegistry = BrowserManagerRegistry(context),
    // R-TEST-10: 工具超时可注入,生产默认 2 分钟
    private val toolTimeoutMs: Long = TOOL_TIMEOUT_MS,
) {

    private companion object {
        const val TAG = "ToolOrchestrator"

        /** v1.x: 浏览器工具名(按会话路由到独立 BrowserManager)。 */
        private val BROWSER_TOOL_NAMES = setOf(
            BrowserAutomationTool.TOOL_NAVIGATE,
            BrowserAutomationTool.TOOL_CLICK,
            BrowserAutomationTool.TOOL_TYPE,
            BrowserAutomationTool.TOOL_EXTRACT,
            BrowserAutomationTool.TOOL_SCROLL_BOTTOM,
            BrowserAutomationTool.TOOL_GET_HTML,
        )
    }

    private data class ToolExecResult(
        val idx: Int,
        val tc: ToolCall,
        val finalToolResult: String,
        val isSuccess: Boolean,
        /** v1.x: 展示给用户的结果(失败时不含 LLM 引导语),null 时回退 [finalToolResult]。 */
        val displayResult: String? = null,
    )

    /**
     * 运行工具调用循环,直到 LLM 不再调用工具、达到轮次上限或连续失败早停。
     *
     * @param params 循环参数
     * @param conversationHistory 可变对话历史(会在此方法内被追加 tool_calls/tool 消息)
     * @param host 宿主回调
     */
    suspend fun runLoop(
        params: ToolLoopParams,
        conversationHistory: MutableList<UIMessage>,
        host: ToolLoopHost,
        accessor: ChatStateAccessor,
        taskCardCoordinator: ChatTaskCardCoordinator,
    ): ToolLoopResult {
        var round = 0
        var consecutiveToolFailures = 0
        var currentAssistantId = params.initialAssistantId
        var totalToolCallCount = 0
        var totalCharCount = 0
        var firstTokenTime = 0L
        val citationUrls = mutableListOf<String>()
        var hasToolCalls = true
        var finalAssistantMessage: UIMessage? = null

        // v1.x: 动态计算最大轮次(按工具循环迭代式设计)
        //  - 有 task_plan: steps*2 + 5(每步平均 2 轮工具调用 + 5 轮缓冲)
        //  - 无 task_plan: DEFAULT_MAX_TOOL_ROUNDS(10)
        //  - 上限 MAX_TOOL_ROUNDS_HARD_CAP(25)兜底,且不超过 params.maxRounds(向后兼容)
        var maxRounds = computeMaxRounds(conversationHistory, params.maxRounds)
        Logger.i(TAG, "Agent Loop 开始 | sessionId=${params.sessionId} | 初始最大轮次: $maxRounds")

        // v1.x: 连续无进展早停 — 记录上一轮 tool_call 签名,连续相同则判定卡死
        var previousToolCallSignature: String? = null
        var noProgressRounds = 0

        while (hasToolCalls && round < maxRounds) {
            round++
            val stepStartedAt = System.currentTimeMillis()
            Logger.d(TAG, "Agent Loop step $round/$maxRounds 开始 | sessionId=${params.sessionId}")

            // v1.x: 每轮动态重算 maxRounds(task_plan 可能在循环内才产生,需要扩大配额)
            val recomputedMax = computeMaxRounds(conversationHistory, params.maxRounds)
            if (recomputedMax != maxRounds) {
                Logger.d(TAG, "Agent Loop maxRounds 更新: $maxRounds → $recomputedMax (task_plan 已产生)")
                maxRounds = recomputedMax
            }

            // C1-2: 工具链过长时截断,保留初始上下文 + 最近工具链
            val toolChainSize = conversationHistory.size - params.baseHistorySize
            if (toolChainSize > MAX_TOOL_CHAIN_MESSAGES) {
                val keepHead = conversationHistory.subList(0, params.baseHistorySize).toList()
                val keepTail = conversationHistory.subList(
                    conversationHistory.size - MAX_TOOL_CHAIN_MESSAGES,
                    conversationHistory.size,
                ).toList()
                val truncatedList = keepHead + listOf(
                    UIMessage(
                        role = MessageRole.SYSTEM,
                        content = "(较早的工具调用历史已省略,仅保留最近 $MAX_TOOL_CHAIN_MESSAGES 条)",
                    ),
                ) + keepTail
                conversationHistory.clear()
                conversationHistory.addAll(truncatedList)
                if (params.experiments.debugMode) {
                    Logger.d(
                        "ToolOrchestrator",
                        "tool-chain truncated | round=$round | size ${params.baseHistorySize + toolChainSize} → ${conversationHistory.size}",
                    )
                }
            }

            // 每轮重置流式累积器;第一轮继续生成时预置已产出内容
            val isFirstRound = round == 1
            val builder = StringBuilder().apply {
                if (isFirstRound && params.initialBuilderContent.isNotEmpty()) append(params.initialBuilderContent)
            }
            val reasoningBuilder = StringBuilder().apply {
                if (isFirstRound && params.initialReasoningContent.isNotEmpty()) append(params.initialReasoningContent)
            }

            val outcome = host.streamRound(
                StreamRoundParams(
                    round = round,
                    history = conversationHistory.toList(),
                    currentAssistantId = currentAssistantId,
                    builder = builder,
                    reasoningBuilder = reasoningBuilder,
                    preservePartialContent = isFirstRound && params.initialBuilderContent.isNotEmpty(),
                )
            )

            when (outcome) {
                is StreamRoundResult.Error -> {
                    Logger.w(
                        TAG,
                        "Agent Loop 因流式错误终止 | sessionId=${params.sessionId} | round=$round" +
                            " | type=${outcome.type} | msg=${outcome.message}",
                    )
                    return ToolLoopResult(
                        finalAssistantId = currentAssistantId,
                        round = round,
                        totalToolCallCount = totalToolCallCount,
                        totalCharCount = totalCharCount,
                        firstTokenTime = firstTokenTime,
                        citationUrls = citationUrls.toList(),
                        success = false,
                        error = ToolLoopError(
                            type = outcome.type,
                            message = outcome.message,
                            partialContent = outcome.partialContent,
                            partialReasoning = outcome.partialReasoning,
                        ),
                    )
                }

                is StreamRoundResult.Success -> {
                    totalCharCount += outcome.contentLength
                    if (outcome.firstTokenTime > 0L && firstTokenTime == 0L) {
                        firstTokenTime = outcome.firstTokenTime
                    }

                    if (!outcome.hasToolCalls) {
                        hasToolCalls = false
                        finalAssistantMessage = outcome.assistantMessage
                        Logger.d(TAG, "Agent Loop step $round/$maxRounds 结束(无工具调用,循环正常结束)")
                        break
                    }

                    val assistantToolMsg = outcome.assistantMessage
                    val rawToolCallList = assistantToolMsg.toolCalls ?: emptyList()
                    // v1.0.48: 过滤无效 toolCalls — 商汤 completeText 回退等场景可能返回
                    //   空 name 或空 arguments 的 toolCall,直接执行会引发 HTTP 400
                    //   (invalid tool_call function, function/name/arguments cannot be empty)
                    //   过滤后若无有效调用,按"无工具调用"处理本轮,避免卡死
                    val toolCallList = ToolCallSanitizer.sanitize(rawToolCallList)
                    if (rawToolCallList.size != toolCallList.size) {
                        Logger.w(
                            TAG,
                            "过滤无效 toolCalls: ${rawToolCallList.size} -> ${toolCallList.size}" +
                                " | sessionId=${params.sessionId}",
                        )
                    }
                    totalToolCallCount += toolCallList.size

                    // v1.0.48: 过滤后无有效 toolCalls,按"无工具调用"处理本轮
                    if (toolCallList.isEmpty()) {
                        hasToolCalls = false
                        // 清空无效 toolCalls 后作为本轮最终 assistant 消息(对齐 line 412 的赋值类型)
                        finalAssistantMessage = assistantToolMsg.copy(toolCalls = emptyList())
                        Logger.d(TAG, "Agent Loop step $round/$maxRounds 结束(过滤后无有效工具调用)")
                        break
                    }

                    // v1.x: 连续无进展早停检测 — 在回填前判断,避免卡死时还执行重复工具
                    val currentSignature = toolCallSignature(toolCallList)
                    if (currentSignature.isNotEmpty() && currentSignature == previousToolCallSignature) {
                        noProgressRounds++
                        Logger.w(
                            TAG,
                            "Agent Loop 检测到连续相同 tool_call(连续 $noProgressRounds 轮)" +
                                " | sessionId=${params.sessionId} | signature=$currentSignature",
                        )
                        if (noProgressRounds >= MAX_NO_PROGRESS_ROUNDS) {
                            Logger.w(TAG, "Agent Loop 连续 $noProgressRounds 轮无进展,判定卡死,提前终止")
                            hasToolCalls = false
                            // 不把 assistantToolMsg 加入 history,避免遗留无 tool 结果的 assistant 消息
                            break
                        }
                    } else {
                        noProgressRounds = 0
                    }
                    previousToolCallSignature = currentSignature

                    // v1.0.62: 历史与持久化必须使用清洗后的 toolCalls,
                    // 避免空 name/空 arguments 的非法调用在下一轮请求中触发 400。
                    val cleanedAssistantToolMsg = if (toolCallList.size == rawToolCallList.size) {
                        assistantToolMsg
                    } else {
                        assistantToolMsg.copy(toolCalls = toolCallList)
                    }

                    // 把带 tool_calls 的 assistant 消息加入历史并持久化
                    conversationHistory.add(cleanedAssistantToolMsg)
                    persistAssistantToolMsg(params.sessionId, cleanedAssistantToolMsg, host)

                    // 断点续传:持久化未完成的工具调用
                    savePendingToolCalls(params.sessionId, toolCallList, host)

                    // 构建任务卡并切换到 EXECUTING
                    // v1.0.53: send_sticker 不纳入任务卡(表情包是趣味交互,不展示执行计划),
                    //   全部调用均为静默工具时不建卡(taskCardId=null,后续对卡的操作内部判空跳过)。
                    // v1.0.54: list_stickers 同样静默(列表情包是内部工作,用户无需看到)。
                    val silentToolNames = setOf("send_sticker", "list_stickers")
                    val taskCardToolCalls = toolCallList
                        .map { it.name to it.arguments }
                        .filter { it.first !in silentToolNames }
                    val taskCardId: String? = if (taskCardToolCalls.isNotEmpty()) {
                        val id = currentAssistantId.toString()
                        val taskCard = TaskCardData.fromToolCalls(currentAssistantId, taskCardToolCalls)
                        accessor.update {
                            it.copy(taskCards = it.taskCards + (id to taskCard))
                        }
                        taskCardCoordinator.updateTaskCardPhase(id, TaskCardPhase.EXECUTING)
                        id
                    } else null

                    // 并行/串行执行工具调用
                    // v1.0.47 P6-2: 弱工具模型降级为串行执行,避免并行 tool_calls 导致格式错乱
                    val executeToolCall: suspend (Int, ToolCall) -> ToolExecResult = { idx, tc ->
                        executeSingleToolCall(params, taskCardId, tc, idx, host, taskCardCoordinator)
                    }

                    val isWeakToolModel = WeakToolUseDetector.isWeakToolModel(params.model)
                    val execResults: List<ToolExecResult> = if (toolCallList.size <= 1 || isWeakToolModel) {
                        if (isWeakToolModel && toolCallList.size > 1) {
                            Logger.i(TAG, "弱工具模型检测到 ${toolCallList.size} 个并行调用,降级为串行执行 | model=${params.model?.id}")
                        }
                        toolCallList.mapIndexed { idx, tc -> executeToolCall(idx, tc) }
                    } else {
                        coroutineScope {
                            toolCallList.mapIndexed { idx, tc ->
                                async { executeToolCall(idx, tc) }
                            }.awaitAll()
                        }.sortedBy { it.idx }
                    }

                    // 按顺序回填结果到历史和 UI
                    for (result in execResults) {
                        val (idx, tc, finalToolResult, isSuccess, displayResult) = result
                        if (isSuccess) {
                            consecutiveToolFailures = 0
                        } else {
                            consecutiveToolFailures++
                        }

                        val toolMsg = UIMessage(
                            role = MessageRole.TOOL,
                            content = finalToolResult,
                            toolCallId = tc.id,
                        )
                        conversationHistory.add(toolMsg)

                        val toolDisplay = UIMessage(
                            role = MessageRole.ASSISTANT,
                            // v1.0.54: 工具调用展示统一为折叠卡片(ToolCallCard,与思考过程/mood 同构),
                            //   消息本体不再拼"调用工具/参数/结果"文本。
                            //   send_sticker 特例: content 只保留贴纸路径(MessageBubble.extractStickerPaths
                            //   据此渲染图片),工具卡片静默(isSilentTool)。
                            content = if (tc.name == "send_sticker") {
                                extractStickerPaths(finalToolResult).joinToString("\n")
                            } else {
                                ""
                            },
                            toolCallInfo = ToolCallInfo(
                                toolName = tc.name,
                                arguments = tc.arguments,
                                result = displayResult ?: finalToolResult,
                                isSuccess = isSuccess,
                            ),
                        )
                        val record = ToolCallRecord(
                            toolName = tc.name,
                            arguments = tc.arguments,
                            result = displayResult ?: finalToolResult,
                            isSuccess = isSuccess,
                            timestamp = System.currentTimeMillis(),
                        )

                        val snapshot = accessor.snapshot
                        val isCurrentDisplayedSession = if (snapshot.isAgentMode) {
                            snapshot.agentSessionId == params.sessionId
                        } else {
                            snapshot.currentSessionId == params.sessionId
                        }
                        if (isCurrentDisplayedSession) {
                            // v1.0.54: 静默工具(list_stickers)完全不推 UI 消息 — 内部工作无痕;
                            //   send_sticker 推送 content=贴纸路径的消息(渲染图片,卡片静默);
                            //   其余工具推送空 content + toolCallInfo(折叠卡片展示)。
                            if (tc.name !in silentToolNames || tc.name == "send_sticker") {
                                accessor.updateMessages { it + toolDisplay }
                                accessor.update {
                                    it.copy(
                                        toolCallHistory = it.toolCallHistory + record,
                                    )
                                }
                            }
                        } else {
                            Logger.d(
                                "ToolOrchestrator",
                                "toolDisplay skipped (detached): sessionId=${params.sessionId}, " +
                                    "current=${snapshot.currentSessionId}, agent=${snapshot.agentSessionId}, " +
                                    "isAgent=${snapshot.isAgentMode}",
                            )
                        }

                        // 从 web_search 结果中提取 citation URL
                        if (tc.name == "web_search") {
                            citationUrls.addAll(extractWebSearchUrls(finalToolResult))
                        }

                        // C1-3: 连续失败早停
                        if (shouldAbortToolLoop(consecutiveToolFailures)) {
                            Logger.w(
                                "ToolOrchestrator",
                                "连续 $consecutiveToolFailures 次工具失败,提前终止工具调用循环 " +
                                    "(round=$round, tool=${tc.name})",
                            )
                            hasToolCalls = false
                            break
                        }
                    }

                    taskCardCoordinator.updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)

                    // 同步 Agent 工作流计划到 UI
                    // v1.137: 为计划关联当前助手消息 ID,使计划卡固定在创建它的消息上随消息滚动,
                    // 而不是始终"跳"到最后一条助手消息(用户反馈"列表固定在底部不跟随滚动")。
                    val latestPlans = skillExecutor.getActivePlans().mapValues { (_, plan) ->
                        if (plan.messageId == null) plan.copy(messageId = currentAssistantId.toString()) else plan
                    }
                    if (latestPlans.isNotEmpty()) {
                        accessor.update { it.copy(agentPlans = latestPlans) }
                    }

                    // 创建新的占位 assistant 消息接收下一轮流式回复
                    if (hasToolCalls && round < maxRounds) {
                        val nextAssistant = UIMessage(role = MessageRole.ASSISTANT, content = "")
                        val snapshot = accessor.snapshot
                        val isCurrentDisplayedSession2 = if (snapshot.isAgentMode) {
                            snapshot.agentSessionId == params.sessionId
                        } else {
                            snapshot.currentSessionId == params.sessionId
                        }
                        if (isCurrentDisplayedSession2) {
                            accessor.updateMessages { it + nextAssistant }
                        }
                        currentAssistantId = nextAssistant.id
                    }

                    // v1.x: 步结束日志 — 记录工具名、成功/失败数、耗时
                    val stepElapsedMs = System.currentTimeMillis() - stepStartedAt
                    val toolNames = toolCallList.joinToString(",") { it.name }
                    val successCount = execResults.count { it.isSuccess }
                    val failCount = execResults.size - successCount
                    Logger.d(
                        TAG,
                        "Agent Loop step $round/$maxRounds 结束 | 工具=[$toolNames]" +
                            " | 成功=$successCount 失败=$failCount | 耗时=${stepElapsedMs}ms",
                    )
                }
            }
        }

        // v1.0.74 fix: 轮次耗尽/卡死退出时 finalAssistantMessage 为 null,会话里没有任何
        // 收尾提示,任务卡悬空、用户以为助手坏了。注入一条明确的收尾消息。
        if (finalAssistantMessage == null && hasToolCalls) {
            finalAssistantMessage = UIMessage(
                id = currentAssistantId,
                role = MessageRole.ASSISTANT,
                content = "[已达到工具调用轮次上限($maxRounds 轮),自动停止。如需继续,可以让我接着处理。]",
            )
            accessor.updateMessages { it + finalAssistantMessage }
        }

        Logger.i(
            TAG,
            "Agent Loop 结束 | sessionId=${params.sessionId} | rounds=$round/$maxRounds" +
                " | toolCalls=$totalToolCallCount | chars=$totalCharCount | success=true",
        )
        return ToolLoopResult(
            finalAssistantId = currentAssistantId,
            round = round,
            totalToolCallCount = totalToolCallCount,
            totalCharCount = totalCharCount,
            firstTokenTime = firstTokenTime,
            citationUrls = citationUrls.toList(),
            success = true,
            finalAssistantMessage = finalAssistantMessage,
        )
    }

    private suspend fun executeSingleToolCall(
        params: ToolLoopParams,
        taskCardId: String?,
        tc: ToolCall,
        idx: Int,
        host: ToolLoopHost,
        taskCardCoordinator: ChatTaskCardCoordinator,
    ): ToolExecResult {
        // v1.x: 通知宿主工具开始执行(含审批等待时间),用于细粒度进度
        val toolStartAt = System.currentTimeMillis()
        host.onToolStart(tc.id, tc.name)

        // v1.0.53: 统一解析参数(Hook 拦截与参数化审批共用)
        val paramsMap = parseToolCallArgs(tc.arguments)

        // P1-1: ToolLifecycleHook.onToolCallRequested — 可拦截工具调用
        if (hookRegistry != null) {
            var blocked: io.zer0.muse.hook.ToolCallAction.Block? = null
            hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                if (blocked != null) return@executeNoResult
                when (val action = hook.onToolCallRequested(tc.name, paramsMap)) {
                    is io.zer0.muse.hook.ToolCallAction.Block -> blocked = action
                    is io.zer0.muse.hook.ToolCallAction.Allow -> { /* 继续 */ }
                }
            }
            if (blocked != null) {
                val blockResult = """{"error": "Tool blocked by hook", "reason": "${blocked!!.reason}"}"""
                host.onToolFinish(tc.id, tc.name, false, System.currentTimeMillis() - toolStartAt)
                return ToolExecResult(idx, tc, blockResult, false)
            }
        }

        // 工具审批检查(v1.0.53: 传完整 args 供参数化权限判定)
        val approvalState = host.requestToolApproval(tc.name, tc.id, tc.arguments.take(200), paramsMap)

        // P1-1: ToolLifecycleHook.onToolPermissionChecked
        if (hookRegistry != null) {
            val approved = approvalState !is ToolApprovalState.Denied
            hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                hook.onToolPermissionChecked(tc.name, approved)
            }
        }

        if (approvalState is ToolApprovalState.Denied) {
            val deniedResult = """{"error": "Tool denied by user", "reason": "${approvalState.reason}"}"""
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                s.copy(
                    status = TaskStepStatus.FAILED,
                    result = deniedResult,
                    finishedAt = System.currentTimeMillis(),
                )
            }
            try {
                PendingToolCallStore.remove(tc.id)
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.w("ToolOrchestrator", "PendingToolCallStore.remove(denied) 失败: ${e.message}", e)
            }
            host.onToolFinish(tc.id, tc.name, false, System.currentTimeMillis() - toolStartAt)
            return ToolExecResult(idx, tc, deniedResult, false)
        }

        // P2-4: 审计日志 — 用户审批放行工具
        if (approvalState is ToolApprovalState.Approved) {
            auditLogger?.log(
                category = "user_action",
                action = "approve_tool",
                target = tc.id,
                detail = mapOf("tool" to tc.name),
            )
        }

        // v1.x: 审批阶段用户覆盖的参数(如 generate_image 的 reference_image 本地图)合并到 tc.arguments
        // 合并规则: argOverrides 中的键覆盖 LLM 原始 JSON 中的同名键;原始 JSON 解析失败时仅用 overrides
        val effectiveArguments = if (approvalState is ToolApprovalState.Approved && approvalState.argOverrides.isNotEmpty()) {
            mergeToolArguments(tc.arguments, approvalState.argOverrides)
        } else {
            tc.arguments
        }

        val stepStartedAt = System.currentTimeMillis()

        // delegate_agent 步骤启动前解析助手名,更新步骤标题/进度文本
        val delegateAgentInfo = if (tc.name == "delegate_agent") {
            resultOf {
                TaskCardData.parseDelegateAgentArgs(tc.arguments)
            }.getOrNull()
        } else null
        val assistantName = delegateAgentInfo?.assistantId?.takeIf { it.isNotBlank() }?.let { id ->
            resultOf { assistantRepository.getById(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
        }
        val stepTitle = if (assistantName != null) "委托给 $assistantName" else tc.name
        val stepProgress = if (assistantName != null) "正在委托给 $assistantName..." else null
        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
            s.copy(
                title = stepTitle,
                status = TaskStepStatus.RUNNING,
                startedAt = stepStartedAt,
                progressText = stepProgress,
            )
        }

        // 执行工具:skill 走 SkillExecutor,本地工具走 ToolRegistry
        val toolResult = withTimeoutOrNull(toolTimeoutMs) {
            val skill = params.skillMap[tc.name]
            if (skill != null) {
                skillExecutor.execute(
                    skill = skill,
                    argumentsJson = effectiveArguments,
                    onProgress = { msg ->
                        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                            s.copy(progressText = msg)
                        }
                    },
                )
            } else {
                withContext(Dispatchers.IO) {
                    // v1.x: 浏览器工具按会话路由 — 每个会话独立 BrowserManager(WebView),
                    // 避免跨会话串扰(会话 A 关闭浏览器不影响会话 B)。
                    if (tc.name in BROWSER_TOOL_NAMES) {
                        val sessionBm = browserManagerRegistry.getForSession(params.sessionId)
                        val argsMap = runCatching {
                            AppJson.decodeFromString(JsonObject.serializer(), effectiveArguments)
                                .entries.associate { (k, v) -> k to v.toString().trim('"') }
                        }.getOrDefault(emptyMap())
                        BrowserAutomationTool.executeFromArgs(tc.name, argsMap, sessionBm)
                    } else {
                        toolRegistry.executeFromJson(tc.name, effectiveArguments)
                    }
                }
            }
        } ?: "[超时] 工具 ${tc.name} ${toolTimeoutMs / 1000} 秒未响应,已终止"

        val isSuccess = taskCardCoordinator.isToolResultSuccess(toolResult)
        // v1.0.47 P2-1: 结构化失败引导 — 仅拼进给 LLM 的历史消息,避免无效重试循环
        // v1.x: 展示给用户的 toolDisplay/taskCard 用纯报错文本,不暴露给模型的引导语。
        val llmResult = if (isSuccess) {
            toolResult
        } else {
            "$toolResult\n\n[工具调用失败引导] 请按以下优先级判断:\n" +
                "1. 参数/路径错误 → 修正后重试本工具(最多 1 次)\n" +
                "2. 权限/资源不可用 → 换用其他工具或告知用户限制\n" +
                "3. 网络超时 → 可重试一次,仍失败则告知用户\n" +
                "4. 无法解决 → 直接告知用户失败原因和建议,不要硬撑"
        }
        // v1.x: 超长工具输出走"预览 + 写文件 + 引用"模式,完整内容落盘到
        // filesDir/tool_outputs/,LLM 上下文仅保留 4K 预览 + read_file 引用,
        // 既避免撑爆上下文,又让 LLM 能按需读取完整结果。
        // 审计修复 (4.7): 只截断一次 — 原实现 finalToolResult 与 displayResult 各调一次
        // maybeTruncateToolOutput,同一输出写两份文件(文件名含时间戳);现只落盘一份,
        // 展示与给 LLM 的结果共用同一份截断结果与同一文件路径。
        val finalToolResult = maybeTruncateToolOutput(tc.id, llmResult)
        // 展示用:复用 finalToolResult(同一路径文件,避免重复落盘;失败时含 LLM 引导语,对展示无碍)
        val displayResult = finalToolResult

        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
            s.copy(
                status = if (isSuccess) TaskStepStatus.SUCCESS else TaskStepStatus.FAILED,
                result = displayResult,
                finishedAt = System.currentTimeMillis(),
            )
        }

        try {
            PendingToolCallStore.remove(tc.id)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w("ToolOrchestrator", "PendingToolCallStore.remove 失败: ${e.message}", e)
        }

        // P1-1: ToolLifecycleHook.onToolExecutionResult
        if (hookRegistry != null) {
            val execResult = io.zer0.muse.hook.ToolExecutionResult(
                toolName = tc.name,
                success = isSuccess,
                output = finalToolResult,
                durationMs = System.currentTimeMillis() - toolStartAt,
            )
            hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                hook.onToolExecutionResult(execResult)
            }
        }

        host.onToolFinish(tc.id, tc.name, isSuccess, System.currentTimeMillis() - toolStartAt)
        return ToolExecResult(idx, tc, finalToolResult, isSuccess, displayResult = displayResult)
    }

    /** P1-1: 解析工具调用 JSON 参数为 Map(供 ToolLifecycleHook 使用)。 */
    private fun parseToolCallArgs(argumentsJson: String): Map<String, Any> {
        return runCatching {
            val element = AppJson.parseToJsonElement(argumentsJson)
            if (element is JsonObject) {
                element.mapValues { (_, v) ->
                    when (v) {
                        is JsonPrimitive -> v.content
                        else -> v.toString()
                    }
                }
            } else emptyMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * v1.x: 把用户在审批阶段覆盖的参数(键 → 值)合并进 LLM 原始 arguments JSON。
     *
     * 合并规则:
     *  - 原始 JSON 解析为 JsonObject,逐键保留;
     *  - [overrides] 中的键以新值覆盖(已存在)或新增(不存在);
     *  - 原始 JSON 解析失败时,仅用 [overrides] 构造一个新 JSON。
     * 所有值统一以 JSON 字符串形式写入(对齐 ToolRegistry.executeFromJson 的解析约定:
     * `v.toString().trim('"')`)。
     */
    internal fun mergeToolArguments(originalArguments: String, overrides: Map<String, String>): String {
        if (overrides.isEmpty()) return originalArguments
        val base: JsonObject = resultOf {
            AppJson.decodeFromString(JsonObject.serializer(), originalArguments)
        }.getOrNull() ?: JsonObject(emptyMap())
        val merged = buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            overrides.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return merged.toString()
    }

    private suspend fun persistAssistantToolMsg(
        sessionId: String,
        msg: UIMessage,
        host: ToolLoopHost,
    ) {
        try {
            sessionRepository.upsertMessage(sessionId, msg)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.e("ToolOrchestrator", "upsertMessage(toolCalls) failed", e)
            host.onToolLoopError(
                ChatErrorType.TOOL_ERROR,
                "工具调用记录保存失败: ${e.message ?: "未知错误"}",
            )
        }
    }

    private suspend fun savePendingToolCalls(
        sessionId: String,
        toolCalls: List<ToolCall>,
        host: ToolLoopHost,
    ) {
        if (toolCalls.isEmpty()) return
        val now = System.currentTimeMillis()
        val pendings = toolCalls.map { tc ->
            PendingToolCallStore.PendingToolCall(
                chatId = sessionId,
                toolCallId = tc.id,
                toolName = tc.name,
                arguments = tc.arguments,
                createdAt = now,
            )
        }
        try {
            PendingToolCallStore.saveAll(pendings)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w("ToolOrchestrator", "PendingToolCallStore.saveAll 失败: ${e.message}", e)
        }
    }

    /**
     * v1.x: 根据任务复杂度动态计算最大工具调用轮次。
     *
     * 策略:
     *  - 若已有 task_plan(AgentPlan 缓存或历史 tool_call),按步骤数 * 2 + 5 推算
     *  - 否则简单任务默认 [DEFAULT_MAX_TOOL_ROUNDS](10 轮)
     *  - 上限 [MAX_TOOL_ROUNDS_HARD_CAP](25)兜底,且不超过 [hardCap](向后兼容调用方传入的 params.maxRounds)
     */
    internal fun computeMaxRounds(
        messages: List<UIMessage>,
        hardCap: Int = MAX_TOOL_ROUNDS_HARD_CAP,
    ): Int {
        val planSteps = countTaskPlanSteps(messages)
        val base = if (planSteps > 0) planSteps * 2 + 5 else DEFAULT_MAX_TOOL_ROUNDS
        return minOf(base, MAX_TOOL_ROUNDS_HARD_CAP, hardCap)
    }

    /**
     * 统计 task_plan 步骤数:优先用 SkillExecutor 内存中的活跃计划,
     * 回退到从历史消息中解析 task_plan 工具调用的 steps 参数(断点续传/继续会话场景)。
     */
    internal fun countTaskPlanSteps(messages: List<UIMessage>): Int {
        val activePlanSteps = skillExecutor.getActivePlans().values.sumOf { it.steps.size }
        if (activePlanSteps > 0) return activePlanSteps
        return messages.asSequence()
            .filter { it.role == MessageRole.ASSISTANT }
            .flatMap { (it.toolCalls ?: emptyList()).asSequence() }
            .filter { it.name == "task_plan" }
            .mapNotNull { parseTaskPlanStepCount(it.arguments) }
            .maxOrNull() ?: 0
    }

    /**
     * 从 task_plan 工具调用的 arguments JSON 中解析 steps 数组长度。
     * 解析失败返回 null(容错,不影响主流程)。
     */
    internal fun parseTaskPlanStepCount(argumentsJson: String): Int? {
        return resultOf {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), argumentsJson)
            val stepsEl = obj["steps"] ?: return@resultOf 0
            val stepsStr = AppJson.encodeToString(JsonElement.serializer(), stepsEl)
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(JsonElement.serializer()),
                stepsStr,
            ).size
        }.getOrNull()
    }

    /**
     * v1.x: 计算一轮 tool_call 列表的签名(用于卡死检测)。
     *
     * 签名 = 按 id 排序后的 "name(arguments)" 拼接,顺序无关,避免并行工具顺序抖动误判。
     * 空列表返回空字符串(不参与卡死检测)。
     */
    internal fun toolCallSignature(toolCalls: List<ToolCall>): String {
        if (toolCalls.isEmpty()) return ""
        return toolCalls.sortedBy { it.id }
            .joinToString("|") { "${it.name}(${it.arguments})" }
    }

    internal fun extractWebSearchUrls(result: String): List<String> {
        val regex = Regex("""^\s*URL:\s*(.+)$""", RegexOption.MULTILINE)
        return regex.findAll(result).map { it.groupValues[1].trim() }.toList()
    }

    /**
     * v1.x: 工具输出超长时,完整内容落盘到 filesDir/tool_outputs/,
     * LLM 上下文仅保留 [TOOL_RESULT_PREVIEW_CHARS] 预览 + read_file 引用。
     *
     * 实现说明:完整内容落盘后由 read_file 工具按需读取。
     *
     * - 输出 ≤ [MAX_TOOL_RESULT_CHARS]: 原样返回,不写文件
     * - 输出 > [MAX_TOOL_RESULT_CHARS]:
     *   1. 写入 filesDir/tool_outputs/tool_output_<toolCallId>_<ts>.txt
     *   2. 返回"[已截断] + [完整输出已保存到:...] + [可用 read_file 读取:...] + 4K 预览"
     *
     * 写盘失败时降级为旧的简单截断(避免影响主流程),并记日志。
     */
    private fun maybeTruncateToolOutput(toolCallId: String, output: String): String {
        if (output.length <= MAX_TOOL_RESULT_CHARS) return output

        val preview = output.take(TOOL_RESULT_PREVIEW_CHARS)
        val fileName = "tool_output_${toolCallId}_${System.currentTimeMillis()}.txt"

        return try {
            val dir = File(context.filesDir, TOOL_OUTPUTS_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(output)
            Logger.i(
                TAG,
                "工具输出截断: toolCallId=$toolCallId | 总长=${output.length}" +
                    " | 完整输出已落盘: ${file.absolutePath}",
            )
            buildString {
                append("[工具输出已截断: 共 ${output.length} 字符]\n")
                append("[完整输出已保存到: /$TOOL_OUTPUTS_DIR/$fileName]\n")
                append("[可用 read_file 工具读取: $TOOL_OUTPUTS_DIR/$fileName]\n\n")
                append(preview)
                append("\n... [已截断,使用 read_file 查看完整输出]")
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 写盘失败:降级为简单截断,不阻塞工具调用主流程
            Logger.w(TAG, "工具输出落盘失败,降级为简单截断: ${e.message}", e)
            output.take(MAX_TOOL_RESULT_CHARS) +
                "\n\n…(结果已截断,完整输出落盘失败: ${e.message})"
        }
    }
}

/**
 * v1.x: 清理超过 [TOOL_OUTPUT_RETENTION_MS] 的工具输出文件。
 *
 * 在 App 启动时(MuseApp.onCreate)调用一次,避免 tool_outputs/ 目录无限累积。
 * 遍历目录下所有 .txt 文件,按 lastModified 判定是否过期,删除过期文件。
 * 失败的删除操作仅记日志,不影响其他文件清理。
 */
fun cleanupOldToolOutputs(context: Context, retentionMs: Long = TOOL_OUTPUT_RETENTION_MS) {    val dir = File(context.filesDir, TOOL_OUTPUTS_DIR)
    if (!dir.exists() || !dir.isDirectory) return
    val cutoff = System.currentTimeMillis() - retentionMs
    var deleted = 0
    dir.listFiles()?.forEach { f ->
        if (!f.isFile) return@forEach
        val mtime = runCatching { f.lastModified() }.getOrDefault(0L)
        if (mtime > 0 && mtime < cutoff) {
            runCatching { f.delete() }
                .onSuccess { deleted++ }
                .onFailure { Logger.w("ToolOrchestrator", "删除过期工具输出失败: ${f.name}", it) }
        }
    }
    if (deleted > 0) {
        Logger.i("ToolOrchestrator", "清理过期工具输出文件: 删除 $deleted 个")
    }
}

/**
 * v1.0.54: 从文本中提取表情包绝对路径(与 MessageBubble.extractStickerPaths 同款正则)。
 * send_sticker 的 toolDisplay content 只保留路径,供 MessageBubble 渲染贴纸图片。
 */
private val STICKER_PATH_PATTERN = Regex(
    """(/[^\s\]]*?/stickers/[^\s\]]+\.(?:png|jpg|jpeg|gif|webp|bmp))""",
    RegexOption.IGNORE_CASE,
)

private fun extractStickerPaths(text: String): List<String> =
    STICKER_PATH_PATTERN.findAll(text).map { it.groupValues[1] }.distinct().toList()
