package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `pruneAbsent` —— 会话缓存的**自我纠正**。
 *
 * 会话表才是事实来源。删除会话的路径不止一条（单条删除、「清除全部聊天」、
 * 恢复备份覆盖、外部导入清理…），只靠每条路径都记得调 `remove(id)` 一定会漏；
 * 漏掉的会话消息副本会一直留在 LRU 里，而这缓存存在的理由正是"别持有已删除会话的副本"。
 * 所以订阅会话列表时顺手把"表里已经没有的"丢掉。
 */
class SessionMemoryCachePruneTest {

    private fun cacheWith(vararg ids: String): SessionMemoryCache {
        val cache = SessionMemoryCache(maxSizeProvider = { 10 })
        ids.forEach { cache.put(it, emptyList()) }
        return cache
    }

    @Test
    fun `丢掉表里已不存在的会话副本并返回数量`() {
        val cache = cacheWith("a", "b", "c")

        val dropped = cache.pruneAbsent(listOf("a", "c"))

        assertEquals("只该丢掉 b 一个", 1, dropped)
        assertEquals(2, cache.size())
        assertEquals(true, cache.contains("a"))
        assertEquals(false, cache.contains("b"))
    }

    @Test
    fun `表里都还在时不动任何东西`() {
        val cache = cacheWith("a", "b")

        assertEquals(0, cache.pruneAbsent(listOf("a", "b", "c")))
        assertEquals("不该误删", 2, cache.size())
    }

    @Test
    fun `会话被清空时缓存跟着全清`() {
        // 「清除全部聊天」之后那一次发射：表空了，副本一个都不该留
        val cache = cacheWith("a", "b", "c", "d")

        assertEquals(4, cache.pruneAbsent(emptyList()))
        assertEquals(0, cache.size())
    }

    @Test
    fun `重复调用不会把还活着的会话误清掉`() {
        val cache = cacheWith("a", "b")

        cache.pruneAbsent(listOf("a"))
        cache.pruneAbsent(listOf("a"))

        assertEquals("第二次不该把 a 也清掉", true, cache.contains("a"))
        assertEquals(1, cache.size())
    }
}
