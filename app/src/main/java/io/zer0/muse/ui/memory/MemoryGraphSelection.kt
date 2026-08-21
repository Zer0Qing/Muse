package io.zer0.muse.ui.memory

import io.zer0.memory.fact.FactStore

/**
 * 选择星座节点的纯函数。
 *
 * 关系边、重要度和置顶状态只影响展示优先级，不再决定普通事实是否可见；
 * 否则刚写入但尚未完成关系整理的事实会在记忆星座中消失。
 */
internal fun selectGraphFacts(
    facts: List<FactStore.Fact>,
    linkedFactIds: Set<Long>,
    maxNodes: Int,
): List<FactStore.Fact> = facts
    .sortedWith(
        compareByDescending<FactStore.Fact> { it.id in linkedFactIds }
            .thenByDescending { it.pinnedAt != null }
            .thenByDescending { it.importance }
            .thenByDescending { it.confidence }
            .thenByDescending { it.id },
    )
    .take(maxNodes)
