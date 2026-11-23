package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotDnsConfigTest {

    @Test
    fun `renderer writes current nameservers and search domains`() {
        val config = ProotDnsConfig.render(
            dnsServers = listOf("192.0.2.53", "2001:db8::53%wlan0", "192.0.2.53"),
            searchDomains = "lan example.test",
        )

        assertTrue(config.contains("nameserver 192.0.2.53\n"))
        assertTrue(config.contains("nameserver 2001:db8::53\n"))
        assertTrue(config.contains("search lan example.test\n"))
        assertEquals(2, Regex("^nameserver ", RegexOption.MULTILINE).findAll(config).count())
    }

    @Test
    fun `renderer falls back to a public resolver when android has none`() {
        val config = ProotDnsConfig.render(emptyList(), null)

        assertTrue(config.startsWith("nameserver 1.1.1.1\n"))
        assertTrue(config.endsWith("options ndots:1 timeout:2 attempts:2\n"))
    }
}
