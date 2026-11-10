package io.zer0.muse.miniphone.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPhoneFeatureCatalogTest {

    @Test
    fun `每个功能的 id 与定位标签都不重复`() {
        val features = MiniPhoneFeatureCatalog.entries
        assertEquals("id 必须唯一", features.size, features.map { it.id }.toSet().size)
        assertEquals("测试标签必须唯一", features.size, features.map { it.tag }.toSet().size)
        assertTrue("注册表不能为空", features.isNotEmpty())
    }

    @Test
    fun `id 可以往返查回同一个功能`() {
        MiniPhoneFeatureCatalog.entries.forEach { feature ->
            assertEquals(feature, MiniPhoneFeatureCatalog.fromId(feature.id))
        }
        assertNull(MiniPhoneFeatureCatalog.fromId("unknown"))
        assertNull(MiniPhoneFeatureCatalog.fromId(""))
    }

    @Test
    fun `全部已接线模块的入口顺序稳定`() {
        assertEquals(
            listOf(
                "moments", "room", "inner_voice_history", "memo", "calls", "checkup",
                "forum", "relation", "roam", "roleplay", "wardrobe", "red_packet",
                "holiday", "ringtone",
            ),

            MiniPhoneFeatureCatalog.entries.map { it.id },
        )
    }

    @Test
    fun `每个功能的文案资源与标签都非空`() {
        MiniPhoneFeatureCatalog.entries.forEach { feature ->
            assertTrue(feature.id, feature.labelRes != 0)
            assertTrue(feature.id, feature.tag.isNotBlank())
        }
    }
}
