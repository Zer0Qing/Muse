package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.23(C1): 渠道对话目标策略的判定回归。
 *
 * 钉住最容易搞错的两处:
 *  - allowlist + 空名单必须「谁都不行」,而不是「全部放行」——选了这个模式却没填人,
 *    意图是收紧,不是放开;
 *  - 未知 mode 按 open 放行:配置损坏不该演变成全面禁言这种最难排查的形态。
 */
class ChannelPolicyTest {

    private fun config(mode: String, targets: List<String> = emptyList()) = ChannelConfig(
        id = "ch1",
        platform = ChannelPlatform.QQ,
        policyMode = mode,
        policyTargets = targets,
    )

    @Test
    fun `open 模式全部放行`() {
        assertTrue(ChannelPolicy.allows(config("open"), "any-chat"))
        assertTrue(ChannelPolicy.allows(config("open", listOf("a")), "a"))
    }

    @Test
    fun `allowlist 只放行名单内`() {
        val cfg = config("allowlist", listOf("group-1", "user-2"))
        assertTrue(ChannelPolicy.allows(cfg, "group-1"))
        assertFalse(ChannelPolicy.allows(cfg, "group-9"))
    }

    @Test
    fun `allowlist 空名单谁都不行`() {
        assertFalse(ChannelPolicy.allows(config("allowlist"), "any"))
    }

    @Test
    fun `denylist 只拦名单内`() {
        val cfg = config("denylist", listOf("spam-group"))
        assertFalse(ChannelPolicy.allows(cfg, "spam-group"))
        assertTrue(ChannelPolicy.allows(cfg, "work-group"))
    }

    @Test
    fun `未知模式按 open 处理`() {
        assertTrue(ChannelPolicy.allows(config("garbage", listOf("a")), "b"))
    }

    @Test
    fun `名单录入规范化`() {
        val targets = ChannelPolicy.normalizeTargets(" a ,\nb,,  \na\n")
        assertEquals(listOf("a", "b"), targets)
    }

    @Test
    fun `拦截理由包含模式但不泄露完整名单`() {
        val reason = ChannelPolicy.blockReason(config("allowlist", listOf("secret-id")), "other-id")
        assertTrue(reason.contains("allowlist"))
        assertTrue(reason.contains("other"))
    }
}
