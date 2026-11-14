package io.zer0.memory.pin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 置顶记忆去重。
 *
 * 分两级：精确去重（纯字符串，无需模型）与语义候选对筛选（交给上层调模型）。
 * 这里只验证精确去重与候选对筛选的边界，不验证 LLM 判定本身。
 */
class PinnedMemoryDedupTest {

    private fun <T> withStore(block: suspend (PinnedMemoryStore) -> T): T = runBlocking {
        val directory = Files.createTempDirectory("pinned-dedup-test").toFile()
        try {
            block(PinnedMemoryStore(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `exact dedup merges identical content and keeps the earliest`() = withStore { store ->
        // add() 本身会对相同内容去重，所以重复必须用 replace 制造：
        // 先把第二条改成与第一条相同，再交给 dedupExact 处理。
        val first = store.add("用户喜欢猫")
        val second = store.add("用户喜欢狗")
        store.replace(second, "用户喜欢猫")

        assertEquals("构造后应有 2 条内容相同的记录", 2, store.getAll().size)

        val removed = store.dedupExact()
        val remaining = store.getAll()

        assertEquals("应合并 1 条", 1, removed)
        assertEquals("合并后剩 1 条", 1, remaining.size)
        assertEquals("应保留创建最早的那条", first, remaining.first().id)
    }

    @Test
    fun `exact dedup treats case and inner whitespace as equal`() = withStore { store ->
        val a = store.add("Hello World")
        val b = store.add("Hello  World")
        // add 的精确比较区分大小写与空白，两条都写得进去；dedupExact 规范化后相等。
        assertEquals("两条均应写入", 2, store.getAll().size)

        val removed = store.dedupExact()

        assertEquals("连续空白差异应被合并", 1, removed)
        assertEquals(1, store.getAll().size)
        assertEquals("应保留最早的一条", a, store.getAll().first().id)
        assertTrue("保留项应是 a", b != store.getAll().first().id)
    }

    @Test
    fun `exact dedup keeps different assistant scopes apart`() = withStore { store ->
        // 同一内容挂在不同助手 scope 下是两条独立数据，不能合并
        store.add("同名内容", assistantId = "assistant-a")
        store.add("同名内容", assistantId = "assistant-b")

        val removed = store.dedupExact()

        assertEquals("跨 scope 不应合并", 0, removed)
        assertEquals(2, store.getAll().size)
    }

    @Test
    fun `exact dedup on unique entries is a no-op`() = withStore { store ->
        store.add("第一条")
        store.add("第二条")
        store.add("第三条")

        val removed = store.dedupExact()

        assertEquals(0, removed)
        assertEquals(3, store.getAll().size)
    }

    @Test
    fun `candidate pairs skip entries with very different lengths`() = withStore { store ->
        store.add("短句")
        // 长度差超过 2 倍，不该进入语义比对候选
        store.add("这是一条明显长得多的内容用来测试长度粗筛是否生效并且足够长")

        val pairs = store.candidatePairsForSemanticDedup(assistantId = null, includeGlobal = true)

        assertTrue("长度差异大的不该成为候选对", pairs.isEmpty())
    }

    @Test
    fun `candidate pairs include similarly sized entries`() = withStore { store ->
        store.add("用户养了一只柯基")
        store.add("用户养了只柯基犬")

        val pairs = store.candidatePairsForSemanticDedup(assistantId = null, includeGlobal = true)

        assertEquals("长度接近的两条应成为候选对", 1, pairs.size)
    }

    @Test
    fun `removeByIds deletes the requested entries only`() = withStore { store ->
        val keep = store.add("保留这条")
        val drop1 = store.add("删掉这条")
        val drop2 = store.add("还有这条")

        val removed = store.removeByIds(listOf(drop1, drop2))

        assertEquals(2, removed)
        val remaining = store.getAll()
        assertEquals(1, remaining.size)
        assertEquals(keep, remaining.first().id)
    }

    @Test
    fun `removeByIds with empty input is a no-op`() = withStore { store ->
        store.add("内容")
        assertEquals(0, store.removeByIds(emptyList()))
        assertEquals(1, store.getAll().size)
    }
}
