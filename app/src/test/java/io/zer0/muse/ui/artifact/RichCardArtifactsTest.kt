package io.zer0.muse.ui.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富卡片自动入库（用户反馈 33①）。
 *
 * 钉住三件事：渲染侧认定为卡片的围栏块都要被抽出；非卡片语言与空白块不入库；
 * 同一张卡的 id 稳定（自动与手动保存不重复），同语言不同内容的卡各自独立。
 */
class RichCardArtifactsTest {

    @Test
    fun `extracts every renderable card language`() {
        val content = """
            前言
            ```html
            <div>hello</div>
            ```
            ```svg
            <svg xmlns="http://www.w3.org/2000/svg"></svg>
            ```
            ```chart
            {"type":"bar"}
            ```
        """.trimIndent()

        val cards = RichCardArtifacts.extract("s1", "m1", content)

        assertEquals(setOf("html", "svg", "chart"), cards.map { it.language }.toSet())
        assertTrue(cards.all { it.type == "rich_card" })
        assertTrue(cards.all { it.sessionId == "s1" && it.messageId == "m1" })
    }

    @Test
    fun `ignores non card languages and blank blocks`() {
        val content = """
            ```kotlin
            fun main() {}
            ```
            ```html
            ```
        """.trimIndent()

        assertTrue(RichCardArtifacts.extract("s1", "m1", content).isEmpty())
    }

    @Test
    fun `same card yields the same id across calls`() {
        val first = RichCardArtifacts.id("m1", "HTML", "<div>a</div>")
        val second = RichCardArtifacts.id("m1", "html", "<div>a</div>")

        assertEquals(first, second)
    }

    @Test
    fun `two same language cards in one message stay distinct`() {
        val content = """
            ```html
            <div>first</div>
            ```
            ```html
            <div>second</div>
            ```
        """.trimIndent()

        val cards = RichCardArtifacts.extract("s1", "m1", content)

        assertEquals(2, cards.size)
        assertNotEquals(cards[0].id, cards[1].id)
    }
}
