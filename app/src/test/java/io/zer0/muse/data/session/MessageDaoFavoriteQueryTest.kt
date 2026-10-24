package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 收藏查询口径（审计 §11-3 / §11-4）。
 *
 * 钉住三件事：
 *  1. 软删会话里的收藏不出现在收藏夹（也不再出现在分组 chip 里）；
 *  2. 收藏不再有静默的 200 条上限；
 *  3. 变体组的最新版序号可查，收藏页据此标"旧版本"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessageDaoFavoriteQueryTest {

    private lateinit var database: MuseDb

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun session(id: String, deletedAt: Long? = null) {
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                title = id,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                deletedAt = deletedAt,
            ),
        )
    }

    /** 收藏一条消息。[variant] 是变体组二元组，避免参数列表过长。 */
    private suspend fun favorite(
        id: String,
        createdAt: Long,
        sessionId: String = "live",
        tag: String? = null,
        variant: Pair<String, Int>? = null,
    ) {
        database.messageDao().upsert(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = "ASSISTANT",
                content = id,
                createdAt = createdAt,
                favorite = true,
                favoriteTag = tag,
                variantGroupId = variant?.first,
                variantIndex = variant?.second ?: 0,
                variantCount = if (variant != null) 3 else 1,
            ),
        )
    }

    @Test
    fun `favorites of a soft deleted session are hidden`() = runTest {
        session("live")
        session("gone", deletedAt = 500L)
        favorite("keep", createdAt = 1_000L)
        favorite("ghost", createdAt = 2_000L, sessionId = "gone")

        val ids = database.messageDao().observeAllFavorites().first().map { it.id }

        assertEquals(listOf("keep"), ids)
    }

    @Test
    fun `soft deleted message is hidden from favorites`() = runTest {
        session("live")
        favorite("alive", createdAt = 1_000L)
        favorite("removed", createdAt = 2_000L)
        // 软删消息：直接改行，不用 helper（deletedAt 只在个别用例出现）
        database.messageDao().upsert(
            MessageEntity(
                id = "removed",
                sessionId = "live",
                role = "ASSISTANT",
                content = "removed",
                createdAt = 2_000L,
                favorite = true,
                deletedAt = 2_500L,
            ),
        )

        val ids = database.messageDao().observeAllFavorites().first().map { it.id }

        assertEquals(listOf("alive"), ids)
    }

    @Test
    fun `favorite tags ignore soft deleted sessions`() = runTest {
        session("live")
        session("gone", deletedAt = 500L)
        favorite("a", createdAt = 1_000L, tag = "灵感")
        favorite("b", createdAt = 2_000L, sessionId = "gone", tag = "别处")

        val tags = database.messageDao().observeAllFavoriteTags().first()

        assertEquals(listOf("灵感"), tags)
    }

    @Test
    fun `favorites are not silently capped at 200`() = runTest {
        session("live")
        repeat(205) { index ->
            favorite("m-$index", createdAt = 1_000L + index)
        }

        val favorites = database.messageDao().observeAllFavorites().first()

        assertEquals(205, favorites.size)
        assertTrue("最新一条必须在里面", favorites.any { it.id == "m-204" })
    }

    @Test
    fun `latest variant index per group is readable`() = runTest {
        session("live")
        favorite("v0", createdAt = 1_000L, variant = "g1" to 0)
        database.messageDao().upsert(
            MessageEntity(
                id = "v2",
                sessionId = "live",
                role = "ASSISTANT",
                content = "newer",
                createdAt = 3_000L,
                variantGroupId = "g1",
                variantIndex = 2,
                variantCount = 3,
            ),
        )

        val heads = database.messageDao().getLatestVariantIndexes(listOf("g1"))

        assertEquals(1, heads.size)
        assertEquals("g1", heads.first().variantGroupId)
        assertEquals(2, heads.first().variantIndex)
    }

    @Test
    fun `favorite without variant group reports no variant head`() = runTest {
        session("live")
        favorite("plain", createdAt = 1_000L)

        val heads = database.messageDao().getLatestVariantIndexes(listOf())

        assertTrue(heads.isEmpty())
        assertFalse(database.messageDao().observeAllFavorites().first().first().variantGroupId != null)
    }
}
