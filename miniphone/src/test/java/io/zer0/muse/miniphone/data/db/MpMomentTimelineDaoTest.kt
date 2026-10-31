package io.zer0.muse.miniphone.data.db

import io.zer0.muse.miniphone.data.db.dao.MpMomentDao
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 动态时间线:倒序分页 + 同刻兜底排序 + 按作者过滤。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpMomentTimelineDaoTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var moments: MpMomentDao

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        moments = db.momentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun timelinePagesFromNewestToOldest() = runBlocking {
        (1..5).forEach { index ->
            moments.insert(moment("m$index", createdAt = index * 1_000L))
        }

        assertEquals(listOf("m5", "m4"), moments.pageTimeline(limit = 2, offset = 0).map { it.id })
        assertEquals(listOf("m3", "m2"), moments.pageTimeline(limit = 2, offset = 2).map { it.id })
        assertEquals(listOf("m1"), moments.pageTimeline(limit = 2, offset = 4).map { it.id })
        assertEquals(emptyList<String>(), moments.pageTimeline(limit = 2, offset = 5).map { it.id })
    }

    @Test
    fun sameTimestampFallsBackToIdDescending() = runBlocking {
        moments.insert(moment("a", createdAt = 1_000L))
        moments.insert(moment("b", createdAt = 1_000L))

        assertEquals(listOf("b", "a"), moments.pageTimeline(limit = 10, offset = 0).map { it.id })
    }

    @Test
    fun observationAndAuthorFilterWork() = runBlocking {
        moments.insert(moment("m1", createdAt = 1_000L, authorId = "c1"))
        moments.insert(moment("m2", createdAt = 2_000L, authorId = "c2"))

        val timeline = withTimeout(TIMEOUT_MS) { moments.observeTimeline().first() }
        val onlyC1 = withTimeout(TIMEOUT_MS) { moments.observeByAuthor("c1").first() }

        assertEquals(listOf("m2", "m1"), timeline.map { it.id })
        assertEquals(listOf("m1"), onlyC1.map { it.id })
    }

    @Test
    fun deleteRemovesMoment() = runBlocking {
        moments.insert(moment("m1", createdAt = 1_000L))

        moments.delete("m1")

        assertEquals(0, moments.count())
        assertNull(moments.getMoment("m1"))
    }

    private fun moment(id: String, createdAt: Long, authorId: String = "c1"): MpMomentEntity = MpMomentEntity(
        id = id,
        authorId = authorId,
        authorName = "角色$authorId",
        content = "动态 $id",
        createdAt = createdAt,
        source = MpMomentSource.AI,
    )

    private companion object {
        const val TIMEOUT_MS: Long = 5_000L
    }
}
