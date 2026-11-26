package io.zer0.muse.rag

import io.zer0.ai.core.RagCitation
import io.zer0.muse.util.TokenEstimator

/**
 * 注入片段的 token 预算选择。
 *
 * 预算必须按**最终注入单元**计量:编号行、来源行、片段与它们之间的空行都会真正发给模型。
 * 只按片段正文估算会让实际注入量系统性超出配置预算,而且块数越多偏差越大。
 *
 * 抽成纯对象是为了让这条规则可被直接驱动验证 —— 否则只能靠整条检索链路 + 真实分词器
 * 间接观察,测不出「少算了来源行」这类偏差。
 */
internal object RagSnippetBudget {

    /** 一个待注入单元:来源行 + 片段 + 对应的引用。 */
    data class SnippetUnit(
        val sourceLine: String,
        val snippet: String,
        val citation: RagCitation,
    )

    /** 选择结果:按原顺序保留的单元与实际用量。 */
    data class Selection(
        val units: List<SnippetUnit>,
        val usedTokens: Int,
    )

    /**
     * 每个单元除正文外的固定开销(换行符与分隔空行)。
     *
     * 分词器按字符/token 计,不会为两三个换行单独给出非零值;这里按 1 token 计,
     * 避免把结构字符当成零成本。
     */
    const val UNIT_OVERHEAD_TOKENS = 1

    /** 单个单元的预算成本 = 来源行 + 片段 + 结构开销。 */
    fun unitTokens(sourceLine: String, snippet: String): Int =
        TokenEstimator.estimate(sourceLine) + TokenEstimator.estimate(snippet) + UNIT_OVERHEAD_TOKENS

    /**
     * 按 [budget] 从前往后取单元,超预算即停。
     *
     * @param budget 总预算;<= 0 表示不限制(全部保留,不计用量)
     * @param headerTokens 外层声明与边界标签的 token 数,同样计入预算
     */
    fun select(units: List<SnippetUnit>, budget: Int, headerTokens: Int = 0): Selection {
        if (budget <= 0) return Selection(units, 0)
        val kept = mutableListOf<SnippetUnit>()
        var used = headerTokens
        for (unit in units) {
            val cost = unitTokens(unit.sourceLine, unit.snippet)
            if (used + cost > budget) break
            used += cost
            kept += unit
        }
        return Selection(kept, used)
    }
}
