package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.TokenEstimator

/**
 * 上下文占用量的合并口径：provider 实测用量优先，实测覆盖不到的尾部用本地估算补齐。
 *
 * 为什么要合并：每轮请求 provider 都会回 usage，`promptTokens` 是**这一轮模型真实看到的
 * 输入**（system prompt、历史、工具定义、图片都在里面），比本地 BPE 估算准得多。而占用率是
 * 自动压缩触发和发送前预警的输入，长期只用估算值会系统性偏低 —— 偏低直接表现为"撞墙才压"。
 *
 * 合并方式：取最后一条带 `promptTokens` 的 assistant 消息作为"水位线"，它及其之前的部分用
 * 实测值，它之后的消息（新输入的用户消息、本轮工具结果等）仍用本地估算，两者相加。
 *
 * 退化路径：会话里一条带 usage 的消息都没有时，结果与纯本地估算逐字节相同 —— 不新增失败模式，
 * 老会话数据（usage 字段为 NULL）也走这条路。
 */
internal object ContextTokenUsage {

    /**
     * 实测合并值与整体估算的可信比例下限：合并值 × 本数 仍小于估算，判定实测不可信。
     *
     * 取 2（即低于估算一半就弃用）的理由：合法差异不会这么大。实测值明显偏低通常来自两类情况
     * —— 中转/兼容层返回被裁剪或只报输出的 usage；prompt 缓存分段计价后 `promptTokens` 只剩
     * 未命中的那一小段（Anthropic 把 cache_read/cache_creation 单列在 `cachedTokens`，
     * 而该字段按定义不计入 total）。这类数字会让占用率报低，而占用率报低的代价是漏掉压缩窗口、
     * 撞上发送前的硬拦截；报高的代价只是多压一点历史。所以这里以估算为下限。
     */
    private const val MIN_TRUST_RATIO = 2

    /**
     * 水位线下标：最后一条带实测 `promptTokens`(>0) 的 assistant 消息，没有则返回 -1。
     *
     * 只认 assistant —— provider 的 usage 只挂在助手消息的生成元数据上；正在流式生成、还没回填
     * usage 的占位消息会被跳过，其内容留在尾段按估算计。
     */
    fun lastMeasuredIndex(messages: List<UIMessage>): Int {
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (message.role != MessageRole.ASSISTANT) continue
            if ((message.promptTokens ?: 0) > 0) return index
        }
        return -1
    }

    /**
     * 单条消息的实测占用 = prompt + completion，与 [io.zer0.ai.core.UsageTokens.total] 同口径。
     *
     * 不叠加 `cachedTokens`：OpenAI 侧 cached 是 `promptTokens` 的子集，叠加就是重复计数；
     * 字段定义也写明它"仅用于信息展示，不计入 total"。Anthropic 侧因此会偏低，由
     * [blend] 的估算下限兜住。
     */
    fun measuredTokens(message: UIMessage): Int = (message.promptTokens ?: 0) + (message.completionTokens ?: 0)

    /**
     * 把实测与估算合成最终占用量（纯算术，便于单测锁定口径）。
     *
     * @param measured 水位线及之前的真实占用
     * @param tailEstimate 水位线之后消息的本地估算（不含 system prompt，它已在 [measured] 里）
     * @param fullEstimate 整份上下文（system prompt + 全部消息）的本地估算，用作可信度参照与下限
     */
    fun blend(measured: Int, tailEstimate: Int, fullEstimate: Int): Int {
        if (measured <= 0) return fullEstimate
        val merged = measured + tailEstimate
        return if (merged * MIN_TRUST_RATIO < fullEstimate) fullEstimate else merged
    }

    /**
     * 一步算出上下文占用量。
     *
     * CPU 密集（内部走 BPE 编码），调用方需放在 [kotlinx.coroutines.Dispatchers.Default] 上执行。
     * 有实测时多付一次尾段估算，尾段通常只有几条消息，成本远低于对整份历史重算。
     */
    fun estimate(messages: List<UIMessage>, systemPrompt: String = ""): Int {
        val fullEstimate = TokenEstimator.estimate(messages, systemPrompt)
        val measuredIndex = lastMeasuredIndex(messages)
        if (measuredIndex < 0) return fullEstimate
        val measured = measuredTokens(messages[measuredIndex])
        if (measured <= 0) return fullEstimate
        // system prompt 已经包含在实测的 promptTokens 里，尾段再带一遍会重复计数。
        val tailEstimate = TokenEstimator.estimate(messages.subList(measuredIndex + 1, messages.size))
        return blend(measured, tailEstimate, fullEstimate)
    }
}
