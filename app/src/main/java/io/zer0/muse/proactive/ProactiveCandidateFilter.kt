package io.zer0.muse.proactive

import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtStatus

/**
 * 从牵挂账本里挑出这一轮可以拿给模型的候选。
 *
 * 过滤并排序：
 *  - 只保留未超过重提上限与重提冷却期的 OPEN 牵挂（未说过优先）；
 *  - 按主动等级过滤类型；
 *  - 屏蔽历史反馈判定为不感兴趣的类型。
 */
object ProactiveCandidateFilter {

    const val DEFAULT_RESURFACE_COOLDOWN_MS = 24 * 60 * 60_000L

    fun select(
        candidates: List<PendingThoughtEntity>,
        intensity: ProactiveIntensity,
        bannedKinds: Set<ThoughtKind> = emptySet(),
        now: Long = System.currentTimeMillis(),
        reSurfaceCooldownMs: Long = DEFAULT_RESURFACE_COOLDOWN_MS,
    ): List<PendingThoughtEntity> = candidates
        .asSequence()
        .filter { it.status == ThoughtStatus.OPEN.name }
        .filter { it.triggerAt == null || now >= it.triggerAt }
        .filter { it.surfaceCount < ProactiveThoughtRepository.MAX_SURFACE_COUNT }
        .filter { it.lastSurfacedAt == null || (now - it.lastSurfacedAt) >= reSurfaceCooldownMs }
        .filter { intensity.allows(it.kindEnum()) }
        .filterNot { it.kindEnum() in bannedKinds }
        .sortedWith(
            compareBy<PendingThoughtEntity> { it.lastSurfacedAt != null }
                .thenByDescending { it.triggerAt != null && now >= it.triggerAt }
                .thenByDescending { it.weight }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
        .toList()
}
