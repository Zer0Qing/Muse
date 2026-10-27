package io.zer0.muse.data.proactive

/**
 * 主动消息的反馈学习。
 *
 * 与评分无关：这里只处理「用户回了/划掉了」之后亲密度怎么变，
 * 以及哪一类内容长期不受欢迎。发送判定本身不再依赖它。
 */
object ProactiveLearning {

    fun intimacyDelta(state: OutcomeState): Float = when (state) {
        OutcomeState.REPLIED -> 0.05f
        OutcomeState.OPENED -> 0.01f
        OutcomeState.DISMISSED -> -0.03f
        OutcomeState.EXPIRED -> -0.01f
        OutcomeState.PENDING -> 0f
    }

    /** 应用一次反馈后的新亲密度。 */
    fun applyOutcome(intimacy: Float, state: OutcomeState): Float =
        (intimacy + intimacyDelta(state)).coerceIn(0f, 1f)

    /**
     * 某类型是否已经「不怎么受欢迎」。
     *
     * 判据刻意保守（至少要发过 4 条、且划掉率过半），
     * 避免样本太少就下结论 —— 误判的代价是永久关掉一类有意义的主动消息。
     */
    fun isKindUnpopular(dismissed: Int, total: Int): Boolean =
        total >= 4 && dismissed * 2 > total
}
