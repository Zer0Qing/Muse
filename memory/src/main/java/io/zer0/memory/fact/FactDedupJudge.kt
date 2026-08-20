package io.zer0.memory.fact

/**
 * v12: 事实去重 LLM 判定器 — 算法层(字符相似度/实体键)无法确定的模糊候选对,
 * 交由大模型判断是否为同一事实。算法层再完善也会有漏网之鱼,
 * 语义等价但表述差异大的两条事实(如"用户养了一只柯基" vs "他养了只柯基犬")
 * 只有模型能可靠识别。
 *
 * 设计:
 *  - 接口定义在 memory 模块,实现(LLM 调用)在 app 模块注入,memory 模块保持独立可测。
 *  - FactStore 仅在"同实体键有候选但文本判定失败"的低频场景调用,控制 token 消耗。
 *  - 判定结果带置信度,低置信时宁可不合并(宁漏不错)。
 *  - 实现方必须超时/异常降级为"不同一",不阻塞记忆写入主流程。
 */
interface FactDedupJudge {

    /**
     * 判定两条事实是否为同一事实。
     *
     * @param a 已有事实文本
     * @param b 新事实文本
     * @param entityKeyA 已有事实的实体键(可空)
     * @param entityKeyB 新事实的实体键(可空)
     * @return 判定结果;LLM 不可用/超时/解析失败时返回 same=false(宁可不合并)
     */
    suspend fun judge(
        a: String,
        b: String,
        entityKeyA: String?,
        entityKeyB: String?,
    ): DedupVerdict
}

/**
 * 判定结果。
 *
 * @param same true=同一事实(应合并),false=不同一(不合并)
 * @param confidence 置信度 0.0~1.0;same=true 且 confidence < 0.7 时调用方应保守处理
 * @param reason 简短理由(用于日志与后续审计)
 */
data class DedupVerdict(
    val same: Boolean,
    val confidence: Float = 0f,
    val reason: String = "",
) {
    /** 高置信同一 — 可安全自动合并。 */
    val highConfidenceSame: Boolean get() = same && confidence >= 0.7f

    companion object {
        /** LLM 不可用/失败时的保守降级结果。 */
        val NOT_SAME = DedupVerdict(same = false, confidence = 0f, reason = "judge unavailable")
    }
}

/** 无 LLM 时的默认实现(行为与未接入 judge 完全一致)。 */
object NoopFactDedupJudge : FactDedupJudge {
    override suspend fun judge(
        a: String,
        b: String,
        entityKeyA: String?,
        entityKeyB: String?,
    ): DedupVerdict = DedupVerdict.NOT_SAME
}
