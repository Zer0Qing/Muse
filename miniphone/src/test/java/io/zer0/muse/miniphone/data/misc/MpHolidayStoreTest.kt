package io.zer0.muse.miniphone.data.misc

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 节日门面：记日子、按 dateKey 查当天。
 *
 * 要守住的是「每年重复」与「只发生一次」绝不含糊：重复的 dateKey 必须是月日、year=0，
 * 一次的必须是完整日期、year 为正；二者不自洽就拒绝，不让一次性日子伪装成每年重复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpHolidayStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpHolidayStore
    private var seq = 0

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        seq = 0
        store = MpHolidayStore(db, newId = { "id-${seq++}" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `每年重复落库为月日且年份为零`() = runBlocking {
        val id = store.add("02-14", "情人节")
        assertNotNull(id)
        val event = store.observeByDate("02-14").first().first()
        assertEquals(0, event.year)
        assertTrue(MpHolidayStore.isRecurring(event))
    }

    @Test
    fun `只发生一次落库为完整日期且年份为正`() = runBlocking {
        val id = store.add("2026-02-14", "我们的纪念日", year = 2026)
        assertNotNull(id)
        val event = store.observeByDate("2026-02-14").first().first()
        assertEquals(2026, event.year)
        assertFalse(MpHolidayStore.isRecurring(event))
    }

    @Test
    fun `按日期键查询各自命中互不串`() = runBlocking {
        store.add("02-14", "每年情人节")
        store.add("2026-02-14", "一次性纪念日", year = 2026)
        val recurring = store.observeByDate("02-14").first()
        val once = store.observeByDate("2026-02-14").first()
        assertEquals(1, recurring.size)
        assertEquals(0, recurring.first().year)
        assertEquals(1, once.size)
        assertEquals(2026, once.first().year)
    }

    @Test
    fun `某一天的查询合并重复与一次性`() = runBlocking {
        store.add("02-14", "每年情人节")
        store.add("2026-02-14", "一次性纪念日", year = 2026)
        assertEquals(2, store.observeForDay("02-14", "2026-02-14").first().size)
    }

    @Test
    fun `空白名字不落库`() = runBlocking {
        assertNull(store.add("02-14", "   "))
        assertEquals(0, store.observeAll().first().size)
    }

    @Test
    fun `日期格式与年份不自洽一律拒绝`() = runBlocking {
        assertNull(store.add("2026-02-14", "重复却写了完整日期", year = 0))
        assertNull(store.add("02-14", "一次却只写了月日", year = 2026))
        assertEquals(0, store.observeAll().first().size)
    }

    @Test
    fun `删除后列表为空`() = runBlocking {
        val id = requireNotNull(store.add("02-14", "情人节"))
        store.delete(id)
        assertEquals(0, store.observeAll().first().size)
    }
}
