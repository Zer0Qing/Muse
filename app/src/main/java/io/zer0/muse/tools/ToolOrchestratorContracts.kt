package io.zer0.muse.tools

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.ToolRoundEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.ui.chat.ChatErrorType
import io.zer0.muse.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.uuid.Uuid

/**
 * 工具编排的常量、类型与纯判定函数。
 *
 * 从 `ToolOrchestrator.kt` 切出——这一段是 36 个顶层声明：超时阈值、输出预算、
 * 并行度、分类枚举、结果闸门、纯函数（`classifyToolExecution` / `canInlineToolOutput` 等）。
 * 它们不依赖编排器实例状态，各自可独立单测。
 */
/** 工具调用超时阈值(2 分钟),超时则终止,避免阻塞流式输出。 */
internal const val TOOL_TIMEOUT_MS = 120_000L

/**
 * The generic 2-minute deadline is too short for bounded phone agents: each visual decision has
 * its own 60-second timeout, and workflows may intentionally contain several bounded Node steps.
 * Derive a deadline from the declared bounds so the outer runner does not cancel valid work early.
 */
internal object ToolExecutionTimeoutPolicy {
    private const val UI_AGENT_VISION_TIMEOUT_MS = 60_000L
    private const val UI_AGENT_STEP_OVERHEAD_MS = 5_000L
    private const val UI_AGENT_SETUP_OVERHEAD_MS = 10_000L
    private const val WORKFLOW_STEP_OVERHEAD_MS = 10_000L
    private const val WORKFLOW_SETUP_OVERHEAD_MS = 20_000L
    private const val DEFAULT_NODE_SCRIPT_TIMEOUT_MS = 30_000L
    private const val MAX_NODE_SCRIPT_TIMEOUT_MS = 300_000L
    private const val MAX_WORKFLOW_STEPS = 20

    fun forTool(toolName: String, argumentsJson: String, baseTimeoutMs: Long): Long {
        val args = runCatching { AppJson.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
            ?: return baseTimeoutMs
        val derived = when (toolName) {
            "ui_agent" -> {
                val steps = args.intOrNull("max_steps")?.coerceIn(3, 30) ?: 15
                UI_AGENT_SETUP_OVERHEAD_MS + steps * (UI_AGENT_VISION_TIMEOUT_MS + UI_AGENT_STEP_OVERHEAD_MS)
            }
            "automation_workflow" -> workflowTimeout(args)
            else -> null
        }
        return derived?.let { maxOf(baseTimeoutMs, it) } ?: baseTimeoutMs
    }

    private fun workflowTimeout(args: JsonObject): Long? {
        val steps = when (val value = args["steps"]) {
            is kotlinx.serialization.json.JsonArray -> value
            is JsonPrimitive -> runCatching {
                AppJson.parseToJsonElement(value.content) as? kotlinx.serialization.json.JsonArray
            }.getOrNull()
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: return null
        var total = WORKFLOW_SETUP_OVERHEAD_MS
        steps.take(MAX_WORKFLOW_STEPS).forEach { element ->
            val step = element as? JsonObject ?: return@forEach
            val action = step.stringOrNull("action")?.trim()?.lowercase().orEmpty()
            val stepTimeout = when (action) {
                "node_script" -> (step.longOrNull("timeoutMs") ?: DEFAULT_NODE_SCRIPT_TIMEOUT_MS)
                    .coerceIn(1_000L, MAX_NODE_SCRIPT_TIMEOUT_MS) + WORKFLOW_STEP_OVERHEAD_MS
                "wait", "virtual_wait" -> (step.longOrNull("durationMs") ?: 400L)
                    .coerceIn(50L, 10_000L) + WORKFLOW_STEP_OVERHEAD_MS
                else -> WORKFLOW_STEP_OVERHEAD_MS
            }
            total += stepTimeout
        }
        return total
    }

    private fun JsonObject.stringOrNull(name: String): String? = runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.intOrNull(name: String): Int? = stringOrNull(name)?.toIntOrNull()

    private fun JsonObject.longOrNull(name: String): Long? = stringOrNull(name)?.toLongOrNull()
}

/**
 * Without a known model window, this threshold only selects inline versus file-backed delivery.
 * It never caps or drops the tool's complete result.
 */
internal const val TOOL_OUTPUT_INLINE_FALLBACK_CHARS = 12 * 1024
internal const val SMALL_TOOL_RESULT_INLINE_CHARS = 4 * 1024

/** 超出当前轮剩余上下文预算后，随完整文件引用提供的预览字符数。 */
internal const val TOOL_RESULT_PREVIEW_CHARS = 4 * 1024

/** Suggested character page size for reading a saved tool output. */
internal const val TOOL_OUTPUT_READ_PAGE_CHARS = 64 * 1024

/** 工具输出文件保留时长(毫秒),超过后由 [cleanupOldToolOutputs] 清理。 */
internal const val TOOL_OUTPUT_RETENTION_MS = 24L * 60 * 60 * 1000

/** 工具输出文件存储子目录(位于 filesDir 下),供 [ToolOrchestrator] 与 [cleanupOldToolOutputs] 共享。 */
internal const val TOOL_OUTPUTS_DIR = "tool_outputs"

/**
 * Phase 3: 工具输出落盘超时(毫秒)。
 *
 * 落盘是"完整输出可被 read_file 读取"的增强路径,不是工具结果本身;
 * 超时(或写入异常)时保留完整内存原文并记日志,绝不能让磁盘 IO 无限阻塞工具循环。
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
 * 与计划书「默认保守、由用户显式开启」不符,故实际开关改为由
 * [ExperimentsConfig.parallelReadOnlyTools]（**默认 true = 只读工具并发，上限 3**；写工具始终串行）控制。
 * 本次修复:该常量此前硬编码 false,会在 [shouldExecuteRoundInParallel] 里提前短路,
 * 直接把用户的实验开关淹掉 —— 现在它只表示“特性可用”(true),真正的开关交回用户。
 */
internal const val PARALLEL_READ_ONLY_TOOLS_ENABLED_DEFAULT = true

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
internal val READ_ONLY_TOOL_NAMES: Set<String> =
    setOf(
        // 基础计算 / 状态查询
        "get_current_time", "calculator", "echo", "current_status", "get_token_usage",
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
        // v2.6.2: 跨会话只读（翻看其他会话）
        "session_list", "session_read",
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
 * v2.6.2（用户审计）: 把工具参数 JsonObject 转为 hook / 参数化审批用的 Map。
 *
 * 与 ToolRegistry.executeFromJson 的解析路径同源（都先过 parseArgumentsLenient），
 * 并保留 JSON 原始类型：字符串→String，布尔→Boolean，数字→Long/Double，
 * 嵌套对象/数组→JSON 文本。旧实现对 JsonPrimitive 一律取 .content，
 * 把数字/布尔都变成字符串，导致参数策略按类型判定时与执行路径行为不一致。
 */
internal fun toolArgsToMap(obj: JsonObject): Map<String, Any> = obj.mapValues { (_, v) ->
    when (v) {
        is JsonPrimitive -> when {
            v.isString -> v.content
            v.content == "true" -> true
            v.content == "false" -> false
            else -> v.content.toLongOrNull() ?: v.content.toDoubleOrNull() ?: v.content
        }
        else -> v.toString()
    }
}

/**
 * Phase 3: 工具输出默认写盘实现(IO 线程覆盖写)。
 * 生产链路固定使用;单测可注入慢写/抛错替身以确定性验证超时与降级路径。
 */
internal val DEFAULT_TOOL_OUTPUT_WRITER: suspend (File, String) -> Unit = { file, content ->
    withContext(Dispatchers.IO) { file.writeText(content) }
}

/**
 * Tool-chain message count is no longer an application truncation limit.
 *
 * When the provider exposes a context budget, [toolChainTailToKeep] uses the measured
 * token budget to retain a safe tail. If the window is unknown, preserving the complete
 * chain lets the provider or the user's own API decide how much it can accept.
 */
internal const val MAX_TOOL_CHAIN_MESSAGES = Int.MAX_VALUE

/** v1.x: 简单任务(无 task_plan)的默认最大轮次。 */
internal const val DEFAULT_MAX_TOOL_ROUNDS = 10

/** v1.x: 工具调用循环绝对上限(防死循环兜底),即使 task_plan 步骤再多也不超过此值。 */
internal const val MAX_TOOL_ROUNDS_HARD_CAP = 25

/** v2.x: 无限制轮次的哨兵值(用户设置放开时使用;重复调用/卡死/连续失败等预算保护不受影响)。 */
internal const val UNLIMITED_TOOL_ROUNDS = Int.MAX_VALUE

/** 工具结果落回消息前,清理断点记录允许占用的最长时间。 */
internal const val PENDING_TOOL_CLEANUP_TIMEOUT_MS = 2_000L

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
    /**
     * 工具连续失败后的收尾轮：强制不暴露任何工具，让主模型基于已经回填的
     * TOOL 结果生成用户可见的最终答复。
     */
    val forceFinalResponse: Boolean = false,
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
     * @param toolCallsTruncated 本轮 tool_calls 的 arguments 被输出长度上限截断。
     *   残缺参数既不解析也不执行,由编排器为每个调用回填一条明确的错误 tool result,
     *   让模型在同一份上下文里看到原因并重发完整调用。
     */
    data class Success(
        val assistantMessage: UIMessage,
        val hasToolCalls: Boolean,
        val contentLength: Int,
        val firstTokenTime: Long,
        val citationUrls: List<String> = emptyList(),
        val toolCallsTruncated: Boolean = false,
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
 * v2.x: 工具链被"本地重建"后的可见标记。
 *
 * 用户可见（能在对话流里看出这一段被整理过），同时告知模型"更早的工具调用与结果已被丢弃"，
 * 避免它以为那些结果还在、继续引用它们。
 */
internal const val TRUNCATED_TOOL_CHAIN_MARKER: String =
    "(较早的工具调用与结果已整理省略,仅保留最近若干条；如需重新获取请再次调用对应工具)"

/**
 * v2.x: 决定工具链该保留多少条尾部消息（0 表示全部丢弃）。
 *
 * 仅按已知 token 预算判定：
 *  1. **未知窗口**：不按应用自定条数丢弃工具历史；
 *  2. **已知 token 预算**：累计 token 超过 [budgetTokens] 时，按"留一半预算给最新尾部"
 *     估算还能留几条 —— 防"轮次不多但每条结果巨大"（日志、大文件、长网页正文）。
 *
 * 这是零请求的本地重建：不调模型、不依赖网络，最坏情况下也能让本轮请求发得出去。
 *
 * @param chainSize 工具链消息条数（不含初始上下文）
 * @param chainTokens 工具链的 token 估算（[budgetTokens] <= 0 时调用方传 0）
 * @param budgetTokens 本轮可用上下文预算；<= 0 表示宿主无法提供，只做条数判定
 */
internal fun toolChainTailToKeep(chainSize: Int, chainTokens: Int, budgetTokens: Int): Int {
    val byCount = minOf(chainSize, MAX_TOOL_CHAIN_MESSAGES)
    val noBudgetSignal = budgetTokens <= 0 || chainTokens <= 0
    val withinHalfBudget = chainTokens <= budgetTokens / 2
    // 按**实测平均条大小**估算，而不是固定值：单条超大结果（几十万 token 的日志）
    // 用固定 50 token/条去算会得出"还能留上千条"的荒谬结论，窗口照样被撞爆。
    val perMessageTokens = if (chainSize > 0) (chainTokens / chainSize).coerceAtLeast(1) else 1
    val byBudget = (budgetTokens / 2) / perMessageTokens
    return when {
        chainSize <= 0 -> 0
        noBudgetSignal || withinHalfBudget -> byCount
        // 至少留 1 条：全丢会让模型完全看不到"刚才那次调用发生了什么"，比多留一条更糟。
        else -> minOf(byCount, maxOf(1, byBudget))
    }
}

/** Share the current tool-result budget while reserving room for the assistant's answer. */
internal fun toolOutputInlineBudgetTokens(contextBudgetTokens: Int, currentHistoryTokens: Int, toolCallCount: Int): Int? {
    if (contextBudgetTokens <= 0) return null
    val answerReserve = maxOf(1_024, contextBudgetTokens / 8)
    val remaining = (contextBudgetTokens - currentHistoryTokens - answerReserve).coerceAtLeast(0)
    return remaining / toolCallCount.coerceAtLeast(1)
}

internal fun canInlineToolOutput(outputLength: Int, estimatedTokens: Int, inlineBudgetTokens: Int?): Boolean = when {
    outputLength <= SMALL_TOOL_RESULT_INLINE_CHARS -> true
    inlineBudgetTokens == null -> outputLength <= TOOL_OUTPUT_INLINE_FALLBACK_CHARS
    else -> estimatedTokens <= inlineBudgetTokens
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
     *  - 处理 NETWORK/RateLimit 自动重试
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
     * v2.6.7: 当前已就绪的设备通道(无障碍/Shell/Root/使用情况/通知使用权…)。
     *
     * 编排器在真正执行工具前用它做一次预检,把「注定失败的调用」挡在执行之前,
     * 并给出「去设置里开什么」的可执行指引 —— 而不是让工具撞墙后返回一句
     * 模型和用户都读不懂的错误。
     *
     * 默认返回空集表示「宿主未提供通道信息」,此时跳过预检(维持原行为),
     * 避免不实现该方法的宿主(子代理、定时任务等)被误判成所有通道都不可用。
     */
    suspend fun availableDeviceChannels(): Set<ToolRequirements.Channel>? = null

    /**
     * 自动化总闸是否打开。默认 true（跳过判断），由宿主按设置实现。
     *
     * 放在**审批之前**用：闸关时不该先弹一次审批再拒绝 —— 那等于白打扰用户一次。
     */
    suspend fun automationEnabled(): Boolean = true

    /**
     * v1.x: 单个工具执行结束回调。
     *
     * @param success 是否成功(按工具结果判定)
     * @param durationMs 工具执行耗时(毫秒,含审批等待)
     * 默认空实现,宿主可选覆盖。
     */
    fun onToolFinish(toolCallId: String, toolName: String, success: Boolean, durationMs: Long) {}

    /**
     * v2.x: 本轮可用的上下文 token 预算（0 或负数表示宿主无法提供，编排器不做 token 判定）。
     *
     * 为什么需要它：工具链截断此前只看**条数**（[MAX_TOOL_CHAIN_MESSAGES]），
     * 但几条超大工具结果就能在条数限额内把窗口撞爆 —— 而生成中途没有压缩机会。
     * 宿主掌握当前模型的窗口与预留量，这里只问一次、不反向依赖宿主实现。
     *
     * 默认返回 0（不做判定），保证现有实现不需要改动。
     */
    fun contextBudgetTokens(): Int = 0

    /**
     * 取用户在工具链运行期间发出的话（插话）。
     *
     * 编排器在「本轮工具结果全部回填完、下一轮请求发出之前」调用一次，把返回的每条文本
     * 作为一条新的 USER 消息追加在 tool 结果之后。正在执行/刚执行完的工具不受影响：
     * 不回滚、不取消、结果照旧回填，插话只改变下一轮请求看得到的内容。
     *
     * 默认返回空列表 —— 不实现该方法的宿主（子代理、定时任务等）行为完全不变：
     * 没有插话时不产生任何额外消息，history 与改动前逐字一致。
     */
    suspend fun drainSteeringMessages(): List<String> = emptyList()
}

/**
 * 工具调用循环的参数。
 *
 * @param sessionId 当前会话 id
 * @param initialAssistantId 初始占位 assistant 消息 id
 * @param baseHistorySize 不可截断的初始上下文大小(system prefix + 历史)
 * @param maxRounds 最大工具轮次;0=无限制,正整数=固定上限(由调用方从设置解析)
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
    /** When false, tool calls are recorded as paused and never execute real side effects. */
    val allowToolExecution: Boolean = true,
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

    /** 工具连续失败后仍成功生成了基于已有结果的最终答复。 */
    COMPLETED_AFTER_TOOL_FAILURES,
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
     * 是否完成最终答复。
     *
     * Phase 3 起仅当 [terminationReason] == [COMPLETED] 或
     * [COMPLETED_AFTER_TOOL_FAILURES] 时为 true;
     * 轮次上限 / 重复调用 / 预算耗尽等未收尾退出为 false；
     * 连续失败但成功生成收尾答复的回合属于完成态。
     */
    val success: Boolean,
    val error: ToolLoopError? = null,
    /** 工具循环完成时的最终 assistant 消息；纯熔断且没有收尾答复时为 null。 */
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
