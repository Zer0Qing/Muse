package io.zer0.muse.data.plugin

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务型插件的准入判定（`runtime.service`）。
 *
 * 这段判定错了不会报错：要么"插件装了但永远不跑"，要么"没授权的也被常驻拉起"。
 * 两者都只能靠测试盯住。
 */
class PluginServiceEligibilityTest {

    private fun plugin(id: String, kind: String, enabled: Boolean = true): PluginManager.InstalledPlugin {
        val p = mockk<PluginManager.InstalledPlugin>(relaxed = true)
        every { p.id } returns id
        every { p.kind } returns kind
        every { p.enabled } returns enabled
        every { p.name } returns id
        return p
    }

    @Test
    fun `only service kind plugins are selected`() {
        val plugins = listOf(
            plugin("svc", "service"),
            plugin("tool", "tool"),
            plugin("prov", "provider"),
            plugin("skin", "ui-skin"),
        )

        val selected = PluginServiceEligibility.select(plugins) { true }

        assertEquals(listOf("svc"), selected.map { it.id })
    }

    @Test
    fun `disabled plugins are not selected`() {
        val selected = PluginServiceEligibility.select(listOf(plugin("svc", "service", enabled = false))) { true }

        assertTrue("禁用插件不该被常驻拉起", selected.isEmpty())
    }

    @Test
    fun `plugins without the grant are not selected`() {
        val selected = PluginServiceEligibility.select(listOf(plugin("svc", "service"))) { false }

        assertTrue("没授权就不该进服务宿主", selected.isEmpty())
    }

    @Test
    fun `the grant is checked per plugin`() {
        val selected = PluginServiceEligibility.select(
            listOf(plugin("a", "service"), plugin("b", "service")),
        ) { id -> id == "b" }

        assertEquals(listOf("b"), selected.map { it.id })
    }

    @Test
    fun `the number of service plugins is capped`() {
        val plugins = (1..PluginServiceEligibility.MAX_SERVICE_PLUGINS + 3).map { plugin("p$it", "service") }

        val selected = PluginServiceEligibility.select(plugins) { true }

        assertEquals(
            "常驻通知要说得清，所以承载数量有上限",
            PluginServiceEligibility.MAX_SERVICE_PLUGINS,
            selected.size,
        )
    }

    @Test
    fun `the kind name matches what authors write in the manifest`() {
        assertEquals("service", PluginServiceEligibility.KIND_SERVICE)
    }
}
