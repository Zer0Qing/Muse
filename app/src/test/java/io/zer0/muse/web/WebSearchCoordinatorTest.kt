package io.zer0.muse.web

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchCoordinatorTest {
    private class FakeService(
        private val values: MutableList<List<WebSearchResult>>,
    ) : WebSearchService {
        override val name: String = "Fake"
        var calls = 0
        override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
            calls++
            return values.removeAt(0)
        }
    }

    private fun result(title: String) = WebSearchResult(
        title = title,
        url = "https://example.com/$title",
        snippet = "snippet",
        source = "Fake",
    )

    @Test
    fun `duplicate query is rejected in same turn`() = runTest {
        val service = FakeService(mutableListOf(listOf(result("one"))))
        val coordinator = WebSearchCoordinator(service)
        val first = coordinator.search(WebSearchRequest("  DeepSeek V4  "), "turn-1")
        val second = coordinator.search(WebSearchRequest("deepseek v4"), "turn-1")
        assertEquals(WebSearchStatus.RESULTS, first.status)
        assertEquals(WebSearchStatus.DUPLICATE_QUERY, second.status)
        assertEquals(1, service.calls)
    }

    @Test
    fun `budget blocks third search`() = runTest {
        val service = FakeService(mutableListOf(listOf(result("one")), listOf(result("two"))))
        val coordinator = WebSearchCoordinator(service)
        val policy = WebSearchPolicy(maxSearchesPerTurn = 2)
        coordinator.search(WebSearchRequest("one"), "turn-1", policy)
        coordinator.search(WebSearchRequest("two"), "turn-1", policy)
        val third = coordinator.search(WebSearchRequest("three"), "turn-1", policy)
        assertEquals(WebSearchStatus.BUDGET_EXCEEDED, third.status)
        assertEquals(2, service.calls)
    }

    @Test
    fun `empty results trip empty circuit`() = runTest {
        val service = FakeService(mutableListOf(emptyList(), emptyList()))
        val coordinator = WebSearchCoordinator(service)
        val policy = WebSearchPolicy(maxSearchesPerTurn = 4, stopAfterEmpty = 2)
        assertEquals(WebSearchStatus.EMPTY, coordinator.search(WebSearchRequest("one"), "turn-1", policy).status)
        assertEquals(WebSearchStatus.EMPTY, coordinator.search(WebSearchRequest("two"), "turn-1", policy).status)
        val third = coordinator.search(WebSearchRequest("three"), "turn-1", policy)
        assertEquals(WebSearchStatus.EMPTY, third.status)
        assertEquals(2, service.calls)
    }

    @Test
    fun `provider exception becomes failed response`() = runTest {
        val service = object : WebSearchService {
            override val name = "Broken"
            override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
                error("network down")
        }
        val response = WebSearchCoordinator(service).search(WebSearchRequest("query"), "turn-1")
        assertEquals(WebSearchStatus.FAILED, response.status)
        assertTrue(response.attempts.single().message!!.contains("network down"))
    }
}
