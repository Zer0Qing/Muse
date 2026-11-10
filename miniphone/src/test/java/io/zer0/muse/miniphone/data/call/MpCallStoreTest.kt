package io.zer0.muse.miniphone.data.call

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 通话记录门面：落库字段、时长非负、历史倒序、按会话清空。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpCallStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpCallStore

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpCallStore(db.callSessionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `视频通话落库带种类与时长`() = runBlocking {
        store.record("c1", "t1", "char-1", MpCallKind.VIDEO, startedAt = 100, durationMs = 5000)
        val row = store.observeByThread("t1").first().single()
        assertEquals("video", row.kind)
        assertEquals(5000L, row.durationMs)
        assertEquals("char-1", row.characterId)
    }

    @Test
    fun `负时长当成未接通报 0 不写脏数据`() = runBlocking {
        store.record("c1", "t1", "char-1", MpCallKind.VOICE, startedAt = 100, durationMs = -42)
        assertEquals(0L, store.observeByThread("t1").first().single().durationMs)
    }

    @Test
    fun `历史按开始时间倒序`() = runBlocking {
        store.record("old", "t1", "char-1", MpCallKind.VOICE, startedAt = 100, durationMs = 1)
        store.record("new", "t1", "char-1", MpCallKind.VIDEO, startedAt = 900, durationMs = 1)
        assertEquals(listOf("new", "old"), store.observeHistory(10).first().map { it.id })
    }

    @Test
    fun `删会话只清该会话的记录`() = runBlocking {
        store.record("c1", "t1", "char-1", MpCallKind.VOICE, startedAt = 100, durationMs = 1)
        store.record("c2", "t2", "char-2", MpCallKind.VOICE, startedAt = 200, durationMs = 1)
        store.deleteByThread("t1")
        assertEquals(listOf("c2"), store.observeHistory(10).first().map { it.id })
    }
}
