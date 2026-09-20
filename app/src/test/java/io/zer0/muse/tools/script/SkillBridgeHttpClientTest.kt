package io.zer0.muse.tools.script

import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Connection-level tests for the isolated SkillBridge/http_get network outlet.
 *
 * The resolver is deliberately injected so these tests prove the address used by the actual
 * MockWebServer socket, rather than only testing a standalone getAllByName policy function.
 */
class SkillBridgeHttpClientTest {

    private lateinit var origin: MockWebServer
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        origin = MockWebServer()
        origin.start(loopback, 0)
    }

    @After
    fun tearDown() {
        origin.shutdown()
    }

    @Test
    fun resolverAddressIsPinnedAndActualSocketUsesThatAddress() {
        val host = "controlled.test"
        val dnsLookups = mutableListOf<String>()
        val validatedConnections = mutableListOf<InetAddress>()
        origin.enqueue(MockResponse().setResponseCode(200).setBody("pinned"))

        val client = SkillBridgeHttpClient(
            baseClient = testBaseClient(),
            dns = Dns { requestedHost ->
                dnsLookups += requestedHost
                if (requestedHost != host) {
                    throw UnknownHostException(requestedHost)
                }
                listOf(loopback)
            },
            addressAllowed = { it.address.contentEquals(loopback.address) },
            onConnectionValidated = { validatedConnections += it },
        )

        val result = client.get(origin.url("/").withHost(host), maxSize = 1024)

        assertEquals("pinned", result.body)
        assertEquals(1, origin.requestCount)
        // The system resolver is never consulted after the policy decision.
        assertEquals(listOf(host), dnsLookups)
        assertTrue(validatedConnections.isNotEmpty())
        assertTrue(validatedConnections.all { it.address.contentEquals(loopback.address) })
    }

    @Test
    fun privateAddressIsRejectedBeforeOpeningSocket() {
        val privateAddress = InetAddress.getByName("10.0.0.1")
        val client = SkillBridgeHttpClient(
            baseClient = testBaseClient(),
            dns = Dns { listOf(privateAddress) },
        )

        val failure = assertThrows(SkillBridgeHttpClient.PinnedAddressException::class.java) {
            client.get("http://private.test:${origin.port}/", maxSize = 1024)
        }

        assertTrue(failure.message.orEmpty().contains("DNS 地址未通过 SSRF 校验"))
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun localhostIsRejectedBeforeDnsLookupOrConnection() {
        var dnsCalled = false
        val client = SkillBridgeHttpClient(
            baseClient = testBaseClient(),
            dns = Dns {
                dnsCalled = true
                listOf(loopback)
            },
        )

        val failure = assertThrows(SkillBridgeHttpClient.PinnedAddressException::class.java) {
            client.get("http://localhost:${origin.port}/", maxSize = 1024)
        }

        assertTrue(failure.message.orEmpty().contains("localhost"))
        assertFalse(dnsCalled)
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun dnsResultContainingRebindingPrivateAddressFailsClosed() {
        val publicAddress = loopback
        val rebindingAddress = InetAddress.getByName("192.168.1.1")
        var dnsCalls = 0
        val client = SkillBridgeHttpClient(
            baseClient = testBaseClient(),
            dns = Dns {
                dnsCalls++
                // A single lookup returning a later private answer is rejected as a whole.
                listOf(publicAddress, rebindingAddress)
            },
            addressAllowed = { it.address.contentEquals(publicAddress.address) },
        )

        val failure = assertThrows(SkillBridgeHttpClient.PinnedAddressException::class.java) {
            client.get("http://rebind.test:${origin.port}/", maxSize = 1024)
        }

        assertTrue(failure.message.orEmpty().contains("DNS 地址未通过 SSRF 校验"))
        assertEquals(1, dnsCalls)
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun laterDnsRebindCannotChangePinnedConnectionAddress() {
        val host = "one-shot-rebind.test"
        var dnsCalls = 0
        val validatedConnections = mutableListOf<InetAddress>()
        origin.enqueue(MockResponse().setResponseCode(200).setBody("still-pinned"))
        val client = SkillBridgeHttpClient(
            baseClient = testBaseClient(),
            dns = Dns { requestedHost ->
                assertEquals(host, requestedHost)
                dnsCalls++
                if (dnsCalls == 1) listOf(loopback) else listOf(InetAddress.getByName("10.0.0.1"))
            },
            // Test policy permits the local MockWebServer only; private rebind is not permitted.
            addressAllowed = { it.address.contentEquals(loopback.address) },
            onConnectionValidated = { validatedConnections += it },
        )

        val result = client.get(origin.url("/").withHost(host), maxSize = 1024)

        assertEquals("still-pinned", result.body)
        assertEquals(1, origin.requestCount)
        assertEquals(1, dnsCalls)
        assertTrue(validatedConnections.all { it.address.contentEquals(loopback.address) })
    }

    @Test
    fun proxyRouteIsRejectedBeforeRequestIsSent() {
        val proxy = MockWebServer()
        try {
            proxy.start(loopback, 0)
            origin.enqueue(MockResponse().setResponseCode(200).setBody("must-not-forward"))
            val host = "proxied.test"
            val client = SkillBridgeHttpClient(
                baseClient = testBaseClient(proxy = proxy.toProxyAddress()),
                dns = Dns { requestedHost ->
                    if (requestedHost == host) listOf(loopback) else throw UnknownHostException(requestedHost)
                },
                addressAllowed = { it.address.contentEquals(loopback.address) },
            )

            val failure = assertThrows(SkillBridgeHttpClient.PinnedAddressException::class.java) {
                client.get(origin.url("/").withHost(host), maxSize = 1024)
            }

            assertTrue(failure.message.orEmpty().contains("代理连接"))
            assertEquals(0, origin.requestCount)
            assertEquals(0, proxy.requestCount)
        } finally {
            proxy.shutdown()
        }
    }

    @Test
    fun redirectToLocalhostIsRejectedAtTheNextHop() {
        val host = "redirect-source.test"
        origin.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://localhost:${origin.port}/secret"),
        )
        val client = SkillBridgeHttpClient(
            baseClient = testBaseClient(),
            dns = Dns { requestedHost ->
                if (requestedHost == host) listOf(loopback) else throw UnknownHostException(requestedHost)
            },
            addressAllowed = { it.address.contentEquals(loopback.address) },
        )

        val failure = assertThrows(SkillBridgeHttpClient.PinnedAddressException::class.java) {
            client.get(origin.url("/").withHost(host), maxSize = 1024)
        }

        assertTrue(failure.url.startsWith("http://localhost:"))
        assertEquals(1, origin.requestCount)
    }

    private fun testBaseClient(proxy: Proxy? = null): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .apply { proxy?.let(::proxy) }
            .build()

    private fun okhttp3.HttpUrl.withHost(host: String): String =
        newBuilder().host(host).build().toString()
}
