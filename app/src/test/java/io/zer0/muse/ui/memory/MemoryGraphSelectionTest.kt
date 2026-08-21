package io.zer0.muse.ui.memory

import io.zer0.memory.fact.FactStore
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryGraphSelectionTest {
    @Test
    fun ordinaryFactsRemainVisibleBeforeRelationshipRefresh() {
        val facts = listOf(
            FactStore.Fact(id = 1, fact = "普通事实", importance = 0, confidence = 1f),
            FactStore.Fact(id = 2, fact = "重要事实", importance = 2, confidence = 1f),
        )

        val selected = selectGraphFacts(facts, linkedFactIds = emptySet(), maxNodes = 500)

        assertEquals(listOf(2L, 1L), selected.map { it.id })
    }

    @Test
    fun linkedFactsArePrioritizedButUnlinkedFactsAreKept() {
        val facts = (1L..3L).map { id ->
            FactStore.Fact(id = id, fact = "事实$id", importance = 0, confidence = 1f)
        }

        val selected = selectGraphFacts(facts, linkedFactIds = setOf(1L), maxNodes = 2)

        assertEquals(listOf(1L, 3L), selected.map { it.id })
    }
}
