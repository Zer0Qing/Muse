package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
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
import io.zer0.muse.data.session.ToolRoundEntity
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.R
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ToolCallRecord
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.coroutineContext
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

/**
 * Phase 3: 工具输出落盘超时(毫秒)。
 *
 * 落盘是"完整输出可被 read_file 读取"的增强路径,不是工具结果本身;
 * 超时(或写入异常)时降级为内存截断并记日志,绝不能让磁盘 IO 无限阻塞工具循环。
 */
internal const val TOOL_OUTPUT_WRITE_TIMEOUT_MS = 5_000L

/**
 * Phase 3: 只读工具有限并发开关(常量开关,默认关闭 = 保守)。
 *
 * 关闭时保持 B-38 的串行执行语义(同轮 tool-call 逐个执行,零行为变化);
 * 打开且整轮均为只读工具时按 [READ_ONLY_TOOL_MAX_PARALLELISM] 并发执行,
 * 结果仍按原始 tool-call 顺序回填。写入/外部副作用工具任何情况下都串行。
 * 用户可经「实验功能」的 parallelReadOnlyTools 一键打开或关闭(见 [ExperimentsConfig.parallelReadOnlyTools])。
 *
 * 整改复核(2026-09-20):此前默认开启,但并发执行时序只做过单测、没有设备态验证,
 * 与计划书「默认保守、由用户显式开启」不符,故默认值回落为 false。
 * 注意:上面的「实验功能」开关描述与实现不符 —— 生产代码里并没有
 * ExperimentsConfig.parallelReadOnlyTools 这个字段,本常量也没有任何生产接线,
 * 想开启只能改这个常量(或先把实验开关补齐再接线)。当前状态下并发路径仅被单测覆盖。
 */
internal const val PARALLEL_READ_ONLY_TOOLS_ENABLED_DEFAULT = false

/** Phase 3: 只读工具并发上限(有限并发,避免打爆网络/服务端限流)。 */
internal const val READ_ONLY_TOOL_MAX_PARALLELISM = 3

/**
 * Phase 3: 工具执行类别 — 决定同轮内是否允许与其它工具并发。
 *
 * 只读/幂等工具无共享副作用,可有限并发;写入/外部副作用工具(以及所有未知工具)
 * 必须串行,保证调用顺序与副作用可见性。
 */
internal enum class ToolExecCategory {
    /** 只读/幂等:可有限并发。 */
    READ_ONLY,

    /** 写入/外部副作用/未知:必须串行。 */
    SERIAL,
}

/**
 * Phase 3: 只读工具白名单(保守枚举)。
 *
 * 仅登记确定无外部副作用的查询类工具;未登记的工具(含自定义 skill / MCP / 未来新工具)
 * 一律按 [ToolExecCategory.SERIAL] 处理,避免未知副作用并发竞态。
 */
private val READ_ONLY_TOOL_NAMES: Set<String> = setOf(
    // 基础计算 / 状态查询
    "get_current_time", "calculator", "echo", "current_status",
    // 网络只读(搜索 / 抓取 / 天气 / DNS)
    "web_search", "web_fetch", "get_weather", "get_public_ip", "ping_host", "dns_lookup", "translate",
    // 设备 / 系统信息
    "get_device_info", "get_battery_info", "get_network_info", "get_storage_info", "get_memory_info",
    "get_display_info", "get_cpu_info", "get_sensors_list", "get_foreground_app", "get_wifi_info",
    "get_bluetooth_devices", "get_brightness", "get_volume", "get_location",
    "get_contacts_count", "get_contacts_list", "get_recent_notifications", "screen_time",
    "calendar_today", "get_calendar_today", "list_installed_apps", "list_reminders",
    "list_stickers", "list_skills",
    // 文件只读
    "read_file", "read_public_file", "list_public_files", "list_dir",
    // 记忆 / 资料库 / 笔记只读
    "search_memory", "recall_experience", "resource_list", "resource_search", "resource_get",
    "quick_note_list", "quick_note_get", "quick_note_search",
    "scheduled_task_list", "scheduled_task_get_history",
    // 纯本地计算辅助
    "json_pretty", "hash_text", "generate_uuid", "random_number", "base64_decode", "url_decode",
    "generate_password",
    // 屏幕只读(不含截图 / 点击 / 输入等副作用)
    "ui_get_page_info", "ui_get_current_app", "screen_read", "screen_current_app", "screen_permission_status",
)

/** Phase 3: 判定工具执行类别;未知工具一律串行([ToolExecCategory.SERIAL])。 */
internal fun classifyToolExecution(toolName: String): ToolExecCategory =
    if (toolName in READ_ONLY_TOOL_NAMES) ToolExecCategory.READ_ONLY else ToolExecCategory.SERIAL

/**
 * Phase 3: 工具输出默认写盘实现(IO 线程覆盖写)。
 * 生产链路固定使用;单测可注入慢写/抛错替身以确定性验证超时与降级路径。
 */
private val DEFAULT_TOOL_OUTPUT_WRITER: suspend (File, String) -> Unit = { file, content ->
    withContext(Dispatchers.IO) { file.writeText(content) }
}

/** 工具调用循环内 conversationHistory 的工具链部分最大消息条数。 */
internal const val MAX_TOOL_CHAIN_MESSAGES = 30

/** v1.x: 简单任务(无 task_plan)的默认最大轮次。 */
internal const val DEFAULT_MAX_TOOL_ROUNDS = 10

/** v1.x: 工具调用循环绝对上限(防死循环兜底),即使 task_plan 步骤再多也不超过此值。 */
internal const val MAX_TOOL_ROUNDS_HARD_CAP = 25

/** 工具结果落回消息前,清理断点记录允许占用的最长时间。 */
private const val PENDING_TOOL_CLEANUP_TIMEOUT_MS = 2_000L

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
    /** Provider 原生联网搜索开关；启用时本轮不发送本地 web_search 工具。 */
    val nativeWebSearch: Boolean = false,
    /**
     * 审查修复 (2.0 A-01): 强制使用主模型重跑本轮 — toolModel 轮(round>1)若直接产出
     * 最终答复(无 toolCalls),说明本轮实为"最终回复轮",由 ChatViewModel 以该标志
     * 递归重跑一轮,保证最终答复由主模型输出。置位后 isToolRound 判定被短路,
     * 不会再触发二次补轮(防无限递归)。
     */
    val forceMainModel: Boolean = false,
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
        val citationUrls: List<String> = emptyList(),
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
    /** F-12: 统一链路 id(源自 StreamRunState.traceId),工具执行审计与日志携带。 */
    val traceId: String = "",
    val initialAssistantId: Uuid,
    val baseHistorySize: Int,
    val maxRounds: Int,
    val tools: List<ToolDefinition>,
    val skillMap: Map<String, SkillEntity>,
    /** Per-turn immutable exposure and execution routing decision. Null keeps legacy callers compatible. */
    val routeSnapshot: ToolRouteSnapshot? = null,
    val model: Model?,
    val providerConfig: ProviderConfig?,
    val temperature: Float?,
    val maxTokens: Int?,
    val reasoningLevel: ReasoningLevel,
    val webSearchEnabled: Boolean = false,
    /** 使用 Provider 原生联网搜索时，关闭本地 web_search 工具，避免两套搜索叠加。 */
    val nativeWebSearch: Boolean = false,
    val experiments: ExperimentsConfig = ExperimentsConfig(),
    val assistant: AssistantEntity? = null,
    /** B7-04: 首轮预置已产出正文(继续生成时从断点续写)。 */
    val initialBuilderContent: String = "",
    /** B7-04: 首轮预置已产出 reasoning。 */
    val initialReasoningContent: String = "",
    /** 新链路回合归属；为空时仅保留旧链路行为。 */
    val turnId: String = "",
    /** 当前生成的统一身份；旧调用方为空时由编排器生成兼容身份。 */
    val generationIdentity: io.zer0.muse.session.GenerationIdentity? = null,
    /** 由主会话宿主注入，供 search_memory 等隔离敏感工具使用。 */
    val toolExecutionContext: ToolExecutionContext? = null,
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
 * Phase 3: 工具调用循环的结构化终止原因。
 *
 * 与 [ToolLoopResult.success] 的关系:`success` 仅在 [COMPLETED] 时为 true;
 * 轮次上限 / 重复调用停滞 / 连续失败 / 预算耗尽 / 无效工具调用 / 流式错误
 * 都是异常退出,调用方据此区分"正常答复"与"熔断收尾"。
 */
enum class ToolLoopTerminationReason {
    /** 正常结束:LLM 产出最终答复,无待执行工具调用。 */
    COMPLETED,

    /** 达到工具调用轮次上限(由 computeMaxRounds 计算)。 */
    ROUND_LIMIT,

    /** 连续多轮相同 tool_call,判定停滞(熔断)。 */
    REPEATED_TOOL_CALLS,

    /** 连续工具失败达到阈值(熔断)。 */
    CONSECUTIVE_FAILURES,

    /** turn 级调用数 / 时间预算耗尽(熔断)。 */
    BUDGET_EXHAUSTED,

    /** 模型本轮 toolCalls 经清洗后全部无效,编排器注入提示后结束。 */
    INVALID_TOOL_CALLS,

    /** 流式请求错误(网络 / 限流 / API 错误),循环失败退出。 */
    STREAM_ERROR,
}

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
    /**
     * 是否正常完成最终答复。
     *
     * Phase 3 起仅当 [terminationReason] == [ToolLoopTerminationReason.COMPLETED] 时为 true;
     * 轮次上限 / 重复调用 / 连续失败 / 预算耗尽等熔断退出一律为 false(不再被标成成功)。
     */
    val success: Boolean,
    val error: ToolLoopError? = null,
    /** 工具调用循环正常结束时(无 tool_calls)的最终 assistant 消息;达到轮次上限等异常退出时为 null。 */
    val finalAssistantMessage: UIMessage? = null,
    /** 本次工具循环的结构化工具轮，随最终消息一次性提交。 */
    val toolRounds: List<ToolRoundEntity> = emptyList(),
    /** Phase 3: 结构化终止原因;与 [success] 一致(success == terminationReason == COMPLETED)。 */
    val terminationReason: ToolLoopTerminationReason = ToolLoopTerminationReason.COMPLETED,
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
/**
 * P2-33: 阻塞型工具超时的「结果闸门」— 在结构上保证超时后的迟到结果无法落地。
 *
 * 背景:阻塞型工具(WebView / 阻塞 IO)内部用 runBlocking 桥接,[Future.cancel] 只能
 * 发出中断信号;忽略中断的调用会继续跑完并产出结果。旧实现里超时分支直接放弃
 * `future.get()`,迟到结果只是「无人读取」,任何后续把该 future/回调接回状态写入点
 * 的改动都会重新引入污染(超时后结果覆盖 TaskCard / 消息 / toolCallRecord)。
 *
 * 本闸门把「结果发布」收敛为一次 CAS:超时先 [abandon],之后到达的发布一律被拒绝并计数,
 * 编排器只从闸门读值 —— 迟到结果不仅没人读,而且写不进去。
 */
internal class ToolResultGate(
    /** 迟到结果被丢弃时的回调(编排器据此记日志/计数,单测据此断言)。 */
    private val onLateDrop: () -> Unit = {},
) {
    private val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
    private val published = java.util.concurrent.atomic.AtomicBoolean(false)
    private val result = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** 超时作废(幂等):此后 [publish] 一律拒绝。 */
    fun abandon() {
        abandoned.set(true)
    }

    /** 该次执行是否已被超时作废。 */
    val isAbandoned: Boolean get() = abandoned.get()

    /**
     * 结果发布(执行线程调用):未作废时仅接受第一个结果并返回 true;
     * 已作废(超时)时丢弃结果、回调诊断并返回 false。
     */
    fun publish(value: String): Boolean {
        if (abandoned.get()) {
            onLateDrop()
            return false
        }
        if (!published.compareAndSet(false, true)) return false
        // 发布与超时可能并发:发布后若已被作废,值同样不可读(valueOrNull 复核)
        result.set(value)
        return !abandoned.get()
    }

    /** 编排器读值:已被作废(超时)时返回 null,迟到结果永不外泄。 */
    fun valueOrNull(): String? = if (abandoned.get()) null else result.get()
}

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
    // F-08: Agent Run 收据内存账本(每次工具执行写入,含 parentRunId 调用链)。
    private val agentRunTracker: io.zer0.muse.data.audit.AgentRunTracker? = null,
    // v1.x: 会话级浏览器实例注册表(每个会话独立 WebView)。
    private val browserManagerRegistry: BrowserManagerRegistry = BrowserManagerRegistry(context),
    // R-TEST-10: 工具超时可注入,生产默认 2 分钟
    private val toolTimeoutMs: Long = TOOL_TIMEOUT_MS,
    // v1.x: 统一登记工具执行资源，供按 session 取消和 late-result 诊断使用。
    private val executionRegistry: io.zer0.muse.session.SessionExecutionRegistry? = null,
    /**
     * Phase 3: 只读工具有限并发开关(默认开启 = 灰度;测试可注入 false 验证串行路径)。
     *
     * 打开后,仅当同一轮全部为只读工具(白名单,且不路由到 skill)时才按
     * [READ_ONLY_TOOL_MAX_PARALLELISM] 有限并发;含写入/副作用工具则整轮串行。
     * 用户可经「实验功能」的 [ExperimentsConfig.parallelReadOnlyTools] 一键关闭。
     */
    private val parallelReadOnlyToolsEnabled: Boolean = PARALLEL_READ_ONLY_TOOLS_ENABLED_DEFAULT,
    /** Phase 3: 工具输出落盘超时(测试可注入更短值)。 */
    private val toolOutputWriteTimeoutMs: Long = TOOL_OUTPUT_WRITE_TIMEOUT_MS,
    /** Phase 3: 工具输出写盘实现(测试可注入慢写/抛错替身)。 */
    private val toolOutputWriter: suspend (File, String) -> Unit = DEFAULT_TOOL_OUTPUT_WRITER,
) {

    private val routeGuard = ToolRouteExecutionGuard(toolRegistry)

    private companion object {
        const val TAG = "ToolOrchestrator"

        /**
         * F-12/B-28: 审计脱敏 — 移除文本中 URL 的 query 部分。
         * 防止工具参数/结果里的 URL 携带 API key 等敏感参数被写入审计收据。
         * 仅保留协议+主机+路径;query/fragment 一律丢弃。
         */
        private val URL_QUERY_SANITIZER = Regex("""(https?://[^\s"'\]\}]+)(\?[^\s"'\]\}]*)?(#\S*)?""")

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

    /**
     * F-12/B-28: 对文本内所有 URL 去掉 query/fragment(见 [URL_QUERY_SANITIZER])。
     * 非 URL 文本原样返回。
     */
    private fun sanitizeUrlQuery(text: String): String =
        URL_QUERY_SANITIZER.replace(text) { match -> match.groupValues[1] }

    /**
     * F-07: 工具执行状态机。
     *  - [SUCCESS]: 执行成功;
     *  - [FAILED]: 执行失败(工具报错/审批拒绝/超时/异常回传);
     *  - [INTERRUPTED]: 执行被流式中断(用户停止/会话切换),结果不完整;
     *  - [APPROVAL_PENDING]: 等待用户审批,生成循环挂起。
     */
    enum class ToolExecStatus { SUCCESS, FAILED, INTERRUPTED, APPROVAL_PENDING, TIMED_OUT, CANCELLED }

    /**
     * Phase 3: 工具执行终态 → 任务卡步骤状态映射。
     *
     * TIMED_OUT / CANCELLED 不再被折叠成 FAILED,任务卡因此能呈现真实终态
     * (折叠态步骤条与展开态步骤行都会据此显示超时/取消图标)。
     */
    private fun ToolExecStatus.toTaskStepStatus(): TaskStepStatus = when (this) {
        ToolExecStatus.SUCCESS -> TaskStepStatus.SUCCESS
        ToolExecStatus.FAILED -> TaskStepStatus.FAILED
        ToolExecStatus.INTERRUPTED -> TaskStepStatus.CANCELLED
        ToolExecStatus.CANCELLED -> TaskStepStatus.CANCELLED
        ToolExecStatus.TIMED_OUT -> TaskStepStatus.TIMED_OUT
        ToolExecStatus.APPROVAL_PENDING -> TaskStepStatus.RUNNING
    }

    private data class ToolExecResult(
        val idx: Int,
        val tc: ToolCall,
        val finalToolResult: String,
        val isSuccess: Boolean,
        /** v1.x: 展示给用户的结果(失败时不含 LLM 引导语),null 时回退 [finalToolResult]。 */
        val displayResult: String? = null,
        /** F-07: 执行状态(默认 SUCCESS,失败/中断点显式标注)。 */
        val status: ToolExecStatus = ToolExecStatus.SUCCESS,
        /** F-08: 工具执行耗时(ms),供 AgentRunRecord 收据。 */
        val durationMs: Long = 0L,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
        val executionId: String = "",
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
        var currentAssistantId = params.initialAssistantId
        var firstTokenTime = 0L
        val citationUrls = mutableListOf<String>()
        val toolRounds = mutableListOf<ToolRoundEntity>()
        var hasToolCalls = true
        var nativeFallbackUsed = false
        var finalAssistantMessage: UIMessage? = null
        // A-07: 非正常退出原因(卡死/连续失败),用于收尾消息文案
        var abortReason: String? = null
        // Phase 3: 结构化终止原因 — 每个退出路径显式标注,循环末尾兜底为 ROUND_LIMIT。
        var terminationReason: ToolLoopTerminationReason? = null

        // M3.3: 统一预算/停止状态源 — ToolExecutionPolicy 承载轮次上限/总调用数/连续失败/重复指纹/时间预算/输出截断。
        // 动态最大轮次(task_plan steps*2+5 / 无 task_plan 10 / 上限 25)仍由 computeMaxRounds 计算,
        // 结果写入 execPolicy.maxRounds,循环内不再维护独立的 maxRounds 局部变量。
        val execPolicy = ToolExecutionPolicy(
            initialMaxRounds = computeMaxRounds(conversationHistory, params.maxRounds, params.sessionId),
        )
        val executionIdentity = params.generationIdentity ?: io.zer0.muse.session.GenerationIdentity(
            sessionId = params.sessionId,
            turnId = params.turnId.ifBlank { "turn-${params.traceId.ifBlank { currentAssistantId.toString() }}" },
            generationId = params.traceId.ifBlank { "generation-${currentAssistantId}" },
            streamId = "stream-${params.traceId.ifBlank { currentAssistantId.toString() }}",
        )
        Logger.i(TAG, "Agent Loop 开始 | sessionId=${params.sessionId} | 初始最大轮次: ${execPolicy.maxRounds}")

        while (hasToolCalls && round < execPolicy.maxRounds) {
            round++
            val stepStartedAt = System.currentTimeMillis()
            Logger.d(TAG, "Agent Loop step $round/${execPolicy.maxRounds} 开始 | sessionId=${params.sessionId}")

            // M3.2: turn 级时间预算(默认关闭;配置后超限即停,与连续失败早停同级的兜底防线)
            if (execPolicy.isTimeBudgetExhausted()) {
                abortReason = "工具循环总耗时预算耗尽"
                terminationReason = ToolLoopTerminationReason.BUDGET_EXHAUSTED
                Logger.w(
                    TAG,
                    "Agent Loop 时间预算耗尽,提前终止 | sessionId=${params.sessionId} | " +
                        "traceId=${params.traceId} | 已执行调用=${execPolicy.executedCalls}",
                )
                break
            }

            // v1.x: 每轮动态重算最大轮次(task_plan 可能在循环内才产生,需要扩大配额)
            val recomputedMax = computeMaxRounds(conversationHistory, params.maxRounds, params.sessionId)
            if (recomputedMax != execPolicy.maxRounds) {
                Logger.d(TAG, "Agent Loop maxRounds 更新: ${execPolicy.maxRounds} → $recomputedMax (task_plan 已产生)")
                execPolicy.updateMaxRounds(recomputedMax)
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
                    nativeWebSearch = params.nativeWebSearch && isFirstRound && !nativeFallbackUsed,
                )
            )

            when (outcome) {
                is StreamRoundResult.Error -> {
                    // 原生搜索失败时降级到本地搜索链：其中先尝试用户已配置的 API，
                    // 最后才是 Bing HTTP 与百度 HTTP。只降级一次，避免重复计费请求。
                    if (params.nativeWebSearch && !nativeFallbackUsed) {
                        nativeFallbackUsed = true
                        round--
                        Logger.w(TAG, "原生搜索请求失败，降级到用户 API/HTTP 搜索链")
                        continue
                    }
                    Logger.w(
                        TAG,
                        "Agent Loop 因流式错误终止 | sessionId=${params.sessionId} | traceId=${params.traceId}" +
                            " | round=$round | type=${outcome.type} | msg=${outcome.message}",
                    )
                    return ToolLoopResult(
                        finalAssistantId = currentAssistantId,
                        round = round,
                        totalToolCallCount = execPolicy.emittedToolCallCount,
                        totalCharCount = execPolicy.streamedCharCount,
                        firstTokenTime = firstTokenTime,
                        citationUrls = citationUrls.toList(),
                        success = false,
                        error = ToolLoopError(
                            type = outcome.type,
                            message = outcome.message,
                            partialContent = outcome.partialContent,
                            partialReasoning = outcome.partialReasoning,
                        ),
                        toolRounds = toolRounds.toList(),
                        terminationReason = ToolLoopTerminationReason.STREAM_ERROR,
                    )
                }

                is StreamRoundResult.Success -> {
                    citationUrls.addAll(outcome.citationUrls)
                    execPolicy.recordStreamedChars(outcome.contentLength)
                    if (outcome.firstTokenTime > 0L && firstTokenTime == 0L) {
                        firstTokenTime = outcome.firstTokenTime
                    }

                    if (!outcome.hasToolCalls) {
                        hasToolCalls = false
                        finalAssistantMessage = outcome.assistantMessage
                        terminationReason = ToolLoopTerminationReason.COMPLETED
                        Logger.d(TAG, "Agent Loop step $round/${execPolicy.maxRounds} 结束(无工具调用,循环正常结束)")
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
                    execPolicy.recordEmittedToolCalls(toolCallList.size)

                    // v1.0.48: 过滤后无有效 toolCalls,按"无工具调用"处理本轮
                    if (toolCallList.isEmpty()) {
                        hasToolCalls = false
                        // v1.0.81: 不再留 content 为空的气泡(那会显示空 UI 且无任何提示)。
                        // 工具调用被清洗通常是模型输出了不完整/非法的工具参数(Sanitizer 丢弃),
                        // 给用户和模型一个明确提示,让本轮可观测、下一轮可重试。
                        val rawCount = assistantToolMsg.toolCalls?.size ?: 0
                        val hint = if (rawCount > 0) {
                            "模型发起了 $rawCount 个工具调用,但参数格式异常未能执行。请重新描述你的需求,我会再试一次。"
                        } else {
                            "模型未输出有效的工具调用,本轮无工具可执行。如需搜索或其他工具,请重新描述你的需求。"
                        }
                        finalAssistantMessage = assistantToolMsg.copy(
                            toolCalls = emptyList(),
                            content = hint,
                        )
                        // Phase 3: 清洗后无有效调用不是"正常完成最终答复" — 记录结构化原因,
                        // 收尾时同时把 hint 作为 error message,便于上层区分模型答复与编排器兜底。
                        abortReason = hint
                        terminationReason = ToolLoopTerminationReason.INVALID_TOOL_CALLS
                        Logger.w(
                            TAG,
                            "Agent Loop step $round/${execPolicy.maxRounds}: toolCalls 清洗后为空(raw=$rawCount),注入提示",
                        )
                        break
                    }

                    // M3.3: 连续无进展早停检测 — 归入 execPolicy 轮级签名重复检测,
                    // 在回填前判断,避免卡死时还执行重复工具。
                    val currentSignature = toolCallSignature(toolCallList)
                    val progressDecision = execPolicy.checkRoundProgress(currentSignature)
                    if (!progressDecision.allowed) {
                        Logger.w(
                            TAG,
                            "Agent Loop 检测到连续相同 tool_call(${progressDecision.detail})" +
                                " | sessionId=${params.sessionId} | signature=$currentSignature",
                        )
                        Logger.w(TAG, "Agent Loop 连续相同 tool_call 轮次达上限,判定卡死,提前终止")
                        hasToolCalls = false
                        terminationReason = ToolLoopTerminationReason.REPEATED_TOOL_CALLS
                        // A-07: 记录退出原因,收尾时注入明确文案(否则用户只看到工具卡片无任何回复)
                        abortReason = "检测到工具调用停滞(连续 ${execPolicy.noProgressRoundsCount} 轮无进展),已自动停止。如需继续,可以让我重新处理。"
                        // 不把 assistantToolMsg 加入 history,避免遗留无 tool 结果的 assistant 消息
                        break
                    }

                    // v1.0.62: 历史与持久化必须使用清洗后的 toolCalls,
                    // 避免空 name/空 arguments 的非法调用在下一轮请求中触发 400。
                    // v1.0.80: 旧实现仅当数量变化时才用清洗结果 — 若 sanitize 只修复了
                    // arguments(坏 JSON → "{}")而数量不变,坏 arguments 仍会进入历史回传,
                    // 下一轮请求触发 400 "Assistant tool call arguments must be valid JSON"。
                    // 改为始终使用清洗后的 toolCallList。
                    val cleanedAssistantToolMsg = assistantToolMsg.copy(toolCalls = toolCallList)

                    // 带 tool_calls 的 assistant 消息只属于本轮 Provider 协议历史。
                    // MessageEntity 不保存 toolCalls 字段，若把 content 为空的协议消息落库，
                    // 重载后只会剩下一条没有任何内容的 assistant 空壳。
                    conversationHistory.add(cleanedAssistantToolMsg)
                    if (
                        cleanedAssistantToolMsg.content.isNotBlank() ||
                        !cleanedAssistantToolMsg.reasoning.isNullOrBlank() ||
                        cleanedAssistantToolMsg.imageUrls.isNotEmpty() ||
                        cleanedAssistantToolMsg.imageBase64List.isNotEmpty() ||
                        cleanedAssistantToolMsg.toolCallInfo != null
                    ) {
                        persistAssistantToolMsg(params.sessionId, cleanedAssistantToolMsg, host)
                    }

                    // 断点续传:持久化未完成的工具调用
                    savePendingToolCalls(params.sessionId, toolCallList, executionIdentity, host)

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
                        val taskCard = TaskCardData.fromToolCalls(context, currentAssistantId, taskCardToolCalls)
                        accessor.update {
                            it.copy(taskCards = it.taskCards + (id to taskCard))
                        }
                        taskCardCoordinator.updateTaskCardPhase(id, TaskCardPhase.EXECUTING)
                        id
                    } else null

                    // 并行/串行执行工具调用
                    // v1.0.47 P6-2: 弱工具模型降级为串行执行,避免并行 tool_calls 导致格式错乱
                    // Phase 3: stateLock/approvalLock 仅在只读并发轮创建;串行路径传 null(行为与现状完全一致)
                    val executeToolCall: suspend (Int, ToolCall, Mutex?, Mutex?) -> ToolExecResult =
                        { idx, tc, stateLock, approvalLock ->
                        val executionJob = coroutineContext[Job]
                        val executionId = executionRegistry?.register(
                            identity = executionIdentity,
                            kind = io.zer0.muse.session.ExecutionKind.TOOL,
                            cancel = { executionJob?.cancel() },
                        )
                        executionId?.let { id -> executionRegistry?.start(id) }
                        // F-13: 非阻塞进度 — 当前工具名进 toolProgressMessage(UI 显示),
                        // 工具阶段结束(execResults 回填后)清除, 与 F-07 状态机联动
                        accessor.update {
                            it.copy(toolProgressMessage = context.getString(R.string.tool_running, tc.name))
                        }
                        try {
                            val result = executeSingleToolCall(
                                params, taskCardId, tc, idx, host, taskCardCoordinator, execPolicy,
                                stateLock, approvalLock,
                            ).copy(executionId = executionId ?: "")
                            persistToolRoundIncrementally(params, round, stepStartedAt, result)
                            executionId?.let { id -> executionRegistry?.finish(id) }
                            result
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            executionId?.let { id -> executionRegistry?.markCancelled(id) }
                            // 取消时协程上下文已不可挂起；在 NonCancellable 中把磁盘断点明确
                            // 标为 ABORTED，避免重启后将已取消/可能部分副作用的调用误当成可执行任务。
                            withContext(NonCancellable) {
                                runCatching {
                                    PendingToolCallStore.updateState(
                                        tc.id,
                                        PendingToolCallStore.ABORTED,
                                        "generation_cancelled",
                                    )
                                }.onFailure { error ->
                                    Logger.w(TAG, "取消工具后写入 ABORTED 失败: ${tc.id}", error)
                                }
                            }

                            // F-07: 中断标记 — 工具执行被流式中断(用户停止/会话切换)。
                            // 结果不完整, 记录 INTERRUPTED 状态日志与任务卡步骤, 再向上传播取消。
                            Logger.w(TAG, "工具 ${tc.name} 被中断(INTERRUPTED) | sessionId=${params.sessionId}")
                            val interruptedAt = System.currentTimeMillis()
                            val interruptedText = "[中断] 工具 ${tc.name} 执行被取消"
                            // Phase 3: 取消是独立终态,不再落成 FAILED(TaskStepStatus.CANCELLED)。
                            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                                s.copy(
                                    status = ToolExecStatus.CANCELLED.toTaskStepStatus(),
                                    result = interruptedText,
                                    finishedAt = interruptedAt,
                                )
                            }
                            // Cancellation previously vanished from the message stream and the
                            // tool history; record it so the中断 is visible after the fact.
                            accessor.update { state ->
                                state.copy(
                                    toolCallHistory = state.toolCallHistory + ToolCallRecord(
                                        toolName = tc.name,
                                        arguments = tc.arguments,
                                        result = interruptedText,
                                        isSuccess = false,
                                        timestamp = interruptedAt,
                                        traceId = params.traceId,
                                        sessionId = params.sessionId,
                                        turnId = params.turnId,
                                        generationId = executionIdentity.generationId,
                                        roundIndex = round,
                                        toolCallId = tc.id,
                                        executionId = executionId ?: "",
                                        startedAt = stepStartedAt,
                                        finishedAt = interruptedAt,
                                        status = ToolExecStatus.CANCELLED.name,
                                        terminationReason = interruptedText,
                                    ),
                                )
                            }
                            throw ce
                        } catch (e: Exception) {
                            executionId?.let { id -> executionRegistry?.fail(id) }
                            // 工具已经可能完成了外部副作用(例如写入记忆),但回调/清理阶段异常
                            // 不能让整个工具循环直接消失,否则用户只会看到“执行中”。
                            Logger.e(TAG, "工具结果回传失败: ${tc.name}", e)
                            failedToolResult(
                                taskCardId = taskCardId,
                                tc = tc,
                                idx = idx,
                                host = host,
                                taskCardCoordinator = taskCardCoordinator,
                                reason = e.message ?: "未知错误",
                            )
                        }
                    }

                    // B-38: 默认串行执行 — async+awaitAll 并发下 execPolicy.afterExecute /
                    // taskCardCoordinator / PendingToolCallStore 等非原子状态写入存在竞态
                    // (ToolExecutionPolicy 明确按"单 turn 顺序使用"设计)。LLM 单帧工具
                    // 调用通常 1-3 个,串行性能可接受,且与弱工具模型降级路径一致。
                    // Phase 3: 开关开启且整轮均为只读工具时,按有限并发执行;所有 per-turn 状态写入
                    // (TaskCard / PendingToolCallStore / execPolicy / hook / host 回调)经 stateLock 串行化,
                    // 审批提示经 approvalLock 串行化,结果仍按原始 tool-call 顺序回填。
                    if (toolCallList.size > 1 && WeakToolUseDetector.isWeakToolModel(params.model)) {
                        Logger.i(TAG, "弱工具模型检测到 ${toolCallList.size} 个工具调用,串行执行 | model=${params.model?.id}")
                    }
                    val parallelRound = shouldExecuteRoundInParallel(toolCallList, params)
                    val stateLock = if (parallelRound) Mutex() else null
                    val approvalLock = if (parallelRound) Mutex() else null
                    if (parallelRound) {
                        Logger.i(
                            TAG,
                            "只读工具有限并发执行: ${toolCallList.size} 个调用" +
                                " | 并发上限=$READ_ONLY_TOOL_MAX_PARALLELISM | sessionId=${params.sessionId}",
                        )
                    }
                    val execResults: List<ToolExecResult> = executeToolRound(
                        toolCallList = toolCallList,
                        parallel = parallelRound,
                        execute = { idx, tc -> executeToolCall(idx, tc, stateLock, approvalLock) },
                    )

                    // 结构化记录本轮工具调用；UI 消息仍走兼容字段，重进会话时由此表恢复。
                    if (params.turnId.isNotBlank()) {
                        val finishedAt = System.currentTimeMillis()
                        toolRounds += execResults.map { result ->
                            ToolRoundEntity(
                                id = "${params.traceId.ifBlank { params.sessionId }}:$round:${result.tc.id}",
                                turnId = params.turnId,
                                roundIndex = round,
                                toolCallId = result.tc.id,
                                toolName = result.tc.name,
                                argsJson = result.tc.arguments,
                                resultJson = result.finalToolResult,
                                status = result.status.name,
                                startedAt = stepStartedAt,
                                finishedAt = finishedAt,
                                errorDetail = if (result.isSuccess) null else result.displayResult ?: result.finalToolResult,
                            )
                        }
                    }

                    // 按顺序回填结果到历史和 UI
                    for (result in execResults) {
                        val (idx, tc, finalToolResult, isSuccess, displayResult) = result
                        // F-07: 状态日志 — 每工具执行结束记录终态(SUCCESS/FAILED/INTERRUPTED/APPROVAL_PENDING)
                        Logger.d(
                            TAG,
                            "工具执行结束: ${tc.name} status=${result.status} isSuccess=$isSuccess" +
                                " | sessionId=${params.sessionId}",
                        )
                        // F-08: Agent Run 统一收据 — 内存账本 + 审计日志(可选持久化)。
                        // parentRunId: 本工具若是 delegate_agent/subagent_task 的发起者,
                        // 其子 agent 内的工具调用会以本 runId 为 parentRunId 形成调用链。
                        // F-11: exposedToolIds 记录本轮 LLM 可见工具(暴露快照证据,
                        // 与 calledToolId 对照可判定"工具是否被合理暴露")。
                        val isDelegationTool = tc.name == "delegate_agent" || tc.name == "subagent_task"
                        agentRunTracker?.record(
                            io.zer0.muse.data.audit.AgentRunRecord(
                                runId = tc.id,
                                parentRunId = null,
                                sessionId = params.sessionId,
                                round = round,
                                toolName = tc.name,
                                // F-12/B-28: 参数与结果摘要先脱敏 URL query,审计不记录 key 类敏感参数
                                argumentsSummary = sanitizeUrlQuery(tc.arguments.take(200)),
                                resultSummary = sanitizeUrlQuery(finalToolResult.take(200)),
                                status = result.status.name,
                                durationMs = result.durationMs,
                                exposedToolIds = params.tools.joinToString(",") { it.name },
                                traceId = params.traceId,
                            ),
                        )
                        auditLogger?.log(
                            category = "agent_run",
                            action = if (isDelegationTool) "delegate_tool" else "tool_exec",
                            target = tc.id,
                            detail = mapOf(
                                "sessionId" to params.sessionId,
                                "traceId" to params.traceId,
                                "round" to round,
                                "tool" to tc.name,
                                "status" to result.status.name,
                                "durationMs" to result.durationMs,
                                "isSuccess" to isSuccess,
                            ),
                        )
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
                        val finishedAt = System.currentTimeMillis()
                        val record = ToolCallRecord(
                            toolName = tc.name,
                            arguments = tc.arguments,
                            result = displayResult ?: finalToolResult,
                            isSuccess = isSuccess,
                            timestamp = finishedAt,
                            traceId = params.traceId,
                            sessionId = params.sessionId,
                            turnId = params.turnId,
                            generationId = executionIdentity.generationId,
                            roundIndex = round,
                            toolCallId = tc.id,
                            executionId = result.executionId,
                            startedAt = stepStartedAt,
                            finishedAt = finishedAt,
                            status = result.status.name,
                            terminationReason = if (isSuccess) {
                                null
                            } else {
                                (displayResult ?: finalToolResult).lineSequence().firstOrNull()?.take(200)
                            },
                        )

                        // 工具结果消息不能只放在内存 UI：切出会话、进程重启后仍需显示工具卡片。
                        // assistant(tool_calls) 已在上方落盘，这里补齐对应的 tool result 展示消息。
                        // 使用消息自身 id 做幂等 upsert，不影响当前 UI 是否正在展示该会话。
                        if (tc.name !in silentToolNames || tc.name == "send_sticker") {
                            persistAssistantToolMsg(params.sessionId, toolDisplay, host)
                        }
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

                        // M3.3: 连续失败早停 — 由 execPolicy(唯一状态源)判定
                        if (execPolicy.shouldAbortOnConsecutiveFailures()) {
                            Logger.w(
                                "ToolOrchestrator",
                                "连续 ${execPolicy.consecutiveFailuresCount} 次工具失败,提前终止工具调用循环 " +
                                    "(round=$round, tool=${tc.name})",
                            )
                            hasToolCalls = false
                            terminationReason = ToolLoopTerminationReason.CONSECUTIVE_FAILURES
                            // A-07: 记录退出原因,收尾时注入明确文案
                            abortReason = "连续 ${execPolicy.consecutiveFailuresCount} 次工具调用失败,已自动停止。如需继续,可以让我重新处理。"
                            break
                        }
                    }

                    // F-13: 工具阶段结束,清除"正在执行"进度(下一轮流式生成不残留)
                    accessor.update { it.copy(toolProgressMessage = null) }
                    taskCardCoordinator.updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)

                    // 同步 Agent 工作流计划到 UI
                    // v1.137: 为计划关联当前助手消息 ID,使计划卡固定在创建它的消息上随消息滚动,
                    // 而不是始终"跳"到最后一条助手消息(用户反馈"列表固定在底部不跟随滚动")。
                    val latestPlans = skillExecutor.getActivePlans(params.sessionId).mapValues { (_, plan) ->
                        if (plan.messageId == null) plan.copy(messageId = currentAssistantId.toString()) else plan
                    }
                    if (latestPlans.isNotEmpty()) {
                        accessor.update { it.copy(agentPlans = latestPlans) }
                    }

                    // 创建新的占位 assistant 消息接收下一轮流式回复
                    if (hasToolCalls && round < execPolicy.maxRounds) {
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
                        "Agent Loop step $round/${execPolicy.maxRounds} 结束 | 工具=[$toolNames]" +
                            " | 成功=$successCount 失败=$failCount | 耗时=${stepElapsedMs}ms",
                    )
                }
            }
        }

        // v1.0.74 fix: 轮次耗尽/卡死退出时 finalAssistantMessage 为 null,会话里没有任何
        // 收尾提示,任务卡悬空、用户以为助手坏了。注入一条明确的收尾消息。
        // A-07: 覆盖全部无收尾退出路径 — 卡死/连续失败早停(hasToolCalls 已置 false
        // 但 abortReason 非空)同样注入,并按退出原因区分文案。
        val roundLimitText = "[已达到工具调用轮次上限(${execPolicy.maxRounds} 轮),自动停止。如需继续,可以让我接着处理。]"
        if (finalAssistantMessage == null) {
            val reasonText = abortReason ?: if (hasToolCalls) roundLimitText else null
            if (reasonText != null) {
                finalAssistantMessage = UIMessage(
                    id = currentAssistantId,
                    role = MessageRole.ASSISTANT,
                    content = reasonText,
                )
                // B-04: 替换语义而非追加 — 占位消息已在 UI 中,追加同 id 会渲染两条相同气泡。
                // 命中既有同 id 消息则原地替换,否则追加新消息。
                accessor.updateMessages { list ->
                    if (list.any { it.id == finalAssistantMessage.id }) {
                        list.map { if (it.id == finalAssistantMessage.id) finalAssistantMessage else it }
                    } else {
                        list + finalAssistantMessage
                    }
                }
            }
        }

        // Phase 3: 结构化终态 — 循环条件退出(while 条件失败)只可能是轮次上限;
        // success 仅表示"正常完成最终答复",熔断/兜底退出一律为 false,
        // 并把终止原因作为 error message 交给上层(否则 ChatViewModel 只能报未知错误)。
        val effectiveReason = terminationReason ?: ToolLoopTerminationReason.ROUND_LIMIT
        val completed = effectiveReason == ToolLoopTerminationReason.COMPLETED
        val terminationDetail = when (effectiveReason) {
            ToolLoopTerminationReason.COMPLETED -> null
            ToolLoopTerminationReason.ROUND_LIMIT -> roundLimitText
            else -> abortReason ?: "工具调用循环提前终止(${effectiveReason.name})"
        }

        Logger.i(
            TAG,
            "Agent Loop 结束 | sessionId=${params.sessionId} | rounds=$round/${execPolicy.maxRounds}" +
                " | toolCalls=${execPolicy.emittedToolCallCount} | chars=${execPolicy.streamedCharCount}" +
                " | success=$completed | reason=$effectiveReason",
        )
        return ToolLoopResult(
            finalAssistantId = currentAssistantId,
            round = round,
            totalToolCallCount = execPolicy.emittedToolCallCount,
            totalCharCount = execPolicy.streamedCharCount,
            firstTokenTime = firstTokenTime,
            citationUrls = citationUrls.toList(),
            success = completed,
            error = if (completed) null else ToolLoopError(
                type = ChatErrorType.TOOL_ERROR,
                message = terminationDetail ?: effectiveReason.name,
            ),
            finalAssistantMessage = finalAssistantMessage,
            toolRounds = toolRounds.toList(),
            terminationReason = effectiveReason,
        )
    }

    private suspend fun persistToolRoundIncrementally(
        params: ToolLoopParams,
        round: Int,
        startedAt: Long,
        result: ToolExecResult,
    ) {
        if (params.turnId.isBlank()) return
        val now = System.currentTimeMillis()
        val entity = ToolRoundEntity(
            id = "${params.traceId.ifBlank { params.sessionId }}:$round:${result.tc.id}",
            turnId = params.turnId,
            roundIndex = round,
            toolCallId = result.tc.id,
            toolName = result.tc.name,
            argsJson = result.tc.arguments,
            resultJson = result.finalToolResult,
            status = result.status.name,
            startedAt = startedAt,
            finishedAt = now,
            errorDetail = if (result.isSuccess) null else result.displayResult ?: result.finalToolResult,
        )
        resultOf { sessionRepository.upsertToolRound(entity) }
            .onError { msg, t ->
                Logger.w(TAG, "工具轮增量落盘失败: ${result.tc.name}: $msg", t)
            }
    }

    /**
     * Phase 3: 是否整轮按只读并发执行。
     *
     * 条件(需全部满足):
     *  - [parallelReadOnlyToolsEnabled] 开启(默认关闭 → 与 B-38 串行行为完全一致);
     *  - 同一轮 ≥2 个调用(单调用无需并发);
     *  - 非弱工具模型(沿用 B-38 的弱模型降级);
     *  - 每个调用都命中只读白名单,且不路由到 skill(未知工具/skill 一律串行)。
     *
     * 混合轮(含写入/副作用工具)整体串行,避免破坏"先读后写"的调用顺序语义。
     */
    private fun shouldExecuteRoundInParallel(toolCallList: List<ToolCall>, params: ToolLoopParams): Boolean {
        if (!parallelReadOnlyToolsEnabled || toolCallList.size <= 1) return false
        // 用户可在「实验功能」中一键关闭只读工具并行(灰度开关)
        if (!params.experiments.parallelReadOnlyTools) return false
        if (WeakToolUseDetector.isWeakToolModel(params.model)) return false
        return toolCallList.all { tc ->
            val routedToSkill = params.routeSnapshot?.routeFor(tc.name) is ToolRouteSnapshot.Route.Skill ||
                (params.routeSnapshot == null && params.skillMap.containsKey(tc.name))
            !routedToSkill && classifyToolExecution(tc.name) == ToolExecCategory.READ_ONLY
        }
    }

    /**
     * Phase 3: 按模式执行一轮工具调用。
     *
     * - [parallel] = false: 顺序执行(与既有行为一致);
     * - [parallel] = true: 以 [READ_ONLY_TOOL_MAX_PARALLELISM] 为上限并发执行,
     *   `awaitAll` 保证结果列表与 [toolCallList] 顺序一一对应(原始 tool-call 顺序回填)。
     */
    private suspend fun executeToolRound(
        toolCallList: List<ToolCall>,
        parallel: Boolean,
        execute: suspend (Int, ToolCall) -> ToolExecResult,
    ): List<ToolExecResult> {
        if (!parallel || toolCallList.size <= 1) {
            return toolCallList.mapIndexed { idx, tc -> execute(idx, tc) }
        }
        val semaphore = Semaphore(minOf(READ_ONLY_TOOL_MAX_PARALLELISM, toolCallList.size))
        return coroutineScope {
            toolCallList.mapIndexed { idx, tc ->
                async { semaphore.withPermit { execute(idx, tc) } }
            }.awaitAll()
        }
    }

    /**
     * Phase 3: per-turn 状态写入串行锁。
     *
     * 只读并发轮中,TaskCard/PendingToolCallStore/execPolicy/hook/host 回调等
     * "按单 turn 顺序使用"设计的非原子状态,统一经此锁串行写入;
     * [lock] 为 null(串行路径)时直接执行,零额外开销、行为与现状一致。
     */
    private suspend fun <T> withTurnLock(lock: Mutex?, block: suspend () -> T): T {
        if (lock == null) return block()
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * P2-18: 阻塞型工具专用执行线程池。
     *
     * 工具实现(BrowserAutomationTool / CodeExecutionTool / WorkspaceTool 等)为适配
     * ToolRegistry 的同步 ToolFn 签名,内部用 [kotlinx.coroutines.runBlocking] 桥接
     * suspend API;runBlocking 是阻塞调用,协程取消无法送达,导致外层 withTimeoutOrNull
     * 失效(假超时:超时分支永远走不到,IO 线程被卡死)。
     *
     * 方案:阻塞调用放到专用 daemon 线程池执行,外层用轮询 future 的方式保持协程可取消;
     * 超时后 [java.util.concurrent.Future.cancel] 中断专用线程(runBlocking 事件循环感知
     * 线程中断并取消内部挂起),让超时真正生效,同时不占共享 IO 池线程。
     */
    private val blockingToolExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "muse-tool-blocking").apply { isDaemon = true }
        }

    /**
     * P2-33: 发生过「超时后迟到结果」的工具调用 id 台账(诊断用;同一 id 的后续新执行
     * 不会被它阻断,因为结果闸门按执行代次(每次执行新建)判定)。
     */
    private val abandonedToolCallIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** P2-33: 超时后被丢弃的迟到结果计数(诊断/单测断言)。 */
    private val lateDroppedResultCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** P2-33: 该 toolCallId 是否发生过超时迟到(其结果已被丢弃)。 */
    internal fun isToolCallAbandoned(toolCallId: String): Boolean = toolCallId in abandonedToolCallIds

    /** P2-33: 超时后被丢弃的迟到结果数量。 */
    internal fun droppedLateResultCount(): Int = lateDroppedResultCount.get()

    /**
     * P2-18: 在专用线程执行阻塞型工具调用并施加可中断的超时。
     *
     * P2-33: 结果经 [ToolResultGate] 发布 — 超时先作废闸门再 cancel,忽略中断的调用即使
     * 稍后跑完,其结果也只会被丢弃([lateDroppedResultCount] 计数 + 日志),不会进入
     * TaskCard / 消息 / toolCallRecord;同一 toolCallId 的迟到结果因此无法再影响状态
     * ([abandonedToolCallIds] 留痕)。同一 toolCallId 的后续新执行使用新闸门,不受旧代次影响。
     *
     * @param timeoutMs 超时毫秒
     * @param toolCallId 工具调用 id(迟到丢弃留痕/诊断用)
     * @param block 阻塞型工具执行(内部可含 runBlocking)
     * @return 执行结果;超时返回 null(调用方按既有超时语义处理)
     */
    private suspend fun executeBlockingToolWithHardTimeout(
        timeoutMs: Long,
        toolCallId: String,
        block: () -> String,
    ): String? {
        val gate = ToolResultGate { onLateResultDropped(toolCallId) }
        val future = blockingToolExecutor.submit {
            // 结果先过闸门:返回 false 表示该次执行已超时作废,结果被丢弃
            gate.publish(block())
        }
        return try {
            withTimeout(timeoutMs) {
                // 轮询让协程保持在可取消挂起点;done 后 get() 立即返回,不会二次阻塞
                while (!future.isDone) delay(50)
                try {
                    future.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    // 解包执行异常,让调用方既有的错误处理路径接管
                    throw (e.cause ?: e)
                }
                gate.valueOrNull()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // P2-33: 先作废闸门(切断迟到回写),再中断专用线程尽力终止 runBlocking 及其内部挂起
            gate.abandon()
            future.cancel(true)
            abandonedToolCallIds.add(toolCallId)
            Logger.w(
                TAG,
                "工具超时,已切断迟到回写 | toolCallId=$toolCallId | timeout=${timeoutMs}ms",
            )
            null
        }
    }

    /**
     * P2-33: 超时执行的结果未被发布(迟到)时的诊断入口。
     * 结果本身已被 [ToolResultGate] 丢弃,这里只计数与留痕,不做任何状态写入。
     */
    private fun onLateResultDropped(toolCallId: String) {
        lateDroppedResultCount.incrementAndGet()
        Logger.w(TAG, "丢弃超时工具的迟到结果(已切断回写) | toolCallId=$toolCallId")
    }

    // M3.2 接线:函数体量/复杂度为本文件历史遗留(审批、Hook、媒体落库、任务卡多路径汇聚),
    // 本次仅新增预算放行/落账两小段;整体拆分属独立重构任务(先例:A5 LargeClass 豁免注释)。
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun executeSingleToolCall(
        params: ToolLoopParams,
        taskCardId: String?,
        tc: ToolCall,
        idx: Int,
        host: ToolLoopHost,
        taskCardCoordinator: ChatTaskCardCoordinator,
        execPolicy: ToolExecutionPolicy,
        /** Phase 3: 只读并发轮的 per-turn 状态写锁;串行路径为 null(无开销)。 */
        stateLock: Mutex?,
        /** Phase 3: 只读并发轮的审批提示写锁(审批挂起期间不持有 stateLock)。 */
        approvalLock: Mutex?,
    ): ToolExecResult {
        // M3.2: 统一预算放行 —— 总调用数/连续失败/重复指纹/时间预算命中时短路,
        // 不弹审批、不执行真实工具;拦截原因作为合成结果回给模型与任务卡。
        // Phase 3: execPolicy 按单 turn 顺序使用设计,并发轮经 stateLock 串行放行。
        val policyDecision = withTurnLock(stateLock) { execPolicy.beforeExecute(tc.name, tc.arguments) }
        if (!policyDecision.allowed) {
            Logger.w(
                TAG,
                "工具调用被预算拦截 | reason=${policyDecision.reason} | ${policyDecision.detail} | " +
                    "sessionId=${params.sessionId} | traceId=${params.traceId} | tool=${tc.name}",
            )
            val blockedResult = """{"error": "tool_execution_policy_blocked", "reason": "${policyDecision.reason}"}"""
            withTurnLock(stateLock) {
                taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                    s.copy(
                        status = TaskStepStatus.FAILED,
                        result = "工具 ${tc.name} 未执行(${policyDecision.reason})",
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
            return ToolExecResult(
                idx = idx,
                tc = tc,
                finalToolResult = blockedResult,
                isSuccess = false,
                displayResult = "工具 ${tc.name} 未执行(${policyDecision.reason})",
                status = ToolExecStatus.FAILED,
            )
        }
        // v1.x: 通知宿主工具开始执行(含审批等待时间),用于细粒度进度
        val toolStartAt = System.currentTimeMillis()
        withTurnLock(stateLock) { host.onToolStart(tc.id, tc.name) }

        // v1.0.53: 统一解析参数(Hook 拦截与参数化审批共用)
        val paramsMap = parseToolCallArgs(tc.arguments)

        // P1-1: ToolLifecycleHook.onToolCallRequested — 可拦截工具调用
        if (hookRegistry != null) {
            var blocked: io.zer0.muse.hook.ToolCallAction.Block? = null
            // Phase 3: hook 注册表非并发安全,并发轮经 stateLock 串行调用
            withTurnLock(stateLock) {
                hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                    if (blocked != null) return@executeNoResult
                    when (val action = hook.onToolCallRequested(tc.name, paramsMap)) {
                        is io.zer0.muse.hook.ToolCallAction.Block -> blocked = action
                        is io.zer0.muse.hook.ToolCallAction.Allow -> { /* 继续 */ }
                    }
                }
            }
            if (blocked != null) {
                val blockResult = """{"error": "Tool blocked by hook", "reason": "${blocked.reason}"}"""
                withTurnLock(stateLock) {
                    host.onToolFinish(tc.id, tc.name, false, System.currentTimeMillis() - toolStartAt)
                }
                return ToolExecResult(idx, tc, blockResult, false, status = ToolExecStatus.FAILED)
            }
        }

        // 工具审批检查(v1.0.53: 传完整 args 供参数化权限判定)
        // F-07: 审批等待可见化 — 挂起等待用户响应的过程记录 APPROVAL_PENDING 状态日志
        Logger.d(TAG, "工具 ${tc.name} 等待审批(APPROVAL_PENDING) | sessionId=${params.sessionId}")
        withTurnLock(stateLock) {
            PendingToolCallStore.updateState(tc.id, PendingToolCallStore.APPROVAL_PENDING)
            // Make the wait visible on the task card: otherwise a pending approval looks
            // identical to a running tool until the 30s timeout silently denies it.
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                s.copy(progressText = "等待审批…")
            }
        }
        // Phase 3: 并发轮中审批提示逐个弹出(approvalLock),避免多个审批对话框同时挂起;
        // 等待用户期间不持有 stateLock,其它工具的状态写入不被阻塞。
        val approvalState = withTurnLock(approvalLock) {
            host.requestToolApproval(tc.name, tc.id, tc.arguments.take(200), paramsMap)
        }
        withTurnLock(stateLock) {
            PendingToolCallStore.updateState(tc.id, PendingToolCallStore.EXECUTING)
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                if (s.progressText == "等待审批…") s.copy(progressText = null) else s
            }
        }

        // P1-1: ToolLifecycleHook.onToolPermissionChecked
        if (hookRegistry != null) {
            val approved = approvalState !is ToolApprovalState.Denied
            withTurnLock(stateLock) {
                hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                    hook.onToolPermissionChecked(tc.name, approved)
                }
            }
        }

        if (approvalState is ToolApprovalState.Denied) {
            val deniedResult = """{"error": "Tool denied by user", "reason": "${approvalState.reason}"}"""
            withTurnLock(stateLock) {
                taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                    s.copy(
                        status = TaskStepStatus.FAILED,
                        result = deniedResult,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                PendingToolCallStore.updateState(
                    tc.id,
                    PendingToolCallStore.ABORTED,
                    "user_denied",
                )
            }
            cleanupPendingToolCall(tc.id)
            withTurnLock(stateLock) {
                host.onToolFinish(tc.id, tc.name, false, System.currentTimeMillis() - toolStartAt)
            }
            return ToolExecResult(idx, tc, deniedResult, false, status = ToolExecStatus.FAILED)
        }

        // P2-4: 审计日志 — 用户审批放行工具
        if (approvalState is ToolApprovalState.Approved) {
            withTurnLock(stateLock) {
                auditLogger?.log(
                    category = "user_action",
                    action = "approve_tool",
                    target = tc.id,
                    detail = mapOf("tool" to tc.name),
                )
            }
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
        withTurnLock(stateLock) {
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                s.copy(
                    title = stepTitle,
                    status = TaskStepStatus.RUNNING,
                    startedAt = stepStartedAt,
                    progressText = stepProgress,
                )
            }
        }

        // 审查修复 (2.0 B-14): subagent_task 省略 parent_session_id 时由执行侧补齐 —
        // 异步结果必须归属发起会话,否则以空串 key 存入 DeferredResultStore,
        // 被 consumeUnowned 降级注入"当前活跃会话",可能串台到别的会话。
        // 执行侧(本处)是唯一确定知道发起会话的位置,不依赖 LLM 自觉传参。
        val subagentSessionFix = if (tc.name == "subagent_task") {
            val hasParent = runCatching {
                AppJson.parseToJsonElement(effectiveArguments)
                    .jsonObject["parent_session_id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
            }.getOrDefault(false)
            if (hasParent) effectiveArguments
            else mergeToolArguments(effectiveArguments, mapOf("parent_session_id" to params.sessionId))
        } else {
            effectiveArguments
        }

        // 执行工具:skill 走 SkillExecutor(挂起、可取消),本地工具走 ToolRegistry
        // P2-18: 阻塞型工具(内部 runBlocking 桥接 WebView/文件 IO)无法被协程取消,
        // 必须走专用线程池 + future.cancel(true),否则外层超时形同虚设(假超时)。
        val route = params.routeSnapshot?.routeFor(tc.name)
        val skill = params.skillMap[tc.name]
        val rawToolResultOrNull: String? = if (params.routeSnapshot != null && route == null) {
            "Error: tool '${tc.name}' is not exposed in this turn"
        } else if (route is ToolRouteSnapshot.Route.Skill || (route == null && skill != null)) {
            val skillToExecute = (route as? ToolRouteSnapshot.Route.Skill)?.skill ?: skill!!
            withTimeoutOrNull(toolTimeoutMs) {
                skillExecutor.execute(
                    skill = skillToExecute,
                    argumentsJson = effectiveArguments,
                    onProgress = { msg ->
                        // Phase 3: onProgress 仅 skill 执行分支可达,而 skill 路由的工具不会进入
                        // 只读并发轮(shouldExecuteRoundInParallel 排除 skill),此处无需加锁。
                        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                            s.copy(progressText = msg)
                        }
                    },
                    turnKey = params.turnId.ifBlank { params.traceId.ifBlank { params.sessionId } },
                    sessionId = params.sessionId,
                )
            }
        } else {
            executeBlockingToolWithHardTimeout(toolTimeoutMs, tc.id) {
                // 阻塞型工具在专用线程执行;顶层 suspend 的 executeFromJson 用 runBlocking
                // 桥接为阻塞调用(与工具内部 runBlocking 同线程,中断可传递),超时可被真正中断
                kotlinx.coroutines.runBlocking {
                    // v1.x: 浏览器工具按会话路由 — 每个会话独立 BrowserManager(WebView),
                    // 避免跨会话串扰(会话 A 关闭浏览器不影响会话 B)。
                    if (tc.name in BROWSER_TOOL_NAMES) {
                        val sessionBm = browserManagerRegistry.getForSession(params.sessionId)
                        val argsMap = runCatching {
                            AppJson.decodeFromString(JsonObject.serializer(), subagentSessionFix)
                                .entries.associate { (k, v) -> k to v.toString().trim('"') }
                        }.getOrDefault(emptyMap())
                        BrowserAutomationTool.executeFromArgs(tc.name, argsMap, sessionBm)
                    } else {
                        if (params.toolExecutionContext != null) {
                            if (params.routeSnapshot == null) {
                                toolRegistry.executeFromJson(tc.name, subagentSessionFix, params.toolExecutionContext)
                            } else {
                                routeGuard.executeFromJson(tc.name, subagentSessionFix, params.routeSnapshot, params.toolExecutionContext)
                            }
                        } else {
                            if (params.routeSnapshot == null) {
                                toolRegistry.executeFromJson(tc.name, subagentSessionFix)
                            } else {
                                routeGuard.executeFromJson(tc.name, subagentSessionFix, params.routeSnapshot)
                            }
                        }
                    }
                }
            }
        }
        val timedOut = rawToolResultOrNull == null
        val rawToolResult = rawToolResultOrNull
            ?: "[超时] 工具 ${tc.name} ${toolTimeoutMs / 1000} 秒未响应,已终止"
        // 某些只产生外部副作用的工具可能返回空字符串。无论副作用是否已经成功,
        // 都必须给 UI 和下一轮模型一个明确的终态文本,避免卡片看起来像仍在等待。
        val toolResult = normalizeToolResult(tc.name, rawToolResult)

        val isSuccess = taskCardCoordinator.isToolResultSuccess(toolResult)
        // Phase 3: 工具执行终态在任务卡上与 ToolExecStatus 保持一致 —
        // 超时 → TaskStepStatus.TIMED_OUT(不再被 isSuccess=false 折叠成 FAILED)。
        val execStatus = when {
            timedOut -> ToolExecStatus.TIMED_OUT
            isSuccess -> ToolExecStatus.SUCCESS
            else -> ToolExecStatus.FAILED
        }
        // v1.x: 超长工具输出走"预览 + 写文件 + 引用"模式,完整内容落盘到
        // filesDir/tool_outputs/,LLM 上下文仅保留 4K 预览 + read_file 引用,
        // 既避免撑爆上下文,又让 LLM 能按需读取完整结果。
        // 审计修复 (4.7): 只截断一次 — 原实现 finalToolResult 与 displayResult 各调一次
        // maybeTruncateToolOutput,同一输出写两份文件(文件名含时间戳);现只落盘一份,
        // 展示与给 LLM 的结果共用同一份截断结果与同一文件路径。
        // B-09: 截断在拼引导语之前执行 — 展示用 displayResult 为纯报错文本,
        // "[工具调用失败引导]"只进 LLM 历史,不泄漏到用户可见的任务卡/工具卡片。
        val baseResult = maybeTruncateToolOutput(tc.id, toolResult)
        // v1.0.47 P2-1: 结构化失败引导 — 仅拼进给 LLM 的历史消息,避免无效重试循环
        val llmResult = if (isSuccess) {
            baseResult
        } else {
            "$baseResult\n\n[工具调用失败引导] 请按以下优先级判断:\n" +
                "1. 参数/路径错误 → 修正后重试本工具(最多 1 次)\n" +
                "2. 权限/资源不可用 → 换用其他工具或告知用户限制\n" +
                "3. 网络超时 → 可重试一次,仍失败则告知用户\n" +
                "4. 无法解决 → 直接告知用户失败原因和建议,不要硬撑"
        }
        val finalToolResult = llmResult
        // B-09: 展示用纯报错文本,不含 LLM 引导语
        val displayResult = baseResult

        withTurnLock(stateLock) {
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                s.copy(
                    status = execStatus.toTaskStepStatus(),
                    result = displayResult,
                    finishedAt = System.currentTimeMillis(),
                )
            }
        }

        cleanupPendingToolCall(tc.id)

        // P1-1: ToolLifecycleHook.onToolExecutionResult
        if (hookRegistry != null) {
            val execResult = io.zer0.muse.hook.ToolExecutionResult(
                toolName = tc.name,
                success = isSuccess,
                output = finalToolResult,
                durationMs = System.currentTimeMillis() - toolStartAt,
            )
            withTurnLock(stateLock) {
                hookRegistry.executeNoResult(io.zer0.muse.hook.ToolLifecycleHook::class) { hook ->
                    hook.onToolExecutionResult(execResult)
                }
            }
        }

        // Phase 3: host 回调与执行预算落账(非线程安全)统一经 stateLock 串行写入
        val executionFinishedAt = System.currentTimeMillis()
        val (clampedResult, wasTruncated) = withTurnLock(stateLock) {
            host.onToolFinish(tc.id, tc.name, isSuccess, executionFinishedAt - toolStartAt)
            // M3.2: 执行落账(计数/失败连击/重复指纹)+ 输出大小上限
            // (审批拒绝/预算拦截的路径已提前 return,不计入调用数)
            execPolicy.afterExecute(tc.name, tc.arguments, isSuccess)
            execPolicy.clampOutput(finalToolResult)
        }
        if (wasTruncated) {
            Logger.w(
                TAG,
                "工具结果超限截断 | tool=${tc.name} | 原始长度=${finalToolResult.length} | sessionId=${params.sessionId}",
            )
        }
        return ToolExecResult(
            idx,
            tc,
            clampedResult,
            isSuccess,
            displayResult = displayResult,
            // Timeout must not be reported as a plain success/failure: callers use the
            // status to render the terminal state and to record a terminationReason.
            status = execStatus,
            durationMs = executionFinishedAt - toolStartAt,
        )
    }

    /**
     * 工具完成后清理断点记录,但不能让文件 IO 卡住聊天结果回填。
     *
     * 断点记录是恢复能力的辅助数据;消息结果已经生成后,清理失败应记录日志并让本轮继续结束。
     */
    private suspend fun cleanupPendingToolCall(toolCallId: String) {
        val cleaned = withTimeoutOrNull(PENDING_TOOL_CLEANUP_TIMEOUT_MS) {
            PendingToolCallStore.remove(toolCallId)
            true
        } ?: false
        if (!cleaned) {
            Logger.w(TAG, "清理 pending 工具调用超时: $toolCallId")
        }
    }

    /** 工具没有返回可显示文本时的统一可见回执。 */
    private fun normalizeToolResult(toolName: String, result: String): String =
        if (result.isBlank()) {
            "工具 $toolName 已执行完成,但没有返回可显示的内容。"
        } else {
            result
        }

    /**
     * 工具副作用可能已经完成,但结果回传链路仍发生异常时的终态。
     * 这条路径专门保证 TaskCard、ToolCallCard 和 pending 记录都不再停留在运行中。
     */
    private suspend fun failedToolResult(
        taskCardId: String?,
        tc: ToolCall,
        idx: Int,
        host: ToolLoopHost,
        taskCardCoordinator: ChatTaskCardCoordinator,
        reason: String,
    ): ToolExecResult {
        val result = "工具 ${tc.name} 执行完成后回传失败: $reason"
        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { step ->
            step.copy(
                status = TaskStepStatus.FAILED,
                result = result,
                finishedAt = System.currentTimeMillis(),
            )
        }
        cleanupPendingToolCall(tc.id)
        host.onToolFinish(tc.id, tc.name, false, 0L)
        return ToolExecResult(
            idx = idx,
            tc = tc,
            finalToolResult = result,
            isSuccess = false,
            displayResult = result,
            status = ToolExecStatus.FAILED,
        )
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
        identity: io.zer0.muse.session.GenerationIdentity,
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
                generationId = identity.generationId,
                turnId = identity.turnId,
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
        sessionId: String? = null,
    ): Int {
        val planSteps = countTaskPlanSteps(messages, sessionId)
        val base = if (planSteps > 0) planSteps * 2 + 5 else DEFAULT_MAX_TOOL_ROUNDS
        return minOf(base, MAX_TOOL_ROUNDS_HARD_CAP, hardCap)
    }

    /**
     * 统计 task_plan 步骤数:优先用 SkillExecutor 内存中的活跃计划,
     * 回退到从历史消息中解析 task_plan 工具调用的 steps 参数(断点续传/继续会话场景)。
     */
    internal fun countTaskPlanSteps(messages: List<UIMessage>, sessionId: String? = null): Int {
        val activePlanSteps = (if (sessionId == null) {
            skillExecutor.getActivePlans()
        } else {
            skillExecutor.getActivePlans(sessionId)
        }).values.sumOf { it.steps.size }
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
     * Phase 3 可靠性:落盘在独立 [withTimeoutOrNull] 内执行并受 [toolOutputWriteTimeoutMs] 约束,
     * 写盘超时/失败一律降级为内存截断(仅记日志),绝不让磁盘 IO 无限阻塞工具循环。
     */
    private suspend fun maybeTruncateToolOutput(toolCallId: String, output: String): String {
        if (output.length <= MAX_TOOL_RESULT_CHARS) return output

        val preview = output.take(TOOL_RESULT_PREVIEW_CHARS)
        val fileName = "tool_output_${toolCallId}_${System.currentTimeMillis()}.txt"

        return try {
            val dir = File(context.filesDir, TOOL_OUTPUTS_DIR)
            val file = File(dir, fileName)
            // 独立超时:目录创建 + 写盘;超时返回 null → 下面的内存截断降级
            val written = withTimeoutOrNull(toolOutputWriteTimeoutMs) {
                if (!dir.exists()) dir.mkdirs()
                toolOutputWriter(file, output)
                true
            }
            if (written == null) {
                Logger.w(
                    TAG,
                    "工具输出落盘超时(${toolOutputWriteTimeoutMs}ms),降级为内存截断" +
                        " | toolCallId=$toolCallId | 总长=${output.length}",
                )
                return output.take(MAX_TOOL_RESULT_CHARS) +
                    "\n\n…(结果已截断,完整输出落盘超时已降级为内存截断)"
            }
            Logger.i(
                TAG,
                "工具输出截断: toolCallId=$toolCallId | 总长=${output.length}" +
                    " | 完整输出已落盘: ${file.absolutePath}",
            )
            buildString {
                append("[工具输出已截断: 共 ${output.length} 字符]\n")
                // Emit the absolute path so the UI can render it as an openable attachment
                // chip; the previous "/tool_outputs/..." form matched no extractor pattern.
                append("[完整输出已保存到: ${file.absolutePath}]\n")
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
