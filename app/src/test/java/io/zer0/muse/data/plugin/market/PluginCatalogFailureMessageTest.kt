package io.zer0.muse.data.plugin.market

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录拉取失败的**可行动说明**。
 *
 * 这条曾经是"目录请求失败: HTTP 404"——用户看到只会去查自己的网络，
 * 而真正的原因是服务端没有发布目录文件。错误信息写错，等于把排查成本转给最不知情的人。
 */
class PluginCatalogFailureMessageTest {

    private val client = PluginCatalogClient(transport = { _, _ -> CatalogHttpResponse(404, ByteArray(0)) })

    @Test
    fun `404 says the catalog file is missing rather than blaming the network`() {
        val message = client.describeHttpFailure(404)

        assertTrue("要指向'地址上没有文件': $message", message.contains("找不到目录文件"))
        assertTrue("要给出下一步: $message", message.contains("设置里检查目录地址"))
    }

    @Test
    fun `410 is treated like 404`() {
        assertTrue(client.describeHttpFailure(410).contains("找不到目录文件"))
    }

    @Test
    fun `403 points at authorization instead of a generic failure`() {
        assertTrue(client.describeHttpFailure(403).contains("拒绝访问"))
    }

    @Test
    fun `5xx is described as temporary so users retry later`() {
        assertTrue(client.describeHttpFailure(503).contains("暂时不可用"))
    }

    @Test
    fun `other statuses keep the status code for support`() {
        val message = client.describeHttpFailure(418)

        assertTrue("要保留状态码便于排查: $message", message.contains("418"))
    }

    @Test
    fun `fetch maps a 404 response through the same message`() {
        val result = client.fetch("https://example.com/muse-market/signed-catalog.json")

        assertTrue("fetch 也要用同一套说明: ${result.error}", result.error?.contains("找不到目录文件") == true)
    }
}
