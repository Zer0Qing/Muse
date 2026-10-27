package io.zer0.muse.transformer

/**
 * 上下文预算的分配。
 *
 * 一个模型的窗口不是全部给对话历史的。至少四块要先扣掉：
 *
 *  - **系统提示**：persona、技能索引、跨会话衔接这些，随功能增长，动辄上万 token；
 *  - **工具定义**：每次请求都要带完整 schema，工具越多这块越吓人；
 *  - **输出预留**：模型要能写完这条回复，留不够就会被截断；
 *  - **安全余量**：估算器是近似值（cl100k 编码对别的模型只是逼近），
 *    不留余量就会出现「算着没超，实际超了」。
 *
 * 旧实现按窗口的固定比例给历史预算，把这四块当成常数。系统提示和工具定义
 * 恰恰是最不常数的两项 —— 工具数量一多，同一条历史在两个会话里可能一个超窗、
 * 一个不超，而固定比例对此毫无感知。
 */
internal object ContextBudget {

    /** 安全余量下限（token）。 */
    private const val MIN_SAFETY_TOKENS = 1_024

    /** 安全余量占窗口比例。 */
    private const val SAFETY_RATIO = 0.05

    /** 输出预留上限：再大的窗口也不需要一次吐这么多。 */
    private const val MAX_OUTPUT_RESERVE = 16_384

    /** 输出预留下限：低于这个数，回复会被明显截断。 */
    private const val MIN_OUTPUT_RESERVE = 4_096

    /** 历史预算占窗口的最低比例。 */
    private const val MIN_HISTORY_RATIO = 0.2

    /**
     * 预算拆解结果。
     *
     * 保留中间量是为了能解释「为什么这轮压了/没压」——
     * 只给一个最终数字的话，线上觉得不对时无从复核。
     */
    data class Breakdown(
        val contextWindow: Int,
        val systemTokens: Int,
        val toolTokens: Int,
        val outputReserve: Int,
        val safetyTokens: Int,
        val historyBudget: Int,
    )

    /**
     * 按窗口与已知占用算出历史可用预算。
     *
     * @param contextWindow 模型上下文窗口（token）。未知传 0，直接走兜底。
     * @param systemTokens 系统提示占用；未知传 0。
     * @param toolTokens 工具定义占用；未知传 0。
     * @param maxOutputTokens 模型最大输出；未知或 0 时按 [MIN_OUTPUT_RESERVE]。
     * @param fallbackBudget 窗口未知时的兜底预算。
     */
    fun compute(
        contextWindow: Int,
        systemTokens: Int = 0,
        toolTokens: Int = 0,
        maxOutputTokens: Int = 0,
        fallbackBudget: Int = DEFAULT_FALLBACK_BUDGET,
    ): Breakdown {
        if (contextWindow <= 0) {
            return Breakdown(
                contextWindow = 0,
                systemTokens = systemTokens,
                toolTokens = toolTokens,
                outputReserve = 0,
                safetyTokens = 0,
                historyBudget = fallbackBudget,
            )
        }
        val outputReserve = outputReserveFor(maxOutputTokens)
        val safety = maxOf(MIN_SAFETY_TOKENS, (contextWindow * SAFETY_RATIO).toInt())
        val raw = contextWindow - systemTokens - toolTokens - outputReserve - safety
        // 下限保护：工具定义特别多的会话里，硬扣会把历史预算压到 0，
        // 于是每轮都触发压缩、压完还是超。宁可按比例留底，也不要陷入死循环。
        val floor = (contextWindow * MIN_HISTORY_RATIO).toInt()
        return Breakdown(
            contextWindow = contextWindow,
            systemTokens = systemTokens,
            toolTokens = toolTokens,
            outputReserve = outputReserve,
            safetyTokens = safety,
            historyBudget = raw.coerceAtLeast(floor),
        )
    }

    /** 输出预留：跟着模型的输出上限走，但夹在一个合理区间内。 */
    fun outputReserveFor(maxOutputTokens: Int): Int = when {
        maxOutputTokens <= 0 -> MIN_OUTPUT_RESERVE
        maxOutputTokens < MIN_OUTPUT_RESERVE -> MIN_OUTPUT_RESERVE
        maxOutputTokens > MAX_OUTPUT_RESERVE -> MAX_OUTPUT_RESERVE
        else -> maxOutputTokens
    }

    /** 窗口未知时的兜底预算（token）。 */
    const val DEFAULT_FALLBACK_BUDGET = 12_000
}
