package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Result

/**
 * 生成流程的纯判定函数与阈值常量。
 *
 * 从 [ChatGenerationController] 所在文件切出——这些函数不吃实例状态，
 * 只吃参数吐结果，各自有独立单测；放在 2,000 行的类文件里会被淹没。
 */
internal fun composeSystemPromptMessages(staticPrompt: String, dynamicPrompt: String): List<UIMessage> = listOfNotNull(
    staticPrompt.takeIf { it.isNotBlank() }?.let { UIMessage(role = MessageRole.SYSTEM, content = it) },
    dynamicPrompt.takeIf { it.isNotBlank() }?.let { UIMessage(role = MessageRole.SYSTEM, content = it) },
)

/** Agent mode owns an independent session; task-session state must never win this lookup. */
internal fun effectiveGenerationSessionId(state: ChatUiState): String? =
    if (state.isAgentMode) state.agentSessionId else state.currentSessionId

/** 发送前上下文预警比例（占用达到窗口的这个比例时激进截断历史）。 */
internal const val PRESEND_TOKEN_WARNING_RATIO = 0.9f

/**
 * 发送前自救压缩后保留的尾部消息条数。
 *
 * 比常规压缩（[CompressionPolicy.KEEP_RECENT_DEFAULT]）更短，因为进到这里就是“已经超窗”。
 * 与运行中那条恢复（ChatToolLoopRunner.OVERFLOW_RECOVERY_KEEP_RECENT）取同一个值：
 * 两条路径对“压到什么程度”不能各有一套口径。
 */
internal const val PRESEND_OVERFLOW_KEEP_RECENT = 4

/**
 * 发送 payload 硬上限比例 — 达到即拒绝发送（压缩/截断后仍超限的兜底）。
 *
 * v2.5.7 修复: 原 0.98 过激进 —— 客户端 BPE 估算是近似值（≠ 服务端真实计数），
 * 0.98 很容易把“实际没超”的请求误拦（用户实测“压缩也没用”）。
 * 改为留出估算误差余量：估算 > 真实窗口的 1.35 倍才拦；
 * 区间内交给 provider 真实校验（真超限时会返回 400，由错误映射处理）。
 */
internal const val CONTEXT_HARD_LIMIT_RATIO = 1.35f

/**
 * 静态 system prompt 快照的构建结果。
 *
 * 空串与构建异常**都不可用**：用空提示词继续生成时，模型会丢掉人格、工具说明、
 * 记忆与格式约束，却仍然产出一条“看起来正常”的回复 —— 对用户比直接报错更具误导性。
 */
internal sealed interface StaticPromptOutcome {
    /** 可用快照。 */
    data class Ready(val prompt: String) : StaticPromptOutcome

    /** 构建抛错（带可诊断原因）。 */
    data class Failed(val reason: String) : StaticPromptOutcome

    /** 构建成功但结果为空。 */
    data object Empty : StaticPromptOutcome
}

/** 判定一次静态提示词构建结果是否可用（单一真源，便于单测）。 */
internal fun staticPromptBuildOutcome(result: Result<String>): StaticPromptOutcome = when {
    result is Result.Error -> StaticPromptOutcome.Failed(result.message)
    result.getOrNull().isNullOrBlank() -> StaticPromptOutcome.Empty
    else -> StaticPromptOutcome.Ready(result.getOrNull().orEmpty())
}

/**
 * 发送前是否已达上下文预警线。
 *
 * [windowTokens] 必须是**本轮实际模型**的窗口（见 [effectiveContextWindowTokens]）：
 * 按全局模型算会把小窗口模型的请求放过去（超限 400），也会把大窗口模型的请求
 * 无谓压缩（白丢上下文）。
 */
internal fun isNearContextLimit(currentTokens: Int, windowTokens: Int): Boolean = windowTokens > 0 && currentTokens > 0 &&
    currentTokens.toFloat() / windowTokens >= PRESEND_TOKEN_WARNING_RATIO

/** 发送 payload 是否超过硬拦截线（窗口 × [CONTEXT_HARD_LIMIT_RATIO]）。 */
internal fun exceedsHardContextLimit(payloadTokens: Int, windowTokens: Int): Boolean =
    windowTokens > 0 && payloadTokens >= (windowTokens * CONTEXT_HARD_LIMIT_RATIO).toInt()

/**
 * 发送前溢出自救：先压一轮历史，压完再估一次。
 *
 * 拦戴不移除，只在它前面加一次自救：拦戴是最后的安全网（避免必然 400 的调用），
 * 而很多“超窗”其实压缩一下就能发。
 *
 * 只压一次：本函数只被调用一次、也只调一次 [compress]；压不动（null / 没压短）
 * 直接放弃，不再估第二次，也不重试 —— 反复压只会把真实历史越压越少。
 *
 * @param history 待发送的历史（已按条数 / 预算裁剪过）
 * @param compress 压缩动作；null 表示腾不出空间（消息太少 / 无净收益 / 压缩不可用）
 * @param stillOverflows 拿压缩后的历史重算 payload，是否仍超窗
 * @return 压完确实不再超窗时返回压缩后的历史；null 表示自救失败，调用方保持拦截、报原错误
 */
internal suspend fun recoverPreSendOverflow(
    history: List<UIMessage>,
    compress: suspend () -> List<UIMessage>?,
    stillOverflows: suspend (List<UIMessage>) -> Boolean,
): List<UIMessage>? {
    val compressed = compress() ?: return null
    // 没压短：重估也会是同一个结果，没必要白算一次 BPE
    if (compressed.size >= history.size) return null
    return compressed.takeIf { !stillOverflows(it) }
}

internal fun shouldRollbackOptimisticSend(state: ChatUiState, requestSessionId: String): Boolean =
    effectiveGenerationSessionId(state) == requestSessionId

/**
 * 是否存在可用模型服务:启用,且要么填了密钥、要么该供应商本就允许无密钥(如免费端点)。
 *
 * 判定单独成函数并被单测钉住 —— 发送闸门与将来的可用性提示都读它,
 * 两处各写一套条件迟早漂移成"这里能发、那里不能发"。
 */
internal fun usableProviderPresent(providers: List<io.zer0.ai.core.ProviderConfig>): Boolean =
    providers.any { it.enabled && (it.apiKey.isNotBlank() || it.allowMissingApiKey) }
