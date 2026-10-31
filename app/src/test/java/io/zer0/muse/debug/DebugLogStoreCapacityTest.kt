package io.zer0.muse.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 环形缓冲的容量与淘汰顺序。
 *
 * 上限提到 2000 之后最容易出错的地方是「多删一条」或「从头删」，两种错法在真机上都表现为
 * 「导出的日志少了最近几分钟」，所以这里两头都断言：条数到顶，且被丢掉的正好是最旧那条。
 */
class DebugLogStoreCapacityTest {

    @Before
    @After
    fun resetBuffer() {
        DebugLogStore.clear()
    }

    @Test
    fun `buffer keeps newest 2000 entries and evicts the oldest`() {
        repeat(2001) { index ->
            DebugLogStore.log(DebugLogStore.LEVEL_INFO, "CapacityTest", "entry-$index")
        }

        assertEquals(2000, DebugLogStore.size())

        val all = DebugLogStore.getAll()
        assertEquals(2000, all.size)
        assertEquals("entry-1", all.first().message)
        assertEquals("entry-2000", all.last().message)
        assertNull(all.firstOrNull { it.message == "entry-0" })
    }

    @Test
    fun `entries stay in insertion order after eviction starts`() {
        repeat(2000) { index ->
            DebugLogStore.log(DebugLogStore.LEVEL_INFO, "CapacityTest", "entry-$index")
        }
        // 满额后再写一条，最旧一条被淘汰，其余顺序不变。
        DebugLogStore.log(DebugLogStore.LEVEL_INFO, "CapacityTest", "entry-2000")

        val messages = DebugLogStore.getAll().map { it.message }
        assertEquals(2000, messages.size)
        assertEquals(messages.sortedBy { it.removePrefix("entry-").toInt() }, messages)
    }
}
