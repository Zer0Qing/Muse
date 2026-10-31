package io.zer0.muse.proactive

/**
 * 发送概率闸。
 *
 * 位置在「所有硬闸都通过之后、真正生成之前」：先按概率放弃，再决定花不花这次
 * 模型调用。反过来（先生成再掷骰子）会让被概率放弃的那一轮白烧一次调用。
 *
 * 概率是用户可见的配置，所以语义必须守恒：
 *
 *  - 100 = 通过即发（与没有这道闸完全一致）
 *  - 0   = 永不发送（保留判定日志，便于排查）
 *  - 50  = 一半概率
 *
 * 随机源不在这里：调用方把一次 `0..99` 的掷骰结果传进来，
 * 生产用真随机，测试给固定值。这个类因此没有状态、没有副作用，可以直接断言。
 */
object ProactiveSendProbability {

    /** 掷骰范围上界（不含）。 */
    const val ROLL_BOUND = 100

    /**
     * 这次掷骰是否应当发送。
     *
     * @param sendProbability 用户配置的发送概率，超出 0..100 的脏值按边界处理。
     * @param rollPercent 一次 0..99 的掷骰结果。
     */
    fun allows(sendProbability: Int, rollPercent: Int): Boolean {
        val probability = sendProbability.coerceIn(0, ROLL_BOUND)
        return probability >= ROLL_BOUND || (probability > 0 && rollPercent.coerceIn(0, ROLL_BOUND - 1) < probability)
    }
}
