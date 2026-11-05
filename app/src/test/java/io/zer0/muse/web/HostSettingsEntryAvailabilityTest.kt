package io.zer0.muse.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host 设置目录里「可点性」的守卫（防止出现点了没反应的死行）。
 *
 * 背景：`browser` 这一条我在 `HostSettingsCatalog` 里标了 `enabled = false`，
 * 因为内置浏览器设置只有 App 侧有页面（WebView 的引擎与 Cookie 行为没法从网页驱动），
 * 而 Web 端的约定是「`enabled = false` 的条目只展示、不进子页」。
 * 这个决定必须有东西钉住：只要有人"顺手把它点亮"却没在客户端补一个页面，
 * 网页上就会多出一行**看着能点、点了什么都不发生**的入口 —— 这类假入口我在本项目里修过很多次，
 * 不该由我亲手再造一个。
 *
 * 同时反向守一条：**别新增无人认领的禁用行**。禁用是有代价的（用户看见却用不了），
 * 所以每多一条都得先进这个清单并写清理由。
 */
class HostSettingsEntryAvailabilityTest {

    private val links = HostSettingsCatalog.groups
        .flatMap { it.entries }
        .filter { it.kind == HostSettingsCatalog.KIND_LINK }

    /**
     * 允许处于禁用态的条目及其理由。
     *
     * 注意这是**许可清单**，不是"当前应该长这样"的快照：`miniphone` 由编译期总闸决定，
     * 开闸时它是可点的（我第一次写这条断言时把它当成恒禁用，测试当场纠正了我）。
     * 判据是"不许出现清单外的禁用行"，所以条目标亮回来不算失败。
     */
    private val allowedDisabled = mapOf(
        // 内置浏览器设置只有 App 侧有页面；Web 端列出但不进子页。
        "browser" to "浏览器设置是 App 侧 WebView 行为，网页端没有对应子页",
        // 小手机由编译期总闸决定：关掉时保留展示但不可进入（既有约定，不是我新加的规则）。
        "miniphone" to "由 FeatureFlags.MINI_PHONE 决定的实验功能，关闸时只展示",
        // 用户约定的编辑与注入都只在 App 侧；Web 端要能进，得先在客户端补子页并登记 entryIds。
        "agent_directives" to "「它该做与不该做的事」只有 App 侧编辑页，网页端列出但不进子页",
    )

    @Test
    fun `browser 条目存在且在补出 Web 子页之前必须保持禁用`() {
        val browser = links.singleOrNull { it.id == "browser" }
        assertTrue("`browser` 条目不见了：设置 → 连接 的 Host 投影需要它，缺了 Web 端就看不到这项功能存在", browser != null)
        assertFalse(
            "`browser` 被点亮了，但 Web 端并没有浏览器设置子页 —— 这会造出一个点了没反应的死行。" +
                "要启用请先在 web/apps/client 里补子页，并把该 id 加进 settingsPages.ts 的 entryIds。",
            browser!!.enabled,
        )
    }

    @Test
    fun `禁用条目都必须在许可清单内并写得出理由`() {
        val unexpected = links.filter { !it.enabled }.map { it.id }.filter { it !in allowedDisabled }
        assertTrue(
            "出现了清单外的禁用入口 $unexpected。禁用行对用户是「看得见用不了」，" +
                "新增要连同理由写进 allowedDisabled。",
            unexpected.isEmpty(),
        )
    }

    @Test
    fun `其余条目必须可点——不能悄悄多出死行`() {
        val dead = links.filter { !it.enabled && it.id !in allowedDisabled }.map { it.id }
        assertTrue("存在无主的禁用条目: $dead", dead.isEmpty())
    }

    @Test
    fun `禁用的条目也要有标题与说明，不能只有一行空白`() {
        // 只展示 = 文案要能自己说话；没有 descKey 的禁用行用户完全不知道它是什么。
        allowedDisabled.forEach { (id, why) ->
            val entry = links.first { it.id == id }
            assertTrue("$id 缺标题资源：$why", entry.titleKey.isNotBlank())
            assertTrue("$id 缺说明资源（禁用行更需要解释为什么在网页里用不了）：$why", entry.descKey != null)
        }
    }
}
