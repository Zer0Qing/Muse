package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页版访问地址的组装规则。
 *
 * 页面上「地址」一行和二维码都只消费 [resolveWebAccessAddress] 的结果，
 * 所以这里的每条断言都对应一块用户能看见的文案：要么是完整地址，要么是「没拿到 IP」的提示。
 */
class WebAccessAddressTest {

    @Test
    fun `ip and port become a plain http address`() {
        assertEquals("http://192.168.1.23:8080", buildWebAccessUrl("192.168.1.23", 8080))
    }

    @Test
    fun `https config switches the scheme`() {
        assertEquals("https://192.168.1.23:8765", buildWebAccessUrl("192.168.1.23", 8765, https = true))
    }

    @Test
    fun `missing ip yields null so the caller shows the hint`() {
        assertNull(buildWebAccessUrl(null, 8080))
        assertNull(buildWebAccessUrl("   ", 8080))
    }

    /** 局域网访问关闭：地址固定回环，并标记仅本机，页面据此显示「仅本机可访问」。 */
    @Test
    fun `lan off resolves to loopback and marks local only`() {
        val address = resolveWebAccessAddress(allowLan = false, lanIp = "192.168.1.23", port = 8765)
        assertEquals("http://127.0.0.1:8765", address.url)
        assertTrue(address.localOnly)
    }

    /** 局域网访问开启但没拿到 IP：地址为 null（走提示分支），且不再宣称仅本机。 */
    @Test
    fun `lan on without ip yields no url`() {
        val address = resolveWebAccessAddress(allowLan = true, lanIp = null, port = 8765)
        assertNull(address.url)
        assertEquals(false, address.localOnly)
    }

    @Test
    fun `lan on with ip resolves to the lan address`() {
        val address = resolveWebAccessAddress(allowLan = true, lanIp = "192.168.1.23", port = 8765)
        assertEquals("http://192.168.1.23:8765", address.url)
        assertEquals(false, address.localOnly)
    }

    private fun candidate(
        name: String,
        address: String,
        siteLocal: Boolean = true,
        loopback: Boolean = false,
    ) = LanIpCandidate(interfaceName = name, address = address, siteLocal = siteLocal, loopback = loopback)

    /** 多张网卡时优先无线：即便 wlan 在枚举顺序里靠后。 */
    @Test
    fun `wlan is preferred over later interfaces`() {
        val picked = pickLanIpAddress(
            listOf(
                candidate("docker0", "172.17.0.1"),
                candidate("eth0", "10.0.0.8"),
                candidate("wlan0", "192.168.1.23"),
            ),
        )
        assertEquals("192.168.1.23", picked)
    }

    /** 回环地址与非私有网段（如公网/链路本地）不参与选择。 */
    @Test
    fun `loopback and non site local addresses are skipped`() {
        val picked = pickLanIpAddress(
            listOf(
                candidate("lo", "127.0.0.1", siteLocal = false, loopback = true),
                candidate("rmnet0", "100.64.1.2", siteLocal = false),
                candidate("wlan0", "192.168.1.23"),
            ),
        )
        assertEquals("192.168.1.23", picked)
    }

    /** 全是不可用地址时返回 null，交给页面的「没拿到局域网 IP」提示。 */
    @Test
    fun `no usable candidate yields null`() {
        assertNull(pickLanIpAddress(emptyList()))
        assertNull(pickLanIpAddress(listOf(candidate("lo", "127.0.0.1", siteLocal = false, loopback = true))))
    }
}
