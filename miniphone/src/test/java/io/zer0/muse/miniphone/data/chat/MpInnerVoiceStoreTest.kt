package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 心声门面：trim 后才落库、历史与会话维度读回、删会话级联清空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpInnerVoiceStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpInnerVoiceStore

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpInnerVoiceStore(db.innerVoiceDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `空白心声不落库`() = runBlocking {
        store.put("id-1", "t1", "c1", null, "   ", createdAt = 10)
        assertEquals(0, store.observeByThread("t1").first().size)
    }

    @Test
    fun `写一条能按会话与历史读回且带 trim`() = runBlocking {
        store.put("id-1", "t1", "c1", messageId = "m1", text = "  其实我也很想他  ", createdAt = 20)
        val byThread = store.observeByThread("t1").first()
        assertEquals(1, byThread.size)
        assertEquals("其实我也很想他", byThread.first().text)
        assertEquals(1, store.observeHistory(10).first().size)
        assertEquals("id-1", store.forMessage("m1")?.id)
        assertNull(store.forMessage("nope"))
    }

    @Test
    fun `删会话把心声一并清掉`() = runBlocking {
        store.put("id-1", "t1", "c1", null, "想他", createdAt = 20)
        store.deleteByThread("t1")
        assertEquals(0, store.observeByThread("t1").first().size)
    }
}
