package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PluginCatalogClientTest {

    private val fixture = PluginMarketTestFixtures.let { fx ->
        val pair = fx.newKeyPair()
        fx.publicKeyBase64(pair) to fx.sign(fx.payload(), pair)
    }
    private val signedCatalog = fixture.second
    private val catalogJson = PluginMarketTestFixtures.catalogJson(signedCatalog)

    @Test
    fun unconfiguredAndNonHttpsUrlsAreRejectedBeforeAnyRequest() {
        var calls = 0
        val client = PluginCatalogClient(transport = { _, _ ->
            calls++
            CatalogHttpResponse(200, catalogJson.toByteArray())
        })

        val blank = client.fetch("   ")
        val plainHttp = client.fetch("http://example.com/catalog.json")

        assertEquals("插件目录未配置", blank.error)
        assertTrue(plainHttp.error.orEmpty().contains("https://"))
        assertEquals(0, calls)
    }

    @Test
    fun fetchParsesSignedCatalogAndReadsOneBytePastCap() {
        var requestedMax = -1
        val client = PluginCatalogClient(transport = { _, maxBytes ->
            requestedMax = maxBytes
            CatalogHttpResponse(200, catalogJson.toByteArray())
        })

        val result = client.fetch("https://example.com/catalog.json")

        assertTrue(result.success)
        assertEquals("official", result.signedCatalog?.payload?.catalogId)
        assertEquals(PluginCatalogClient.MAX_CATALOG_BYTES + 1, requestedMax)
    }

    @Test
    fun oversizeBodyIsRejectedInsteadOfBeingParsed() {
        val client = PluginCatalogClient(
            maxCatalogBytes = 64,
            transport = { _, maxBytes -> CatalogHttpResponse(200, ByteArray(maxBytes)) },
        )

        val result = client.fetch("https://example.com/catalog.json")

        assertNull(result.signedCatalog)
        assertEquals("目录体积超过限制", result.error)
    }

    @Test
    fun nonSuccessStatusMalformedJsonAndTransportFailureReturnReasons() {
        val notFound = PluginCatalogClient(transport = { _, _ -> CatalogHttpResponse(404, ByteArray(0)) })
            .fetch("https://example.com/catalog.json")
        val malformed = PluginCatalogClient(transport = { _, _ ->
            CatalogHttpResponse(200, "{not-json".toByteArray())
        }).fetch("https://example.com/catalog.json")
        val failing = PluginCatalogClient(transport = { _, _ -> throw IOException("DNS 解析失败") })
            .fetch("https://example.com/catalog.json")

        assertTrue(notFound.error.orEmpty().contains("HTTP 404"))
        assertTrue(malformed.error.orEmpty().contains("JSON"))
        assertTrue(failing.error.orEmpty().contains("DNS 解析失败"))
    }

    @Test
    fun truncatedJsonIsRejected() {
        val truncated = catalogJson.substring(0, catalogJson.length / 2)
        val client = PluginCatalogClient(
            transport = { _, _ -> CatalogHttpResponse(200, truncated.toByteArray()) },
        )

        val result = client.fetch("https://example.com/catalog.json")

        assertNull(result.signedCatalog)
        assertNotNull(result.error)
    }

    @Test
    fun appJsonRoundTripKeepsSignatureVerifiable() {
        val encoded = AppJson.encodeToString(SignedPluginCatalog.serializer(), signedCatalog)
        val decoded = AppJson.decodeFromString(SignedPluginCatalog.serializer(), encoded)
        val pair = fixture.first

        val verification = PluginCatalogVerifier.verify(
            signed = decoded,
            trustedKeys = mapOf("root-1" to pair),
            nowEpochMs = 1_000L,
        )

        assertTrue(verification.valid)
    }
}
