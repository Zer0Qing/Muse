package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryItemActionRoutingTest {

    @Test
    fun sessionSummaryIdRoutesToSummaryStorageInsteadOfFactIdParsing() {
        var factPathCalled = false
        val item = MemoryItem(
            id = "session-550e8400-e29b-41d4-a716-446655440000",
            title = "Chat title",
            content = "Conversation summary",
            source = "Summary",
        )

        val result = routeMemoryItemAction(
            item = item,
            onFact = { _, _ ->
                factPathCalled = true
                "fact"
            },
            onSummary = { sessionId -> sessionId },
            onExperience = { "unused" },
            onCompile = { "unused" },
            onUnsupported = { "unsupported" },
        )

        assertFalse(factPathCalled)
        assertEquals("session-550e8400-e29b-41d4-a716-446655440000", result)
    }

    @Test
    fun numericFactIdKeepsItsFactScope() {
        val item = MemoryItem(
            id = "42",
            title = "A fact",
            content = "A fact",
            source = "Fact",
            scope = "assistant-2",
        )

        val result = routeMemoryItemAction(
            item = item,
            onFact = { id, scope -> "$id:$scope" },
            onSummary = { "summary:$it" },
            onExperience = { "unused" },
            onCompile = { "unused" },
            onUnsupported = { "unsupported" },
        )

        assertEquals("42:assistant-2", result)
    }

    /**
     * v2.6.7: 编译产物（source="Compile"）此前落到 onUnsupported，
     * 导致 editCompile / deleteCompile 零调用 —— 用户编辑确认后静默失败、
     * 删除点下去什公都不发生。现在 id（section key）必须路由到 onCompile。
     */
    @Test
    fun compileSectionRoutesByIdToCompilePath() {
        val item = MemoryItem(
            id = "longterm",
            title = "长期记忆",
            content = "compiled text",
            source = "Compile",
        )

        val result = routeMemoryItemAction(
            item = item,
            onFact = { _, _ -> "fact" },
            onSummary = { "summary" },
            onExperience = { "experience:$it" },
            onCompile = { sectionKey -> "compile:$sectionKey" },
            onUnsupported = { "unsupported" },
        )

        assertEquals("compile:longterm", result)
    }

    /**
     * v2.6.7: 经验库条目此前落到 onUnsupported。
     * addExperience/editExperience/deleteExperience 与 ExperienceLibraryCard、
     * ExperienceEditDialog 都写好了，但界面从未引用，用户看不了也改不了。
     */
    @Test
    fun experienceItemRoutesToExperiencePath() {
        val item = MemoryItem(
            id = "exp_1700000000_42",
            title = "打包要先提交",
            content = "先提交后打包，否则包内容与 HEAD 不一致",
            source = "Experience",
        )

        val result = routeMemoryItemAction(
            item = item,
            onFact = { _, _ -> "fact" },
            onSummary = { "summary" },
            onExperience = { id -> "experience:$id" },
            onCompile = { "compile" },
            onUnsupported = { "unsupported" },
        )

        assertEquals("experience:exp_1700000000_42", result)
    }

    @Test
    fun unknownSourceStillFallsToUnsupported() {
        val item = MemoryItem(id = "x", title = "t", content = "c", source = "Whatever")

        val result = routeMemoryItemAction(
            item = item,
            onFact = { _, _ -> "fact" },
            onSummary = { "summary" },
            onExperience = { "experience" },
            onCompile = { "compile" },
            onUnsupported = { "unsupported" },
        )

        assertEquals("unsupported", result)
    }

    @Test
    fun allScopeMemoryMaintenanceIncludesMainAndChildAssistants() {
        val scopes = listOf(
            ScopeOption(id = null, displayName = "All", isAll = true),
            ScopeOption(id = "main", displayName = "Main", isMain = true),
            ScopeOption(id = "assistant-2", displayName = "Second"),
        )

        assertEquals(listOf("main", "assistant-2"), memoryScopesForMaintenance(null, scopes))
        assertEquals(listOf("assistant-2"), memoryScopesForMaintenance("assistant-2", scopes))
    }
}
