package io.zer0.memory.runtime

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
