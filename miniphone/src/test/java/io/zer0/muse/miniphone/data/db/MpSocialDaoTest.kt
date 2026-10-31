package io.zer0.muse.miniphone.data.db

import io.zer0.muse.miniphone.data.db.entity.MpAlbumEntity
import io.zer0.muse.miniphone.data.db.entity.MpDiaryEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.db.entity.MpProfileEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 点赞、评论、日记、相册、资料这几张表的读写(朋友圈与相册页要用的最小集合)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpSocialDaoTest {

    private lateinit var db: MiniPhoneDb

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun likeCanBeAddedCountedAndRemoved() = runBlocking {
        val likes = db.momentLikeDao()
        likes.insert(like("m1", MpActorType.CHARACTER, "c1"))

        assertTrue(likes.isLiked("m1", MpActorType.CHARACTER, "c1"))
        assertEquals(1, withTimeout(TIMEOUT_MS) { likes.observeLikeCount("m1").first() })

        // 同一人重复点赞不新增行(复合主键 + REPLACE)
        likes.insert(like("m1", MpActorType.CHARACTER, "c1"))
        assertEquals(1, likes.likesOf("m1").size)

        likes.delete("m1", MpActorType.CHARACTER, "c1")
        assertFalse(likes.isLiked("m1", MpActorType.CHARACTER, "c1"))
        assertEquals(0, likes.likesOf("m1").size)
    }

    @Test
    fun commentIsStoredWithNullableParent() = runBlocking {
        val comments = db.momentCommentDao()
        comments.insert(comment("c1", parentId = null))
        comments.insert(comment("c2", parentId = "c1"))

        val observed = withTimeout(TIMEOUT_MS) { comments.observeComments("m1").first() }

        assertEquals(listOf("c1", "c2"), observed.map { it.id })
        assertNull(observed.first().parentId)
        assertEquals("c1", observed.last().parentId)
        assertEquals(2, comments.count("m1"))

        comments.delete("c2")
        assertEquals(1, comments.count("m1"))
    }

    @Test
    fun diaryUpsertsPerDate() = runBlocking {
        val diary = db.diaryDao()
        diary.upsert(MpDiaryEntity(date = "2026-10-28", content = "第一天", updatedAt = 1_000L))
        diary.upsert(MpDiaryEntity(date = "2026-10-29", content = "第二天", updatedAt = 2_000L))
        diary.upsert(MpDiaryEntity(date = "2026-10-28", content = "第一天(改)", updatedAt = 3_000L))

        assertEquals("第一天(改)", diary.get("2026-10-28")?.content)
        assertEquals(listOf("2026-10-29", "2026-10-28"), diary.recent(limit = 10).map { it.date })
        assertEquals(
            "第二天",
            withTimeout(TIMEOUT_MS) { diary.observe("2026-10-29").first() }?.content,
        )
    }

    @Test
    fun albumHidesAndFavoritesWithoutLosingRows() = runBlocking {
        val album = db.albumDao()
        val visibleId = album.insert(albumEntry("content://img/1", createdAt = 1_000L))
        val hiddenId = album.insert(albumEntry("content://img/2", createdAt = 2_000L))

        album.setHidden(hiddenId, hidden = true)
        album.setFavorite(visibleId, favorite = true)

        assertEquals(listOf("content://img/1"), album.page(includeHidden = false, limit = 10, offset = 0).map { it.uri })
        assertEquals(2, album.page(includeHidden = true, limit = 10, offset = 0).size)
        assertEquals(listOf(visibleId), album.favorites(includeHidden = false).map { it.id })
        assertTrue(requireNotNull(album.getById(visibleId)).favorite)
        assertTrue(requireNotNull(album.getById(hiddenId)).hidden)
    }

    @Test
    fun profileIsSingleRowUpsert() = runBlocking {
        val profile = db.profileDao()
        profile.upsert(MpProfileEntity(nickname = "子奇", signature = "第一版"))

        assertEquals("子奇", profile.getProfile()?.nickname)

        profile.upsert(MpProfileEntity(id = MpProfileEntity.SINGLE_ROW_ID, nickname = "子奇", signature = "第二版"))

        assertEquals("第二版", profile.getProfile()?.signature)
        assertEquals(
            "第二版",
            withTimeout(TIMEOUT_MS) { profile.observeProfile().first() }?.signature,
        )
    }

    private fun like(momentId: String, type: MpActorType, actorId: String): MpMomentLikeEntity = MpMomentLikeEntity(
        momentId = momentId,
        likerType = type,
        likerId = actorId,
        likerName = "角色$actorId",
        createdAt = 1_000L,
    )

    private fun comment(id: String, parentId: String?): MpMomentCommentEntity = MpMomentCommentEntity(
        id = id,
        momentId = "m1",
        parentId = parentId,
        senderType = MpActorType.USER,
        senderId = "local",
        senderName = "我",
        content = "评论 $id",
        createdAt = 1_000L,
    )

    private fun albumEntry(uri: String, createdAt: Long): MpAlbumEntity =
        MpAlbumEntity(uri = uri, source = MpAlbumSource.AI, createdAt = createdAt)

    private companion object {
        const val TIMEOUT_MS: Long = 5_000L
    }
}
