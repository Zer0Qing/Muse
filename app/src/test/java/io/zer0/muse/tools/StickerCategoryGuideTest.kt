package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情包分类推荐的测试。
 *
 * 助手自审第 16 条：「表情包分类名必须严格匹配清单，猜错就失效，而且没有
 * 『这个语境适合什么』的推荐」。
 *
 * 这里钉住两件事：
 *  1. 分类概览带语境对照（从对话情绪直接映射到分类）
 *  2. 分类名猜错时返回候选清单，而不是一句「空」
 *
 * 纯函数测试：不构造 Repository，不碰磁盘。
 */
class StickerCategoryGuideTest {

    private fun guide(cats: List<String>) = SkillMediaToolsImpl.buildCategoryGuide(cats)

    @Test
    fun `known moods are mapped to matching categories`() {
        val out = guide(listOf("开心", "难过", "沙雕"))

        assertTrue(out.contains("开心/庆祝"))
        assertTrue(out.contains("难过/低落"))
        assertTrue(out.contains("搞笑/沙雕"))
    }

    @Test
    fun `unmatched categories are listed as judge-by-name instead of being dropped`() {
        val out = guide(listOf("开心", "猫猫图", "工作梗"))

        assertTrue("认不出的分类要列出来，不能悄悄丢掉", out.contains("其余分类按名称自行判断"))
        assertTrue(out.contains("猫猫图"))
        assertTrue(out.contains("工作梗"))
    }

    @Test
    fun `no matching category yields empty guide rather than a misleading one`() {
        assertEquals("一个都不认识时不要硬塞建议", "", guide(listOf("分类甲", "分类乙")))
    }

    @Test
    fun `english category names are matched case insensitively`() {
        val out = guide(listOf("Happy", "SAD"))

        assertTrue(out.contains("开心/庆祝"))
        assertTrue(out.contains("难过/低落"))
    }

    @Test
    fun `a category matching multiple moods appears under each`() {
        val out = guide(listOf("开心治愈"))

        assertTrue("同时含「开心」和「治愈」，两边都该出现", out.contains("开心/庆祝"))
        assertTrue(out.contains("安慰/治愈"))
    }

    @Test
    fun `empty category list yields empty guide`() {
        assertEquals("", guide(emptyList()))
    }
}
