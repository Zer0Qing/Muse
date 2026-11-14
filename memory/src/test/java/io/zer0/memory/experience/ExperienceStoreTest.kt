package io.zer0.memory.experience

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
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
}
