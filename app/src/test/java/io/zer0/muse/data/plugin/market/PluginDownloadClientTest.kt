package io.zer0.muse.data.plugin.market

import io.zer0.muse.tools.script.SkillBridgeHttpClient
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 市场下载客户端定向测试：通过受控出口（MockWebServer + 注入 DNS/地址策略）确认
 * “摘要匹配才落盘、失败不写入 staging，更不写入插件目录”的行为。
 */
class PluginDownloadClientTest {

    private lateinit var origin: MockWebServer
    private lateinit var staging: File
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val host = "market.test"

    @Before
    fun setUp() {
        origin = MockWebServer()
        origin.start(loopback, 0)
        staging = Files.createTempDirectory("plugin-download-staging").toFile()
    }

    @After
    fun tearDown() {
        origin.shutdown()
        staging.deleteRecursively()
    }

    @Test
    fun matchingDigestWritesNamedStagingFile() {
        val body = "muse-plugin-package-bytes"
        origin.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = client().download(entry(sha256(body)))

        assertTrue(result.success)
        val artifact = result.artifact!!
        assertEquals("safe-plugin-1.0.0.muse-plugin", artifact.file.name)
        assertEquals(staging.canonicalPath, artifact.file.parentFile?.canonicalPath)
        assertArrayEquals(body.toByteArray(), artifact.file.readBytes())
        assertEquals(sha256(body), artifact.sha256)
    }

    @Test
    fun digestMismatchWritesNothing() {
        origin.enqueue(MockResponse().setResponseCode(200).setBody("tampered-bytes"))

        val result = client().download(entry(sha256("expected-bytes")))

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("摘要"))
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun httpErrorWritesNothing() {
        origin.enqueue(MockResponse().setResponseCode(404).setBody("missing"))

        val result = client().download(entry(sha256("missing")))

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("404"))
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun transportFailureWritesNothing() {
        val result = client(addressAllowed = { false }).download(entry(sha256("anything")))

        assertFalse(result.success)
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    private fun client(addressAllowed: (InetAddress) -> Boolean = { true }): PluginDownloadClient =
        PluginDownloadClient(
            httpClient = SkillBridgeHttpClient(
                baseClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)
                    .build(),
                dns = Dns { listOf(loopback) },
                addressAllowed = addressAllowed,
            ),
            stagingDir = staging,
        )

    private fun entry(artifactSha256: String): PluginCatalogEntry = PluginCatalogEntry(
        id = "safe-plugin",
        version = "1.0.0",
        name = "Safe Plugin",
        publisherId = "publisher.test",
        publisherKeyFingerprint = "a".repeat(64),
        artifactUrl = origin.url("/safe-plugin-1.0.0.muse-plugin").newBuilder().host(host).build().toString(),
        artifactBytes = 128L,
        artifactSha256 = artifactSha256,
        manifestSha256 = "c".repeat(64),
        capabilities = listOf("resource.read"),
    )

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
}
