package io.zer0.memory.experience

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import java.nio.file.Files

class ExperienceStoreTest {

    private fun newStore(): ExperienceStore {
        val dir = Files.createTempDirectory("experience-test").toFile()
        return ExperienceStore(dir)
    }

    @Test
    fun `record same category content is idempotent and stable`() = runTest {
        val store = newStore()
        val id1 = store.record("hanako", "search", "搜索前先查记忆再搜网页")
        val id2 = store.record("hanako", "search", "搜索前先查记忆再搜网页")

        assertEquals(id1, id2)
        val entries = store.list("hanako", "search")
        assertEquals(1, entries.size)
        assertEquals(id1, entries.first().id)
    }

    @Test
    fun `category path traversal is rejected`() = runTest {
        val store = newStore()
        listOf("../escape", "..\\escape", "/tmp/escape").forEach { category ->
            val result = runCatching { store.record("hanako", category, "bad") }
            assertEquals(true, result.isFailure)
            assertEquals(true, result.exceptionOrNull() is IllegalArgumentException)
        }
        val assistantResult = runCatching { store.record("../other", "safe", "bad") }
        assertEquals(true, assistantResult.isFailure)
    }

    @Test
    fun `different assistants do not share experiences`() = runTest {
        val store = newStore()
        store.record("hanako", "search", "经验A")
        store.record("butter", "search", "经验B")

        assertEquals(1, store.list("hanako", "search").size)
        assertEquals(1, store.list("butter", "search").size)
        assertEquals(2, store.size())
    }

    @Test
    fun `listAll spans assistants and sorts by updatedAt desc`() = runTest {
        val store = newStore()
        val older = store.record("hanako", "search", "先查记忆", title = "旧", tags = listOf("kotlin"))
        val newer = store.record("butter", "review", "先看 diff 再评审", title = "新")

        val all = store.listAll()
        assertEquals(2, all.size)
        // updatedAt 由 Instant.now() 生成，写入顺序即时间顺序；并行毫秒内可能同值，因此只断言集合与字段。
        assertEquals(setOf(older, newer), all.map { it.id }.toSet())
        val old = all.first { it.id == older }
        assertEquals("旧", old.title)
        assertEquals(listOf("kotlin"), old.tags)
        assertEquals(ExperienceEntry.SOURCE_MANUAL, old.source)
    }

    @Test
    fun `update keeps title and moves entry across category files`() = runTest {
        val store = newStore()
        val id = store.record("hanako", "search", "内容A", title = "标题A")
        val current = store.list("hanako", "search").single()

        assertEquals(true, store.update(current.copy(category = "review", title = "改过的标题")))
        assertEquals(0, store.list("hanako", "search").size)
        val moved = store.list("hanako", "review").single()
        assertEquals(id, moved.id)
        assertEquals("改过的标题", moved.title)
        assertEquals(false, store.update(current.copy(id = "不存在的 id")))
    }

    @Test
    fun `delete removes only the targeted entry and reports misses`() = runTest {
        val store = newStore()
        val keep = store.record("hanako", "search", "留下")
        val drop = store.record("hanako", "search", "删掉")

        assertEquals(true, store.delete(drop))
        assertEquals(listOf(keep), store.list("hanako", "search").map { it.id })
        assertEquals(false, store.delete(drop))
    }

    @Test
    fun `revision advances on every successful write`() = runTest {
        val store = newStore()
        val start = store.revision.value
        val id = store.record("hanako", "search", "内容")
        assertTrue(store.revision.value > start)
        val afterRecord = store.revision.value
        // 幂等重写同一条内容不产生新文件写入，不该动 revision
        store.record("hanako", "search", "内容")
        assertEquals(afterRecord, store.revision.value)
        store.delete(id)
        assertTrue(store.revision.value > afterRecord)
    }
}
