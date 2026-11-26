package io.zer0.memory.runtime

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryUseCasesTest {

    @Test
    fun `save and search round trip uses scope`() = runTest {
        val factDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FactDb::class.java)
            .allowMainThreadQueries().build()
        val tmp = Files.createTempDirectory("memory-use-cases").toFile()
        try {
            val store = FactStore(factDb.factDao(), factDb)
            val pinned = PinnedMemoryStore(File(tmp, "pinned"))
            val cases = DefaultMemoryUseCases(
                factStoreProvider = { store },
                pinnedMemoryStore = pinned,
            )
            val saved = cases.save(
                SaveMemoryRequest(
                    scope = MemoryScopeKey("default", "main", "default"),
                    fact = FactStore.Fact(fact = "用户喜欢猫"),
                ),
            )
            assertTrue(saved is SaveMemoryResult.Saved)
            val found = cases.search(
                MemorySearchRequest(
                    scope = MemoryScopeKey("default", "main", "default"),
                    query = "猫",
                ),
            )
            assertTrue(found is MemorySearchResult.Found)
            assertEquals(1, (found as MemorySearchResult.Found).facts.size)

            val deleted = cases.delete(
                DeleteMemoryRequest(
                    scope = MemoryScopeKey("default", "main", "default"),
                    factId = (saved as SaveMemoryResult.Saved).id,
                ),
            )
            assertTrue(deleted is DeleteMemoryResult.Deleted)
        } finally {
            factDb.close()
        }
    }

    @Test
    fun `pin and unpin round trip for default assistant`() = runTest {
        val factDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FactDb::class.java)
            .allowMainThreadQueries().build()
        val tmp = Files.createTempDirectory("memory-use-cases-pin").toFile()
        try {
            val store = FactStore(factDb.factDao(), factDb)
            val pinned = PinnedMemoryStore(File(tmp, "pinned"))
            val cases = DefaultMemoryUseCases(
                factStoreProvider = { store },
                pinnedMemoryStore = pinned,
            )
            val scope = MemoryScopeKey("default", "main", "default")
            val pinnedResult = cases.pin(PinMemoryRequest(scope, "永远记得生日"))
            assertTrue(pinnedResult is PinMemoryResult.Pinned)
            val unpin = cases.unpin(UnpinMemoryRequest(scope, id = (pinnedResult as PinMemoryResult.Pinned).id.take(8), keyword = null))
            assertTrue(unpin is UnpinMemoryResult.Removed)
        } finally {
            factDb.close()
        }
    }

    @Test
    fun `search applies tags dates and hides suppressed facts by default`() = runTest {
        val factDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FactDb::class.java)
            .allowMainThreadQueries().build()
        val tmp = Files.createTempDirectory("memory-use-cases-search").toFile()
        try {
            val store = FactStore(factDb.factDao(), factDb)
            val cases = DefaultMemoryUseCases({ store }, PinnedMemoryStore(File(tmp, "pinned")))
            store.add(
                FactStore.Fact(
                    fact = "用户喜欢春天散步",
                    tags = listOf("preference"),
                    time = "2026-11-01T00:00:00Z",
                ),
                scope = "main",
                spaceId = "default",
            )
            store.add(
                FactStore.Fact(
                    fact = "用户曾经喜欢冬天滑雪",
                    tags = listOf("preference"),
                    time = "2025-11-01T00:00:00Z",
                    suppressedAt = "2026-11-02T00:00:00Z",
                ),
                scope = "main",
                spaceId = "default",
            )
            val found = cases.search(
                MemorySearchRequest(
                    scope = MemoryScopeKey("default", "main", "default"),
                    query = "用户",
                    tags = listOf("preference"),
                    dateRange = FactStore.DateRange(from = "2026-01-01T00:00:00Z"),
                ),
            )
            assertEquals(listOf("用户喜欢春天散步"), (found as MemorySearchResult.Found).facts.map { it.fact })
        } finally {
            factDb.close()
        }
    }

    @Test
    fun `assistant scoped pinned dedup preserves another assistant`() = runTest {
        val factDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FactDb::class.java)
            .allowMainThreadQueries().build()
        val tmp = Files.createTempDirectory("memory-use-cases-dedup").toFile()
        try {
            val pinned = PinnedMemoryStore(File(tmp, "pinned"))
            pinned.add("同一内容", assistantId = "assistant-a")
            pinned.add("同一  内容", assistantId = "assistant-a")
            pinned.add("同一内容", assistantId = "assistant-b")
            val cases = DefaultMemoryUseCases(
                factStoreProvider = { FactStore(factDb.factDao(), factDb) },
                pinnedMemoryStore = pinned,
            )
            val removed = cases.dedupPinned(
                DedupPinnedRequest(
                    scope = MemoryScopeKey("assistant-a", "assistant-a", "default"),
                    includeGlobal = false,
                ),
            )
            assertEquals(1, (removed as DedupPinnedResult.Removed).count)
            assertEquals(1, pinned.getForAssistant("assistant-a", includeGlobal = false).size)
            assertEquals(1, pinned.getForAssistant("assistant-b", includeGlobal = false).size)
        } finally {
            factDb.close()
        }
    }

    // ── 事实置顶（facts.pinned_at 是唯一事实源）──────────────────────────

    /**
     * 起一个 {"default" 库 + 独立置顶文件} 的测试环境。
     * 退出时清掉临时目录与库，避免跨用例互相看到置顶条目。
     */
    private fun withUseCases(
        tag: String,
        block: suspend (cases: DefaultMemoryUseCases, store: FactStore, pinned: PinnedMemoryStore) -> Unit,
    ) = runTest {
        val factDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FactDb::class.java)
            .allowMainThreadQueries().build()
        val tmp = Files.createTempDirectory(tag).toFile()
        try {
            val store = FactStore(factDb.factDao(), factDb)
            val pinned = PinnedMemoryStore(File(tmp, "pinned"))
            val cases = DefaultMemoryUseCases(factStoreProvider = { store }, pinnedMemoryStore = pinned)
            block(cases, store, pinned)
        } finally {
            factDb.close()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `toggle fact pin writes pinnedAt and keeps standalone pins apart`() = withUseCases("pin-toggle") { cases, store, pinned ->
        val key = MemoryScopeKey("default", "main", "default")
        val id = store.add(FactStore.Fact(fact = "用户每周三打网球"), scope = "main", spaceId = "default")

        assertEquals(FactPinResult.Changed(pinned = true), cases.toggleFactPinned(key, id))
        assertNotNull("置顶后 facts.pinned_at 应有值", store.getById(id)?.pinnedAt)
        assertEquals(listOf(id), store.getPinned("main", "default").map { it.id })
        // 新路径不再写固定记忆文件：那里只放"一段独立文字"的置顶。
        assertTrue("事实置顶不得再往固定记忆文件写副本", pinned.getForAssistant("default", true).isEmpty())

        assertEquals(FactPinResult.Changed(pinned = false), cases.toggleFactPinned(key, id))
        assertNull("取消置顶后应清回 NULL", store.getById(id)?.pinnedAt)
        assertTrue(store.getPinned("main", "default").isEmpty())
    }

    @Test
    fun `toggle rejects a fact that belongs to another space`() = withUseCases("pin-space") { cases, store, _ ->
        val id = store.add(FactStore.Fact(fact = "工作空间里的安排"), scope = "main", spaceId = "work")

        val rejected = cases.toggleFactPinned(MemoryScopeKey("default", "main", "default"), id)

        assertTrue("跨 Space 的置顶必须被拒: $rejected", rejected is FactPinResult.Rejected)
        assertNull("被拒时不得改到那条事实", store.getById(id)?.pinnedAt)
    }

    @Test
    fun `same content in two spaces pins independently`() = withUseCases("pin-isolation") { cases, store, _ ->
        val defaultSpace = MemoryScopeKey("default", "main", "default")
        val inDefault = store.add(FactStore.Fact(fact = "用户每天早上跑步"), scope = "main", spaceId = "default")
        val inWork = store.add(FactStore.Fact(fact = "用户每天早上跑步"), scope = "main", spaceId = "work")

        assertEquals(FactPinResult.Changed(pinned = true), cases.toggleFactPinned(defaultSpace, inDefault))

        assertEquals(listOf(inDefault), store.getPinned("main", "default").map { it.id })
        assertTrue("另一个 Space 的相同内容不得跟着置顶", store.getPinned("main", "work").isEmpty())
        assertNull(store.getById(inWork)?.pinnedAt)
    }

    @Test
    fun `unpin clears legacy pinned file residue by exact content only`() = withUseCases("legacy-residue") { cases, store, pinned ->
        val key = MemoryScopeKey("default", "main", "default")
        val id = store.add(FactStore.Fact(fact = "用户养了一只猫"), scope = "main", spaceId = "default")
        // 旧版本把事实置顶同时写进固定记忆文件；那条副本没有事实 id，只能靠原文认回。
        pinned.add("用户养了一只猫", assistantId = "default")
        // 内容包含同一段文字的独立置顶是另一条记忆，取消置顶不该顺手删掉它。
        pinned.add("用户养了一只猫,还养了一条狗", assistantId = "default")

        cases.toggleFactPinned(key, id)
        assertEquals(FactPinResult.Changed(pinned = false), cases.toggleFactPinned(key, id))

        assertEquals(
            listOf("用户养了一只猫,还养了一条狗"),
            pinned.getForAssistant("default", includeGlobal = true).map { it.content },
        )
    }

    @Test
    fun `deleting a pinned fact clears its legacy residue and the row`() = withUseCases("pin-delete") { cases, store, pinned ->
        val key = MemoryScopeKey("default", "main", "default")
        val id = store.add(FactStore.Fact(fact = "用户下月搬家"), scope = "main", spaceId = "default")
        pinned.add("用户下月搬家", assistantId = "default")
        cases.toggleFactPinned(key, id)

        assertEquals(DeleteMemoryResult.Deleted(id), cases.delete(DeleteMemoryRequest(key, id)))

        assertNull("删除后行不应再存在", store.getById(id))
        assertTrue("删除置顶事实时旧副本应一并清掉", pinned.getForAssistant("default", true).isEmpty())
        assertEquals("删掉的事实不能再切换置顶", FactPinResult.Missing, cases.toggleFactPinned(key, id))
    }
}
