package io.zer0.muse.miniphone.data.moment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import io.zer0.muse.miniphone.ui.moments.displayNameOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 朋友圈名字的落库与展示：库里不存界面语言，界面按当前语言兜底。
 *
 * 修复前的缺陷：空名字在落库前被补成「我」「角色」，于是中文建的动态切到英文界面后
 * 作者名仍是中文，而且没有任何入口能改回来 —— 界面语言被写进了数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpMomentsNameFallbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: io.zer0.muse.miniphone.data.db.MiniPhoneDb
    private lateinit var repository: MpMomentsRepository

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        repository = MpMomentsRepository(
            momentDao = db.momentDao(),
            likeDao = db.momentLikeDao(),
            commentDao = db.momentCommentDao(),
            imageStore = MpImageStore(directory = File(context.cacheDir, "name-fallback"), openStream = { null }),
            now = { 1_000L },
            newId = { "id-1" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `自己的动态没给名字时库里留空 不写中文`() = runBlocking {
        val row = checkNotNull(repository.publish(content = "今天很好", imageUris = emptyList(), authorName = ""))
        assertEquals(MpMomentSource.USER, row.source)
        assertTrue("作者名不能是烘进库里的界面语言，实际是「${row.authorName}」", row.authorName.isEmpty())
    }

    @Test
    fun `角色动态名字为空时库里也留空`() = runBlocking {
        val row = checkNotNull(
            repository.insertFromCharacter(
                character = io.zer0.muse.miniphone.api.MpCharacter(id = "c1", name = ""),
                content = "随便说说",
            ),
        )
        assertTrue("角色名同样不能烘进库，实际是「${row.authorName}」", row.authorName.isEmpty())
    }

    @Test
    fun `点赞与评论的空名字同样留空`() = runBlocking {
        val row = checkNotNull(repository.publish(content = "正文", imageUris = emptyList(), authorName = "阿离"))
        repository.toggleLike(row.id, MpActorType.USER, MP_LOCAL_ACTOR_ID, likerName = "")
        val liked = db.momentLikeDao().likesOf(row.id).single()
        assertTrue("点赞人名不能烘进库，实际是「${liked.likerName}」", liked.likerName.isEmpty())

        val comment = checkNotNull(
            repository.addComment(
                momentId = row.id,
                parentId = null,
                senderType = MpActorType.USER,
                senderId = MP_LOCAL_ACTOR_ID,
                senderName = "",
                content = "说一句",
            ),
        )
        assertTrue("评论人名不能烘进库，实际是「${comment.senderName}」", comment.senderName.isEmpty())
    }

    @Test
    fun `展示时空名字按当前语言补称呼`() {
        assertEquals(context.getString(R.string.mp_moments_self_name), displayNameOf(context, "", isMine = true))
        assertEquals(context.getString(R.string.mp_moments_character_name), displayNameOf(context, "  ", isMine = false))
    }

    @Test
    fun `有名字就原样用 界面不替用户改名`() {
        assertEquals("阿离", displayNameOf(context, " 阿离 ", isMine = false))
        assertEquals("子奇", displayNameOf(context, "子奇", isMine = true))
    }
}
