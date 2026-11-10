package io.zer0.muse.miniphone.data.forum

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpForumPostEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

/**
 * 论坛门面：发帖、倒序时间线、按作者看主页、点赞名单、评论、删帖级联。
 *
 * 最要紧的两条是「空白不落库」与「拿不到生成就什么都不写」——
 * 前者不让空内容占住时间线，后者不让一条编出来的话冒充角色说的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpForumStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var host: ForumHost
    private lateinit var store: MpForumStore
    private var clock = 0L
    private var seq = 0

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        host = ForumHost()
        clock = 0L
        seq = 0
        store = MpForumStore(db, host, now = { clock }, newId = { "id-${seq++}" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 发一条并推进时钟，让「新帖在前」这件事真的由时间决定，而不是由 id 巧合决定。 */
    private suspend fun post(authorId: String = "c1", content: String = "一条帖子"): MpForumPostEntity {
        clock += 1_000L
        return checkNotNull(store.publish(authorId, "作者", content))
    }

    private suspend fun seedMoment(id: String, authorId: String, content: String) {
        db.momentDao().insert(
            MpMomentEntity(
                id = id,
                authorId = authorId,
                authorName = "作者",
                content = content,
                createdAt = 1L,
                source = MpMomentSource.AI,
            ),
        )
    }

    @Test
    fun `发帖落库且时间线新帖在前`() = runBlocking {
        val oldest = post(content = "最早")
        val middle = post(content = "中间")
        val newest = post(content = "最新")
        val rows = store.observeTimeline().first()
        assertEquals(listOf(newest.id, middle.id, oldest.id), rows.map { it.id })
        assertEquals(listOf(newest.id, middle.id), store.pageTimeline(2, 0).map { it.id })
        assertEquals(listOf(oldest.id), store.pageTimeline(2, 2).map { it.id })
    }

    @Test
    fun `空白正文不落库`() = runBlocking {
        assertNull(store.publish("c1", "作者", ""))
        assertNull(store.publish("c1", "作者", "   \n  "))
        assertEquals(0, store.observeTimeline().first().size)
    }

    @Test
    fun `正文与作者名去掉首尾空白`() = runBlocking {
        val row = checkNotNull(store.publish(" c1 ", "  阿离  ", "  今天很好  "))
        assertEquals("c1", row.authorId)
        assertEquals("阿离", row.authorName)
        assertEquals("今天很好", row.content)
        assertEquals(MpForumStore.EMPTY_LIKES, row.likeIdsJson)
    }

    @Test
    fun `按作者看主页只含该作者的帖子`() = runBlocking {
        val mine = post(authorId = "c1")
        post(authorId = "c2")
        val rows = store.observeByAuthor("c1").first()
        assertEquals(listOf(mine.id), rows.map { it.id })
        assertEquals(0, store.observeByAuthor("nobody").first().size)
    }

    @Test
    fun `点赞后名单里有他 再点一次回到未赞`() = runBlocking {
        val row = post()
        assertTrue(store.toggleLike(row.id, "u1"))
        assertEquals(listOf("u1"), MpForumStore.decodeLikeIds(db.forumPostDao().get(row.id)?.likeIdsJson))
        assertFalse(store.toggleLike(row.id, "u1"))
        assertEquals(emptyList<String>(), MpForumStore.decodeLikeIds(db.forumPostDao().get(row.id)?.likeIdsJson))
    }

    @Test
    fun `不同人的点赞互不覆盖`() = runBlocking {
        val row = post()
        assertTrue(store.toggleLike(row.id, "u1"))
        assertTrue(store.toggleLike(row.id, "u2"))
        assertEquals(listOf("u1", "u2"), MpForumStore.decodeLikeIds(db.forumPostDao().get(row.id)?.likeIdsJson))
        assertFalse(store.toggleLike(row.id, "u1"))
        assertEquals(listOf("u2"), MpForumStore.decodeLikeIds(db.forumPostDao().get(row.id)?.likeIdsJson))
    }

    @Test
    fun `帖子不存在时点赞返回 false`() = runBlocking {
        assertFalse(store.toggleLike("missing", "u1"))
        assertNull(db.forumPostDao().get("missing"))
    }

    @Test
    fun `坏点赞名单当没人赞过 一条脏数据不让整帖读不出来`() = runBlocking {
        db.forumPostDao().insert(
            MpForumPostEntity(
                id = "dirty",
                authorId = "c1",
                authorName = "作者",
                content = "名单是坏的",
                likeIdsJson = "not-json",
                createdAt = 1L,
            ),
        )
        assertEquals(emptyList<String>(), MpForumStore.decodeLikeIds("not-json"))
        assertTrue(store.toggleLike("dirty", "u1"))
        assertEquals(listOf("u1"), MpForumStore.decodeLikeIds(db.forumPostDao().get("dirty")?.likeIdsJson))
    }

    @Test
    fun `评论按时间正序且空白不落库`() = runBlocking {
        val row = post()
        assertNull(store.comment(row.id, "u1", "我", "   "))
        clock += 1L
        val first = checkNotNull(store.comment(row.id, "u1", "我", " 第一条 "))
        clock += 1L
        val second = checkNotNull(store.comment(row.id, "c2", "角色", "第二条"))
        val comments = store.observeComments(row.id).first()
        assertEquals(listOf(first.id, second.id), comments.map { it.id })
        assertEquals("第一条", comments.first().content)
        assertEquals(2, db.forumCommentDao().countByPost(row.id))
    }

    @Test
    fun `帖子不存在时不留孤儿评论`() = runBlocking {
        assertNull(store.comment("missing", "u1", "我", "说给谁听"))
        assertEquals(0, db.forumCommentDao().countByPost("missing"))
    }

    @Test
    fun `删帖级联清掉评论`() = runBlocking {
        val row = post()
        store.comment(row.id, "u1", "我", "第一条")
        store.comment(row.id, "c2", "角色", "第二条")
        store.deletePost(row.id)
        assertEquals(0, store.observeComments(row.id).first().size)
        assertNull(db.forumPostDao().get(row.id))
    }

    @Test
    fun `角色发帖拿到文本就落库并署名`() = runBlocking {
        host.scene = "  今天的风很大  "
        val row = checkNotNull(store.composePost("c1", "阿离"))
        assertEquals("c1", row.authorId)
        assertEquals("阿离", row.authorName)
        assertEquals("今天的风很大", row.content)
        assertEquals(listOf(row.id), store.observeTimeline().first().map { it.id })
    }

    @Test
    fun `拿不到生成结果就不落库 不编一条像他说的话`() = runBlocking {
        host.scene = null
        assertNull(store.composePost("c1", "阿离"))
        host.scene = "   "
        assertNull(store.composePost("c1", "阿离"))
        assertEquals(0, store.observeTimeline().first().size)
    }

    @Test
    fun `没给素材时取这个角色最近的动态 不取别人的`() = runBlocking {
        seedMoment("mo1", "c1", "今天下了很大的雨")
        seedMoment("mo2", "c2", "别人的近况")
        host.scene = "雨停了"
        checkNotNull(store.composePost("c1", "阿离"))
        assertTrue(host.brief.contains("今天下了很大的雨"))
        assertFalse(host.brief.contains("别人的近况"))
    }

    @Test
    fun `给了素材就用给的 不再翻库`() = runBlocking {
        seedMoment("mo1", "c1", "今天下了很大的雨")
        host.scene = "海边"
        checkNotNull(store.composePost("c1", "阿离", material = "昨天去了海边"))
        assertTrue(host.brief.contains("昨天去了海边"))
        assertFalse(host.brief.contains("今天下了很大的雨"))
    }
}

/** 只实现论坛用得到的那一条生成桥，并记下任务说明；其余能力按「没有」处理。 */
private class ForumHost : MiniPhoneHost {
    var scene: String? = null
    var brief: String = ""

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(emptyList())

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = ""))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun composeScene(characterId: String, brief: String): String? {
        this.brief = brief
        return scene
    }

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
