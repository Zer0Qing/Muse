package io.zer0.muse.tools

import io.zer0.ai.core.UsageTokens

/**
 * v1.0.53 Phase 3: 子 agent token 预算账本(既有实现 workflow budgetTokens)。
 *
 * 累加来源:[ChatService.completeText] 返回的 [io.zer0.ai.core.ChatCompletion.usageTokens]。
 *  - usageTokens 为 null 时(Provider 未返回 usage)跳过累加,不误判耗尽
 *  - total = promptTokens + completionTokens(已含 reasoning tokens,不重复加)
 *
 * 使用场景:
 *  - [SubagentRunner] 每轮 completeText 后 accumulate,耗尽则强制结束工具循环进入总结
 *  - [SkillExecutor.delegateAgent] 路径 A(子助手委派)跨递归累加(通过 ThreadLocal 传递 budget 实例)
 *  - [WorkflowOrchestrator] 框架级硬上限检查点
 *
 * 线程安全:本类不做同步 — 设计为单协程内顺序使用。
 * 跨协程共享需调用方自行包裹同步(如 nonBlocking 后台协程各自创建独立 budget)。
 *
 * @param limitTokens 预算上限(prompt + completion 合计)。null 表示不限制(由工厂方法 [of] 处理)。
 */
class AgentTokenBudget private constructor(
    private val limitTokens: Int,
) {
    companion object {
        /** 子 agent 默认 token 预算(60k,对齐文档 6.4 默认值)。 */
        const val DEFAULT_SUBAGENT_BUDGET = 60_000

        /**
         * 创建预算实例。
         * @param limitTokens 上限;null 时返回 null(表示不限制)。
         */
        fun of(limitTokens: Int?): AgentTokenBudget? =
            limitTokens?.takeIf { it > 0 }?.let { AgentTokenBudget(it) }
    }

    private var spent = 0

    /** 剩余预算(永不小于 0)。 */
    val remaining: Int get() = (limitTokens - spent).coerceAtLeast(0)

    /** 预算是否已耗尽。 */
    val isExhausted: Boolean get() = spent >= limitTokens

    /** 已消耗 token 数(诊断用)。 */
    val spentTokens: Int get() = spent

    /**
     * 尝试预扣一笔 token(用于节点启动前的硬上限检查)。
     * @return false 表示预扣后超限,拒绝本次调用;true 表示预扣成功。
     */
    fun trySpend(tokens: Int): Boolean {
        if (tokens <= 0) return true
        if (spent + tokens > limitTokens) return false
        spent += tokens
        return true
    }

    /**
     * 从 [ChatCompletion.usageTokens] 累加实际消耗。
     * - usage 为 null 时(Provider 未返回)跳过累加,仅返回当前是否已耗尽。
     *
     * @return true 表示累加后仍有剩余;false 表示累加后已耗尽(调用方应进入总结路径)。
     */
    fun accumulate(usage: UsageTokens?): Boolean {
        if (usage == null) return !isExhausted
        spent += usage.total
        return !isExhausted
    }
}
