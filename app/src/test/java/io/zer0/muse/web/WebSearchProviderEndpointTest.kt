package io.zer0.muse.web

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebSearchProviderEndpointTest {

    private lateinit var configuredServer: MockWebServer
    private lateinit var legacyServer: MockWebServer
    private lateinit var jinaServer: MockWebServer

    @Before
    fun setUp() {
        configuredServer = MockWebServer()
        legacyServer = MockWebServer()
        jinaServer = MockWebServer()
        configuredServer.start()
        legacyServer.start()
        jinaServer.start()
        configuredServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"results":[{"title":"configured","url":"https://example.com/configured","content":"python android"}]}""",
            ),
        )
        legacyServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"results":[{"title":"legacy","url":"https://example.com/legacy","content":"wrong endpoint"}]}""",
            ),
        )
        jinaServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"results":[{"title":"jina custom","url":"https://example.com/jina","content":"python android"}]}""",
            ),
        )
    }

    @After
    fun tearDown() {
        configuredServer.shutdown()
        legacyServer.shutdown()
        jinaServer.shutdown()
    }

    @Test
    fun `customizable provider entry endpoint overrides legacy global endpoint`() = runTest {
        val config = WebSearchConfig(
            providerName = "SearXNG",
            apiKey = "test-key",
            endpoint = legacyServer.url("/legacy").toString().trimEnd('/'),
            providerEntries = listOf(
                WebSearchProviderEntry(
                    id = "SearXNG",
                    type = "SearXNG",
                    customUrl = configuredServer.url("/configured").toString().trimEnd('/'),
                ),
            ),
        )

        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        val results = CompositeWebSearchService.buildDelegate(client, config).search("python android", 3)

        assertEquals("configured", results.single().title)
        assertNotNull(configuredServer.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(null, legacyServer.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `searxng provider entry endpoint is used before built in fallbacks`() = runTest {
        val customEndpoint = jinaServer.url("/custom").toString()
        val config = WebSearchConfig(
            providerName = "SearXNG",
            providerEntries = listOf(
                WebSearchProviderEntry(
                    id = "SearXNG",
                    type = "SearXNG",
                    customUrl = customEndpoint,
                ),
            ),
        )

        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        val results = CompositeWebSearchService.buildDelegate(client, config).search("python android", 3)
        val request = jinaServer.takeRequest(1, TimeUnit.SECONDS)

        assertEquals("jina custom", results.single().title)
        assertNotNull(request)
        assertEquals(
            "$customEndpoint/search",
            request!!.requestUrl!!.newBuilder().query(null).build().toString(),
        )
    }

    @Test
    fun `legacy chinese custom api selects the custom provider`() = runTest {
        val customEndpoint = configuredServer.url("/custom").toString().trimEnd('/')
        val config = WebSearchConfig(
            providerName = WebSearchConfig.CUSTOM_PROVIDER_NAME,
            apiKey = "legacy-key",
            endpoint = customEndpoint,
        )

        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        val results = CompositeWebSearchService.buildDelegate(client, config).search("python android", 3)

        assertEquals("configured", results.single().title)
        assertNotNull(configuredServer.takeRequest(1, TimeUnit.SECONDS))
    }

    @Test
    fun `standard provider ignores legacy custom endpoint and uses catalog endpoint`() {
        val config = WebSearchConfig(
            providerName = "Tavily",
            endpoint = "https://legacy.example.invalid/search",
            providerEntries = listOf(
                WebSearchProviderEntry(
                    id = "Tavily",
                    type = "Tavily",
                    customUrl = "https://user-entered.example.invalid/search",
                    apiKeys = listOf("test-key"),
                ),
            ),
        )

        assertEquals(
            WebSearchProviderCatalog.defaultEndpoint("Tavily"),
            resolveProviderEndpoint(config, "Tavily"),
        )
    }

    @Test
    fun `custom api can be tested before it becomes the current provider`() = runTest {
        val endpoint = configuredServer.url("/custom").toString().trimEnd('/')
        val config = WebSearchConfig(
            providerName = "Auto",
            providerEntries = listOf(
                WebSearchProviderEntry(
                    id = "Custom API",
                    type = "Custom API",
                    customUrl = endpoint,
                    apiKeys = listOf("custom-key"),
                ),
            ),
        )

        val result = probeSearchProvider(
            client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(),
            cfg = config,
            providerKey = "Custom API",
            apiKeyDraft = "",
        )

        assertEquals(true, result.ok)
        assertEquals(1, result.results)
        val request = configuredServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/custom/search", request!!.requestUrl!!.encodedPath)
    }
}
