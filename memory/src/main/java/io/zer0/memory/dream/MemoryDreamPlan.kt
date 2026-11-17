package io.zer0.memory.dream

/**
 * Dream 计划：模型给出的「哪些 section 应该改成什么」。
 *
 * 它只是**候选**。模型输出永远不直接落库 —— 必须先过 [MemoryDreamPlanValidator]，
 * 否则一次跑偏的生成就能把用户长期积累的记忆整段覆盖掉。
 */
data class MemoryDreamPlan(
    val sections: Map<String, String>,
)

/** 计划校验结果。四种结局分别对应四种上层动作。 */
sealed interface DreamPlanResult {
    /** 通过校验，[sections] 只含**实际需要写**的 section（未变化的已剔除）。 */
    data class Planned(val sections: Map<String, String>) : DreamPlanResult

    /** 模型没给出任何可用内容：保持现状，不清空。 */
    data object Empty : DreamPlanResult

    /** 计划不合法（未知 section / 空白正文 / 超长）。 */
    data class Rejected(val reason: String) : DreamPlanResult

    /** 生成过程失败（网络、取消、解析）。[reason] 已脱敏。 */
    data class Failed(val reason: String) : DreamPlanResult
}

/**
 * 计划校验：把「模型说了什么」收敛成「允许写什么」。
 *
 * 三条硬规则，任何一条不满足就整份计划作废（不做部分采纳）：
 *  1. section 必须在允许清单内 —— 未知键意味着模型在编结构，写进去就是垃圾数据；
 *  2. 正文不能空白 —— 空替换等于删掉该段记忆，必须走显式删除语义而不是「空字符串」；
 *  3. 单段长度有上限 —— 防止一次生成把 prompt 预算吃光（该段会进 system prompt）。
 */
object MemoryDreamPlanValidator {

    /** 允许被 Dream 改写的 section 键。与 [io.zer0.memory.compile.MemoryCompiler.Section] 保持一致。 */
    val ALLOWED_SECTIONS = setOf("facts", "today", "week", "longterm")

    /**
     * 单段字符上限。
     *
     * 取 800：longterm 段在编译期会被裁到 830 字符以内（见编译器的 preserve/trim 逻辑），
     * 这里留一点余量，同时挡住「模型把整篇总结塞进一段」的失控输出。
     */
    const val MAX_SECTION_CHARS = 800

    /**
     * 校验并收敛计划。
     *
     * @param plan 模型给出的候选
     * @param current 当前 compiled sections，用于剔除「没变化」的段
     */
    fun validate(plan: MemoryDreamPlan, current: Map<String, String>): DreamPlanResult {
        if (plan.sections.isEmpty()) return DreamPlanResult.Empty
        val accepted = LinkedHashMap<String, String>()
        for ((key, raw) in plan.sections) {
            if (key !in ALLOWED_SECTIONS) {
                return DreamPlanResult.Rejected("unknown section: $key")
            }
            val content = raw.trim()
            if (content.isEmpty()) {
                return DreamPlanResult.Rejected("empty content for section: $key")
            }
            if (content.length > MAX_SECTION_CHARS) {
                return DreamPlanResult.Rejected(
                    "section too long: $key (${content.length} > $MAX_SECTION_CHARS)",
                )
            }
            // 内容与当前一致时不写：一次 Dream 只应该产生真正的差异，
            // 否则每次都会刷新 updatedAt / fingerprint，让「谁改过这段」失去意义。
            if (content == current[key]?.trim()) continue
            accepted[key] = content
        }
        return if (accepted.isEmpty()) DreamPlanResult.Empty else DreamPlanResult.Planned(accepted)
    }
}
