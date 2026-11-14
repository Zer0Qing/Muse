package io.zer0.muse.web

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Locks the four reported queries at the provider boundary and result filter. */
class WebSearchFourQueryRegressionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `四条回归查询保留完整词组并应用 site 域名约束`() = runTest {
        // 两种词序应命中同一结果集；如果某层只取首词，这个断言会把回归钉死。
        server.enqueue(response("python android", "https://example.com/python-android"))
        server.enqueue(response("python android", "https://example.com/python-android"))
        server.enqueue(response("天气 成都", "https://example.com/weather-chengdu"))
        server.enqueue(response("github python", "https://github.com/example/python"))

        val config = WebSearchConfig(
            providerName = "SearXNG",
            endpoint = server.url("/").toString().trimEnd('/'),
        )
        val service = CompositeWebSearchService(client, config)
        val coordinator = WebSearchCoordinator(service)

        val pythonAndroid = coordinator.search(WebSearchRequest("python android"), "four-query-1")
        val androidPython = coordinator.search(WebSearchRequest("android python"), "four-query-2")
        val weatherChengdu = coordinator.search(WebSearchRequest("天气 成都"), "four-query-3")
        val githubPython = coordinator.search(
            WebSearchRequest("site:github.com python"),
            "four-query-4",
        )

        assertEquals(WebSearchStatus.RESULTS, pythonAndroid.status)
        assertEquals(WebSearchStatus.RESULTS, androidPython.status)
        assertEquals(WebSearchStatus.RESULTS, weatherChengdu.status)
        assertEquals(WebSearchStatus.RESULTS, githubPython.status)
        assertFalse(pythonAndroid.results.isEmpty())
        assertFalse(androidPython.results.isEmpty())
        assertEquals(
            pythonAndroid.results.map { it.url }.toSet(),
            androidPython.results.map { it.url }.toSet(),
        )
        assertTrue(githubPython.results.all { URI(it.url).host == "github.com" })

        val actualQueries = (1..4).map {
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            requireNotNull(request) { "Mock SearXNG did not receive request #$it" }
            URLDecoder.decode(
                request.requestUrl!!.queryParameter("q").orEmpty(),
                StandardCharsets.UTF_8.name(),
            )
        }
        assertEquals(
            listOf("python android", "android python", "天气 成都", "site:github.com python"),
            actualQueries,
        )
        assertEquals("site:github.com python", githubPython.debug?.outboundQuery)
        assertEquals(listOf("github.com"), githubPython.debug?.constraints?.domains)
    }

    private fun response(title: String, url: String): MockResponse = MockResponse().setResponseCode(200).setBody(
        """{"results":[{"title":"$title","url":"$url","content":"$title termux kivy chaquopy"}]}""",
    )
}
