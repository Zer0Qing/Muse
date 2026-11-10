package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 备忘录门面：空白不落库、trim、按角色与全局读回、删除。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpMemoStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpMemoStore

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpMemoStore(db.memoDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `空白内容不落库`() = runBlocking {
        assertFalse(store.save("m1", "c1", "   ", createdAt = 1, updatedAt = 1))
        assertEquals(0, store.observeAll().first().size)
    }

    @Test
    fun `写入会 trim 且能按角色读回`() = runBlocking {
        assertTrue(store.save("m1", "c1", "  记得买伞  ", createdAt = 1, updatedAt = 2))
        assertEquals("记得买伞", store.observeForCharacter("c1").first().single().content)
        assertEquals(0, store.observeForCharacter("c2").first().size)
    }

    @Test
    fun `同 id 覆盖更新而不是攒两条`() = runBlocking {
        store.save("m1", "c1", "旧内容", createdAt = 1, updatedAt = 1)
        store.save("m1", "c1", "新内容", createdAt = 1, updatedAt = 9)
        val all = store.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("新内容", all.single().content)
        assertEquals(9L, all.single().updatedAt)
    }

    @Test
    fun `按最近修改倒序`() = runBlocking {
        store.save("old", "c1", "旧", createdAt = 1, updatedAt = 1)
        store.save("new", "c1", "新", createdAt = 2, updatedAt = 5)
        assertEquals(listOf("new", "old"), store.observeAll().first().map { it.id })
    }

    @Test
    fun `删除后不再出现`() = runBlocking {
        store.save("m1", "c1", "记得买伞", createdAt = 1, updatedAt = 1)
        store.delete("m1")
        assertEquals(0, store.observeAll().first().size)
    }
}
