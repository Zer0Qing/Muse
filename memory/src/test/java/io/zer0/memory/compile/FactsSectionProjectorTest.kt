package io.zer0.memory.compile

import io.zer0.memory.fact.FactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D3-P2: FACTS 段确定性投影器测试。 */
class FactsSectionProjectorTest {

    private fun fact(
        text: String,
        importance: Int = 0,
        pinnedAt: String? = null,
        createdAt: String = "2026-01-01T00:00:00Z",
        lastConfirmedAt: String? = null,
        id: Long = 0,
        supersedesId: Long? = null,
    ) = FactStore.Fact(
        id = id,
        fact = text,
        importance = importance,
        pinnedAt = pinnedAt,
        createdAt = createdAt,
        lastConfirmedAt = lastConfirmedAt,
        supersedesId = supersedesId,
    )

    @Test
    fun `empty input returns blank`() {
        assertEquals("", FactsSectionProjector.project(emptyList()))
    }

    @Test
    fun `renders one fact per line without bullet prefix`() {
        val out = FactsSectionProjector.project(listOf(fact("喜欢摄影"), fact("住在成都")))
        val lines = out.lines()
        assertEquals(2, lines.size)
        assertTrue("不应有 bullet/标题前缀", lines.none { it.startsWith("-") || it.startsWith("#") })
    }

    @Test
    fun `pinned first then importance then recency`() {
        val out = FactsSectionProjector.project(
            listOf(
                fact("普通", importance = 0, createdAt = "2026-03-01T00:00:00Z"),
                fact("重要", importance = 2, createdAt = "2026-01-01T00:00:00Z"),
                fact("置顶", importance = 0, pinnedAt = "2026-02-01T00:00:00Z"),
            ),
        )
        val lines = out.lines()
        assertEquals("置顶", lines[0])
        assertEquals("重要", lines[1])
        assertEquals("普通", lines[2])
    }

    @Test
    fun `respects maxItems`() {
        val facts = (1..10).map { fact("事实$it") }
        val out = FactsSectionProjector.project(facts, FactsSectionProjector.Config(maxItems = 3))
        assertEquals(3, out.lines().size)
    }

    @Test
    fun `respects maxChars and truncates long items`() {
        val facts = (1..5).map { fact("第$it 条" + "很长".repeat(50)) }
        val out = FactsSectionProjector.project(
            facts,
            FactsSectionProjector.Config(maxItems = 10, maxChars = 100, maxItemChars = 30),
        )
        assertTrue("应受 maxChars 限制, 实际 ${out.length}", out.length <= 102)
        assertTrue("超长条目应被截断加省略号", out.contains("…"))
    }

    @Test
    fun `blank facts are skipped`() {
        val out = FactsSectionProjector.project(listOf(fact("  "), fact("有效")))
        assertEquals("有效", out)
    }

    /** v18: 被 supersedes 指向的旧记忆不进 FACTS 段。 */
    @Test
    fun `superseded fact is not projected`() {
        val out = FactsSectionProjector.project(
            listOf(
                fact("张三喜欢喝咖啡", id = 1),
                fact("张三讨厌喝咖啡", id = 2, supersedesId = 1),
            ),
        )
        assertEquals("张三讨厌喝咖啡", out)
    }

    /** v18: 指向方不在投影输入里时(如已被删),旧记忆自己回到上下文。 */
    @Test
    fun `old fact returns when the superseding row is gone`() {
        val out = FactsSectionProjector.project(listOf(fact("张三喜欢喝咖啡", id = 1)))
        assertEquals("张三喜欢喝咖啡", out)
    }
}
