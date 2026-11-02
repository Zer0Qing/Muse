package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件安装深链（插件计划 P5）。
 *
 * 深链是外部输入：能塞进来什么都不能假设，所以先校验形状、再校验目标，
 * 两层都不过就直接拒绝并给出可读原因。
 */
class PluginInstallLinkTest {

    private fun target(url: String) = PluginInstallLink.parse(url) as PluginInstallLink.Parsed.Target

    private fun invalid(url: String) = PluginInstallLink.parse(url) as PluginInstallLink.Parsed.Invalid

    @Test
    fun `a percent encoded repository url is accepted`() {
        val parsed = target("muse://plugin?url=https%3A%2F%2Fgithub.com%2Fowner%2Frepo")

        assertEquals("https://github.com/owner/repo", parsed.url)
    }

    @Test
    fun `a plain url works too`() {
        assertEquals(
            "https://github.com/owner/repo",
            target("muse://plugin?url=https://github.com/owner/repo").url,
        )
    }

    @Test
    fun `extra query parameters do not break parsing`() {
        assertEquals(
            "https://github.com/owner/repo",
            target("muse://plugin?from=qr&url=https%3A%2F%2Fgithub.com%2Fowner%2Frepo&v=2").url,
        )
    }

    @Test
    fun `a foreign scheme is refused`() {
        assertTrue(invalid("https://plugin?url=https://github.com/a/b").reason.contains("muse://"))
    }

    @Test
    fun `a foreign host is refused`() {
        assertTrue(invalid("muse://session?url=https://github.com/a/b").reason.contains("plugin"))
    }

    @Test
    fun `a missing url parameter is refused`() {
        assertTrue(invalid("muse://plugin").reason.contains("url"))
        assertTrue(invalid("muse://plugin?from=qr").reason.contains("url"))
    }

    @Test
    fun `a target that is not a github repository is refused`() {
        // 复用 SkillSourceUrl 的校验：只认 github.com
        assertTrue(
            invalid("muse://plugin?url=https%3A%2F%2Fexample.com%2Fowner%2Frepo")
                .reason.contains("github.com"),
        )
    }

    @Test
    fun `an empty link is refused instead of crashing`() {
        assertTrue(invalid("").reason.isNotEmpty())
        assertTrue(invalid("   ").reason.isNotEmpty())
    }

    @Test
    fun `a malformed percent encoding does not throw`() {
        // 坏编码原样交给下一层校验，不在这一层抛异常
        val parsed = PluginInstallLink.parse("muse://plugin?url=https%ZZgithub.com/a/b")

        assertTrue("坏编码应被拒绝而不是崩溃: $parsed", parsed is PluginInstallLink.Parsed.Invalid)
    }
}
