package io.zer0.muse.tools

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.memory.runtime.DefaultMemoryUseCases
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeleteMemoryToolTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
        .allowMainThreadQueries()
        .build()
    private val store = FactStore(db.factDao(), db, assistantId = "assistant-a")
    private val cases = DefaultMemoryUseCases({ store }, PinnedMemoryStore(File(context.cacheDir, "test-pins")))

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `delete memory passes assistant scope and space to transactional delete`() = runTest {
        val id = store.add(
            FactStore.Fact(fact = "assistant-owned preference"),
            scope = "assistant-a",
            spaceId = "work",
        )

        val result = deleteMemoryViaUseCases(
            useCases = cases,
            context = ToolExecutionContext(scope = "assistant-a", spaceId = "work", assistantId = "assistant-a"),
            args = mapOf("id" to id.toString()),
        )

        assertTrue(result.startsWith("Deleted from long-term memory"))
        assertEquals(null, store.getById(id))
        assertTrue(store.getTombstones("assistant-a", "work").contains("assistant-owned preference"))
    }

    @Test
    fun `delete memory refuses assistant identity mismatch even when scope matches`() = runTest {
        val id = store.add(
            FactStore.Fact(fact = "must not be deleted from another assistant"),
            scope = "assistant-a",
            spaceId = "work",
        )

        val result = deleteMemoryViaUseCases(
            useCases = cases,
            context = ToolExecutionContext(scope = "assistant-a", spaceId = "work", assistantId = "assistant-b"),
            args = mapOf("id" to id.toString()),
        )

        assertFalse(result.startsWith("Deleted from long-term memory"))
        assertFalse(store.getById(id) == null)
        assertTrue(store.getTombstones("assistant-a", "work").isEmpty())
    }
}
