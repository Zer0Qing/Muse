package io.zer0.muse.web

import io.zer0.common.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `memory.list` / `memory.search` 的载荷投影测试(分页、截断、片段、来源类别)。 */
class HostMemoryReadsTest {

    private fun record(
        id: Long,
        content: String = "记忆 $id",
        source: String = HostMemorySourceKind.CONVERSATION,
        pinned: Boolean = false,
    ) = HostMemoryRecord(
        id = id.toString(),
        content = content,
        createdAt = "2026-10-31T0$id:00:00Z",
        source = source,
        pinned = pinned,
    )

    /** 1..[count] 号条目,模拟自增 id。 */
    private fun records(count: Int): List<HostMemoryRecord> = (1..count).map { record(it.toLong()) }

    @Test
    fun `list returns thirty entries per page and continues from the cursor`() {
        val all = records(75)

        val first = HostMemoryReads.page(all, cursor = null, limit = HostMemoryReads.DEFAULT_LIMIT)
        assertEquals(30, first.entries.size)
        assertTrue(first.hasMore)
        // 最新入库在前:75 号开头,46 号收尾。
        assertEquals("75", first.entries.first().id)
        val cursor = first.entries.last().id.toLong()
        assertEquals("46", first.entries.last().id)

        val second = HostMemoryReads.page(all, cursor = cursor, limit = HostMemoryReads.DEFAULT_LIMIT)
        assertEquals(30, second.entries.size)
        assertTrue(second.hasMore)
        assertEquals("45", second.entries.first().id)

        val third = HostMemoryReads.page(all, cursor = second.entries.last().id.toLong(), limit = HostMemoryReads.DEFAULT_LIMIT)
        assertEquals(15, third.entries.size)
        assertFalse("最后一页不该再说还有下一页", third.hasMore)
        assertEquals("1", third.entries.last().id)

        // 三页拼起来正好是全集,没有重复也没有跳条。
        val ids = (first.entries + second.entries + third.entries).map { it.id }
        assertEquals(75, ids.size)
        assertEquals(75, ids.distinct().size)
    }

    @Test
    fun `list on an empty database answers with an empty array`() {
        val event = HostMemoryReads.listEvent(
            records = emptyList(),
            cursor = null,
            limit = HostMemoryReads.DEFAULT_LIMIT,
            requestId = "req-empty",
        )

        assertEquals(HostMemoryReads.TYPE_LIST, event.type)
        assertEquals("req-empty", event.requestId)
        assertTrue(event.memory.isEmpty())
        assertEquals(false, event.hasMore)
        assertNull(event.error)
    }

    @Test
    fun `limit above the cap is clamped to one hundred`() {
        val page = HostMemoryReads.page(records(150), cursor = null, limit = 500)

        assertEquals(HostMemoryReads.MAX_LIMIT, page.entries.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `a cursor that is no longer in the list still continues by id`() {
        // 上一页最后一条被删了:锚点找不到,也要接着往更早的 id 取,不能回到第一页。
        val page = HostMemoryReads.page(records(10), cursor = 4, limit = 3)

        assertEquals(listOf("3", "2", "1"), page.entries.map { it.id })
        assertFalse(page.hasMore)
    }

    @Test
    fun `list truncates long content to two hundred characters`() {
        val long = "很长的记忆。".repeat(100)

        val event = HostMemoryReads.listEvent(listOf(record(1, content = long)), null, 30, "req-trunc")

        val content = event.memory.single().content
        assertEquals(HostMemoryReads.CONTENT_LIMIT, content.length)
        assertTrue(long.startsWith(content))
    }

    @Test
    fun `list carries the pinned flag and the source kind`() {
        val event = HostMemoryReads.listEvent(
            records = listOf(
                record(2, source = HostMemorySourceKind.MANUAL, pinned = true),
                record(1, source = HostMemorySourceKind.AUTO),
            ),
            cursor = null,
            limit = 30,
            requestId = "req-flags",
        )

        assertTrue(event.memory.first().pinned)
        assertEquals(HostMemorySourceKind.MANUAL, event.memory.first().source)
        assertFalse(event.memory[1].pinned)
        assertEquals(HostMemorySourceKind.AUTO, event.memory[1].source)
    }

    @Test
    fun `search returns hits with a snippet around the keyword`() {
        val content = "用户喜欢在周末去城西的旧书店。" + "后面还有很多细节。".repeat(20)

        val event = HostMemoryReads.searchEvent(
            query = "旧书店",
            records = listOf(record(7, content = content, pinned = true)),
            requestId = "req-search",
        )

        assertEquals(HostMemoryReads.TYPE_SEARCH, event.type)
        val hit = event.memoryHits.single()
        assertTrue("片段应包含命中词: ${hit.snippet}", hit.snippet.contains("旧书店"))
        assertTrue(hit.snippet.length <= HostMemoryReads.SNIPPET_LIMIT)
        assertEquals("7", hit.entry.id)
        assertTrue(hit.entry.pinned)
        // 条目结构与列表同一套(正文按 200 字截断)。
        assertTrue(hit.entry.content.length <= HostMemoryReads.CONTENT_LIMIT)
    }

    @Test
    fun `search with no hit returns an empty array`() {
        val event = HostMemoryReads.searchEvent(query = "不存在的东西", records = emptyList(), requestId = "req-miss")

        assertEquals(HostMemoryReads.TYPE_SEARCH, event.type)
        assertTrue(event.memoryHits.isEmpty())
        assertNull(event.error)
    }

    @Test
    fun `source kind follows the session and the fact origin`() {
        assertEquals(HostMemorySourceKind.CONVERSATION, HostMemoryReads.sourceKind("session-1", "inferred"))
        assertEquals(HostMemorySourceKind.MANUAL, HostMemoryReads.sourceKind(null, "user_explicit"))
        assertEquals(HostMemorySourceKind.AUTO, HostMemoryReads.sourceKind(null, "inferred"))
        assertEquals(HostMemorySourceKind.AUTO, HostMemoryReads.sourceKind("  ", "imported"))
    }

    @Test
    fun `new payload fields survive the wire round trip`() {
        val event = HostMemoryReads.listEvent(records(3), cursor = null, limit = 2, requestId = "req-wire")

        val decoded = AppJson.decodeFromString<HostEvent>(AppJson.encodeToString(event))

        assertEquals(event, decoded)
        assertEquals(2, decoded.memory.size)
        assertEquals(true, decoded.hasMore)
    }
}
