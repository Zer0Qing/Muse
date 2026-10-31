package io.zer0.memory.compile

import io.zer0.memory.fact.FactStore

/**
 * D3-P2: FACTS 段确定性投影器。
 *
 * 把 facts 表条目按稳定顺序渲染为"每行一条事实"的纯文本段。
 * 输出格式与既有 LLM 产物对齐（CompilePrompts.buildFactsPrompt:
 * "one fact per line, no bullet prefix, no headings"）。
 *
 * 设计原则（D3 方案）：
 *  - 单一权威源：FACTS 段内容 100% 来自 facts 表，不再经 LLM 重新编译；
 *  - 编辑/删除即刻生效：投影即表值；
 *  - 预算克制：条数/字符/单条长度三重上限，超限按重要性截断。
 */
object FactsSectionProjector {

    /** 投影配置。 */
    data class Config(
        /** 最多条数。 */
        val maxItems: Int = 40,
        /** 最大字符数（超出按排序截断）。 */
        val maxChars: Int = 1500,
        /** 单条事实最大保留长度（超长条目截断，防单条挤爆预算）。 */
        val maxItemChars: Int = 120,
    )

    /**
     * 投影渲染。
     *
     * 排序：置顶（pinnedAt 非空）> importance 降序 > lastConfirmedAt/createdAt 降序。
     * 输入应已由调用方过滤墓碑与过期条目（getByScopeAndSpace 已含过期过滤，
     * 墓碑由 MemoryCompiler.filterTombstonedLines 处理）。
     *
     * v18: 被 supersedes 指向的旧记忆不投影 —— 行仍在库里（记忆页可见、可回退），
     * 只是不写进上下文，避免模型同时收到两条互斥断言。指向方被删除后，
     * 本集合自动不再包含它，旧记忆随之回到上下文，无需额外状态。
     *
     * @return 每行一条事实的纯文本；无内容时返回空串
     */
    fun project(facts: List<FactStore.Fact>, config: Config = Config()): String {
        if (facts.isEmpty()) return ""
        val supersededIds = facts.mapNotNullTo(mutableSetOf()) { it.supersedesId }
        val sorted = facts
            .filter { it.fact.isNotBlank() && it.id !in supersededIds }
            .sortedWith(
                compareByDescending<FactStore.Fact> { it.pinnedAt != null }
                    .thenByDescending { it.importance }
                    .thenByDescending { it.lastConfirmedAt ?: it.createdAt },
            )
        val out = StringBuilder()
        var count = 0
        for (f in sorted) {
            if (count >= config.maxItems) break
            val line = f.fact.trim().let {
                if (it.length > config.maxItemChars) it.take(config.maxItemChars) + "…" else it
            }
            if (line.isBlank()) continue
            if (out.isNotEmpty() && out.length + line.length + 1 > config.maxChars) break
            out.append(line).append('\n')
            count++
        }
        return out.toString().trim()
    }
}
