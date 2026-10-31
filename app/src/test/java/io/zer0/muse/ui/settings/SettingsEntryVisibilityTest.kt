package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂不主推的入口：分类页不展示，搜索仍能命中。
 *
 * 这两个功能模块与路由都还在包里，只是这一版不给主入口。
 */
class SettingsEntryVisibilityTest {

    private val hiddenIds = listOf(SettingsEntryId.MINIPHONE, SettingsEntryId.WEB_ACCESS)

    @Test
    fun `暂不展示的入口标了 browseVisible=false`() {
        val all = SettingsCatalog.entries.associateBy { it.id }
        hiddenIds.forEach { id ->
            val entry = requireNotNull(all[id]) { "$id 应在清单里保留声明" }
            assertFalse("$id 不应在分类页展示", entry.browseVisible)
        }
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
        assertEquals(14, SettingsCatalog.groups.size)
        assertEquals(SettingsCatalog.groups.sumOf { it.entries.size }, SettingsCatalog.entries.size)
    }
}
