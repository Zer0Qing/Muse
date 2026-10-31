package io.zer0.muse.proactive

import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind

/**
 * 从牵挂账本里挑出这一轮可以拿给模型的候选。
 *
 * 两道过滤，原因各不相同：
 *  - 主动等级：安静档只允许「该说的事」，闲聊不在名单里
 *  - 历史反馈：用户对某类消息从来不感冒，就别再拿它去烦人
 *
 * 单拎出来是因为这两道规则都是纯判断，不需要模型也不需要库；
 * 埋在巡检的生成阶段里，就只能靠真跑一轮才能验证。
 */
object ProactiveCandidateFilter {

    /**
     * @param candidates 账本里所有 OPEN 的牵挂
     * @param intensity 用户选的主动等级
     * @param bannedKinds 历史反馈判定为「不感兴趣」的类型
     */
    fun select(
        candidates: List<PendingThoughtEntity>,
        intensity: ProactiveIntensity,
        bannedKinds: Set<ThoughtKind> = emptySet(),
    ): List<PendingThoughtEntity> = candidates
        .sortedByDescending { it.weight }
        .filter { intensity.allows(it.kindEnum()) }
        .filterNot { it.kindEnum() in bannedKinds }
}
