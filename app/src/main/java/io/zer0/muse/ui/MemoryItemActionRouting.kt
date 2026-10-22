package io.zer0.muse.ui

internal inline fun <T> routeMemoryItemAction(
    item: MemoryItem,
    onFact: (factId: String, scope: String?) -> T,
    onSummary: (sessionId: String) -> T,
    onUnsupported: () -> T,
    /**
     * v2.6.7: 经验库条目（source="Experience"）。
     *
     * MemoryViewModel 的 addExperience / editExperience / deleteExperience 早就实现，
     * ExperienceLibraryCard 与 ExperienceEditDialog 两个组件也写好了，但
     * MemoryScreen 从未引用它们 —— 经验库只能被 AI 写、用户看不了也改不了。
     */
    onExperience: (id: String) -> T,
    /**
     * v2.6.7: 编译产物（source="Compile"）。
     *
     * id 即 section key（facts/today/week/longterm），与 MemoryCompiler.Section.key 一致。
     * 之前这类条目落到 onUnsupported —— 编辑确认后静默保存失败、删除什么都不发生，
     * 而 editCompile / deleteCompile 早就实现好了。
     */
    onCompile: (sectionKey: String) -> T,
): T = when (item.source) {
    "Fact" -> onFact(item.id, item.scope)
    "Summary" -> onSummary(item.id)
    "Compile" -> onCompile(item.id)
    "Experience" -> onExperience(item.id)
    else -> onUnsupported()
}

internal fun memoryScopesForMaintenance(selectedScope: String?, availableScopes: List<ScopeOption>): List<String> {
    if (selectedScope != null) return listOf(selectedScope)
    return availableScopes.asSequence()
        .filterNot { it.isAll }
        .mapNotNull { it.id }
        .distinct()
        .toList()
        .ifEmpty { listOf("main") }
}
