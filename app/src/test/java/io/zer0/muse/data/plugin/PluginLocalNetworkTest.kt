package io.zer0.muse.data.plugin

import io.zer0.muse.tools.script.SkillBridgeHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 私网访问授权（`network.local`）。
 *
 * 两件事要同时成立：
 *  1. 没授权时，连 127.0.0.1 会被**判定层**挡下（不是连接失败，是根本不发起）；
 *  2. 授权后，判定层放行 —— 之后连不上是网络问题，而不是被我们拦住。
 *
 * 这条区分很重要：两者都表现为"失败"，但一个是策略、一个是环境，
 * 混在一起会让作者以为自己的能力没生效。
 */
class PluginLocalNetworkTest {

    private val tempDir: File = kotlin.io.path.createTempDirectory("muse-net").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `the default client refuses loopback before connecting`() {
        val client = SkillBridgeHttpClient()

        val error = runCatching { client.getComplete("http://127.0.0.1:1/probe", tempDir, 3) }
            .exceptionOrNull()

        assertTrue("应当被判定层拒绝: $error", error is SkillBridgeHttpClient.PinnedAddressException)
        assertTrue(
            "拒绝信息要带上是哪个地址: ${(error as SkillBridgeHttpClient.PinnedAddressException).url}",
            error.url.contains("127.0.0.1"),
        )
    }

    @Test
    fun `an allowing client gets past the gate and fails at the connection instead`() {
        val client = SkillBridgeHttpClient(addressAllowed = { true })

        val error = runCatching { client.getComplete("http://127.0.0.1:1/probe", tempDir, 3) }
            .exceptionOrNull()

        // 端口 1 上没有服务，所以一定失败；关键是**失败原因不再是"内网地址被拒"**
        assertTrue("应当真的去连了: $error", error is IOException)
        assertFalse(
            "授权后不该再被判定层拦下: $error",
            error is SkillBridgeHttpClient.PinnedAddressException,
        )
    }

    @Test
    fun `the capability is not granted just by declaring it`() {
        val storeFile = File(tempDir, "plugin_permissions.json")
        val gateway = PluginCapabilityGateway(storeFile)

        val record = gateway.seedFromDeclaration("p1", listOf("network", "network.local"))

        assertTrue(
            "普通联网首见即授予",
            record.grants["network"]?.allowed == true,
        )
        assertFalse(
            "私网访问必须用户亲手打开，不能跟着清单自动授予",
            record.grants[PluginCapabilityGateway.NETWORK_LOCAL]?.allowed == true,
        )
    }

    @Test
    fun `the capability name matches what authors write in the manifest`() {
        // 清单里写的就是这个字符串；改名字等于让所有已发布的包静默失效
        assertTrue(PluginCapabilityGateway.NETWORK_LOCAL == "network.local")
    }
}
