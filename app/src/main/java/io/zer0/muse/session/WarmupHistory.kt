// v2.3.1: 本文件为模型侧上下文/简报文本(非 UI 文案),从 ui/chat 迁到 session/,
// 与 check_hardcoded_cjk 的扫描范围(ui/ 的 Compose 文案)保持一致。
package io.zer0.muse.session

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.TokenEstimator

/**
 * v2.x 导入预热:外部导入 / 备份恢复会话的首轮上下文策略。
 *
 * 动机:导入会话通常内含长历史,而默认上下文按"条数"裁剪
 * (Assistant.contextMessageSize / limitContextWithContext,默认 20 条)——长历史几乎全被
 * 砍掉,模型首轮近乎失忆。预热策略把首轮从"条数裁剪"换成"token 预算裁剪":
 *
 * 1. 全量优先 —— 预算内尽可能携带完整历史,超出时从最旧截起(至少保留最新一条);
 * 2. 仅补一次 —— 由 SessionEntity.warmupPending 标记,首轮成功回复后清除
 *    (见 SessionRepository.clearWarmupPending / ChatGenerationController.finalizeResponse);
 * 3. 防"认领" —— 附带系统简报,说明历史来自外部导入、助手回复由其他模型生成,
 *    要求只回应用户最新消息(见 [briefingMessage]);
 * 4. 图片按文本计量 —— 预算估算直接复用 [TokenEstimator] 口径(含每图约 1000 token),
 *    不做视觉特判。
 */
object WarmupHistory {
    /**
     * token 预算占模型上下文窗口的比例。
     *
     * 仅作为「无法得知系统提示与工具定义占用」时的兜底估算。
     * 调用方能给出这两项时，应改走 [compressTokenBudgetFor] 的完整参数。
     */
    const val BUDGET_RATIO = 0.6

    /** contextMaxTokens 未知时的兜底预算(token)。 */
    const val FALLBACK_BUDGET_TOKENS = io.zer0.muse.transformer.ContextBudget.DEFAULT_FALLBACK_BUDGET

    /** 截断结果。[truncated]=true 表示丢弃了最旧的部分历史。 */
    data class TrimResult(val history: List<UIMessage>, val truncated: Boolean)

    /** 依据模型上下文窗口计算历史 token 预算（不扣减已知占用，仅作粗略估算）。 */
    fun budgetTokensFor(contextMaxTokens: Int): Int = if (contextMaxTokens > 0) {
        (contextMaxTokens * BUDGET_RATIO).toInt()
    } else {
        FALLBACK_BUDGET_TOKENS
    }

    /**
     * 触发上下文压缩的 token 预算。
     *
     * 相比按比例拍脑袋，这里把已知占用先扣掉：系统提示、工具定义、输出预留与安全余量。
     * 这三项都不是常数——工具多一个就多几百 token，系统提示随功能增长，
     * 而固定比例对它们完全无感，于是同一条历史在不同会话里可能一个超窗一个不超。
     *
     * @param contextMaxTokens 模型上下文窗口；<=0 时走兜底。
     * @param systemTokens 系统提示占用；给不出就传 0。
     * @param toolTokens 工具定义占用；给不出就传 0。
     * @param maxOutputTokens 模型最大输出；<=0 时按最小输出预留算。
     */
    fun compressTokenBudgetFor(
        contextMaxTokens: Int,
        systemTokens: Int = 0,
        toolTokens: Int = 0,
        maxOutputTokens: Int = 0,
    ): Int = io.zer0.muse.transformer.ContextBudget.compute(
        contextWindow = contextMaxTokens,
        systemTokens = systemTokens,
        toolTokens = toolTokens,
        maxOutputTokens = maxOutputTokens,
        fallbackBudget = FALLBACK_BUDGET_TOKENS,
    ).historyBudget

    /** 兼容旧调用方/key 名;返回值已经是 token,不再是字符预算。 */
    @Deprecated("Use compressTokenBudgetFor; the legacy name is retained for persisted context keys.")
    fun compressCharBudgetFor(contextMaxTokens: Int): Int = compressTokenBudgetFor(contextMaxTokens)

    /**
     * 从最新往旧累计 token,超出预算则截断。
     *
     * 至少保留最后一条(即便其单独超出预算,兜底避免空历史);
     * [estimate] 可注入,便于单测(默认走 [TokenEstimator],包含每图约 1000 token 的计量)。
     */
    fun trimToBudget(
        history: List<UIMessage>,
        budgetTokens: Int,
        estimate: (UIMessage) -> Int = { TokenEstimator.estimate(listOf(it)) },
    ): TrimResult {
        if (history.isEmpty()) return TrimResult(history, false)
        var accumulated = 0
        var cutIndex = history.size
        for (i in history.indices.reversed()) {
            val tokens = estimate(history[i]).coerceAtLeast(1)
            if (accumulated > 0 && accumulated + tokens > budgetTokens) break
            accumulated += tokens
            cutIndex = i
        }
        return if (cutIndex <= 0) {
            TrimResult(history, false)
        } else {
            TrimResult(history.subList(cutIndex, history.size).toList(), true)
        }
    }

    /** 首轮系统简报:防模型把导入历史"认领"为自己的表述。 */
    fun briefingMessage(truncated: Boolean): UIMessage = UIMessage(
        role = MessageRole.SYSTEM,
        content = buildString {
            append("【导入历史说明】本会话的早期消息由外部平台导入,其中助手回复由其他 AI 模型生成。")
            append("请直接回应用户的最新消息,不要总结、复述或重新回答历史中的旧消息,")
            append("也不要把历史中助手的回复当作你自己的表述。")
            if (truncated) append("(历史较长,此处已按上下文预算保留最近部分。)")
        },
    )
}
