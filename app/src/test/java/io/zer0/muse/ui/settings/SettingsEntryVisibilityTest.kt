package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口的展示可见性。
 *
 * 隐藏的是「展示」，不是「声明」：模块与路由都还在包里，搜索仍能命中，
 * 所以这里断言的是展示位，条目总数与分组结构由布局测试另行钉住。
 */
class SettingsEntryVisibilityTest {

    // 2.6.26: 小手机随 R0–R10 重写完成放出入口，不再是「暂不展示」；网页版仍是暂不主推。
    // 小手机那一位是否展示由下面「只认编译期总闸」那条单独钉住（它与 MINI_PHONE_ENTRY 相等）。
    private val hiddenIds = listOf(SettingsEntryId.WEB_ACCESS)

    @Test
    fun `暂不展示的入口标了 browseVisible=false`() {
        val all = SettingsCatalog.entries.associateBy { it.id }
        hiddenIds.forEach { id ->
            val entry = requireNotNull(all[id]) { "$id 应在清单里保留声明" }
            assertFalse("$id 不应在分类页展示", entry.browseVisible)
        }
    }

    @Test
    fun `小手机的展示位只认编译期总闸这一个真源`() {
        // 首页看 FeatureFlags.MINI_PHONE_ENTRY、设置分类页看条目的 browseVisible。
        // 两处各写一份迟早会漂成「首页能进、设置搜不到」，所以设置位必须等于总闸。
        val entry = SettingsCatalog.entries.first { it.id == SettingsEntryId.MINIPHONE }
        assertEquals(io.zer0.muse.feature.FeatureFlags.MINI_PHONE_ENTRY, entry.browseVisible)
    }

    @Test
    fun `暂不展示的入口仍然在清单里并能被搜索索引取到`() {
        val ids = SettingsCatalog.entries.map { it.id }.toSet()
        hiddenIds.forEach { id ->
            assertTrue("$id 从清单里消失后，搜索也就搜不到了", id in ids)
        }
    }

    @Test
    fun `其余入口默认展示`() {
        val hidden = hiddenIds.toSet()
        val wronglyHidden = SettingsCatalog.entries.filter { it.id !in hidden && !it.browseVisible }
        assertEquals(emptyList<SettingsEntryId>(), wronglyHidden.map { it.id })
    }

    @Test
    fun `暂不展示不影响分类结构与条目总数`() {
        // 隐藏的是“展示”，不是“声明”，否则分组数量与布局测试会跟着漂。
        assertEquals(16, SettingsCatalog.groups.size)
        assertEquals(SettingsCatalog.groups.sumOf { it.entries.size }, SettingsCatalog.entries.size)
    }
}
