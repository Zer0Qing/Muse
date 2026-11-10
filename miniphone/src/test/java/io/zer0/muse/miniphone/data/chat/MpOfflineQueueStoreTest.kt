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

/** 待应答队列门面：空白不入队、应答到达后清行、按会话清空。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpOfflineQueueStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpOfflineQueueStore

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpOfflineQueueStore(db.offlineMessageDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `空白内容不入队`() = runBlocking {
        assertFalse(store.enqueue("o1", "t1", "c1", "  ", createdAt = 1))
        assertEquals(0, store.observeAll().first().size)
    }

    @Test
    fun `入队后能按会话读回且带 trim`() = runBlocking {
        assertTrue(store.enqueue("o1", "t1", "c1", "  晚点回我  ", createdAt = 1))
        assertEquals("晚点回我", store.observeByThread("t1").first().single().content)
        assertEquals(0, store.observeByThread("t2").first().size)
    }

    @Test
    fun `应答到达后清掉那一行`() = runBlocking {
        store.enqueue("o1", "t1", "c1", "在吗", createdAt = 1)
        store.settle("o1")
        assertEquals(0, store.observeByThread("t1").first().size)
    }

    @Test
    fun `按时间正序且同 id 覆盖`() = runBlocking {
        store.enqueue("o1", "t1", "c1", "第一句", createdAt = 1)
        store.enqueue("o2", "t1", "c1", "第二句", createdAt = 2)
        store.enqueue("o1", "t1", "c1", "第一句改", createdAt = 1)
        val rows = store.observeByThread("t1").first()
        assertEquals(listOf("o1", "o2"), rows.map { it.id })
        assertEquals("第一句改", rows.first().content)
    }

    @Test
    fun `清空会话不影响别的会话`() = runBlocking {
        store.enqueue("o1", "t1", "c1", "在吗", createdAt = 1)
        store.enqueue("o2", "t2", "c1", "吃了吗", createdAt = 2)
        store.clearThread("t1")
        assertEquals(listOf("o2"), store.observeAll().first().map { it.id })
    }
}
