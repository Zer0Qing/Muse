package io.zer0.muse.miniphone.ui.moments

import android.net.Uri
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.dao.MpMomentCommentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentLikeDao
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoDecision
import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoPlan
import io.zer0.muse.miniphone.data.moment.auto.MpMomentSkipReason
import io.zer0.muse.miniphone.data.moment.decodeMomentImages
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * 朋友圈状态:时间线分页、发布、点赞、评论、下拉刷新触发角色生成。
 *
 * 库里跑真 Room(内存库),图片落盘跑真 [MpImageStore](临时目录):这两件事都不该用替身,
 * 否则「动态真的写进去了」「图片文件真的在」这两条断言就只是自证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneMomentsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: MiniPhoneDb
    private lateinit var imageDir: File
    private lateinit var repository: MpMomentsRepository
    private val imageBytes = mutableMapOf<String, ByteArray>()
    private var clock: Long = NOW_BASE
    private var idSeq: Int = 0
    private val autoStore: FakeAutoStore = FakeAutoStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = newInMemoryMiniPhoneDb()
        imageDir = File(tempFolder.newFolder(), "images")
        repository = MpMomentsRepository(
            momentDao = db.momentDao(),
            likeDao = db.momentLikeDao(),
            commentDao = db.momentCommentDao(),
            imageStore = MpImageStore(directory = imageDir, openStream = ::openImage),
            now = { clock++ },
            newId = { "id-${idSeq++}" },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun timelineLoadsTwentyThenTheRestNewestFirst() = runBlocking {
        seedMoments(count = 25)

        val viewModel = createViewModel(FakeMomentsHost())
        val first = awaitState(viewModel) { state -> state.isLoaded }
        assertEquals("首屏一页是 20 条", 20, first.moments.size)
        assertTrue("还有更老的没读", first.hasMore)
        assertEquals("倒序:最新的在最上面", "m25", first.moments.first().id)
        assertEquals("m6", first.moments.last().id)

        viewModel.loadMore()
        val all = awaitState(viewModel) { state -> state.moments.size == 25 }
        assertEquals(25, all.moments.size)
        assertFalse("读完最后一页就没有更多了", all.hasMore)
        assertEquals("m25", all.moments.first().id)
        assertEquals("m1", all.moments.last().id)
    }

    @Test
    fun publishKeepsThreeImagesOnDiskAndInRow() = runBlocking {
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.publish(content = "今天天气不错", imageUris = listOf(pickImage("a"), pickImage("b"), pickImage("c")))

        val state = awaitState(viewModel) { current -> current.moments.size == 1 }
        val row = state.moments.single()
        assertEquals("今天天气不错", row.content)
        assertEquals(3, row.images.size)
        assertTrue("自己发的动态可以删", row.isMine)

        val stored = requireNotNull(db.momentDao().getMoment(row.id))
        assertEquals(MpMomentSource.USER, stored.source)
        val paths = decodeMomentImages(stored.imagesJson)
        assertEquals("imagesJson 落三项", 3, paths.size)
        paths.forEach { path -> assertTrue("图片文件应当真实存在:$path", File(path).exists()) }
    }

    @Test
    fun likingTwiceEndsUpUnliked() = runBlocking {
        seedMoments(count = 1)
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.toggleLike(MOMENT_ID)
        val liked = awaitState(viewModel) { state -> state.moments.single().likes.isNotEmpty() }
        assertEquals("点赞人是自己", listOf(SELF_NAME), liked.moments.single().likes)
        assertTrue(liked.moments.single().likedByMe)
        assertEquals(1, db.momentLikeDao().likesOf(MOMENT_ID).size)

        viewModel.toggleLike(MOMENT_ID)
        val unliked = awaitState(viewModel) { state -> state.moments.single().likes.isEmpty() }
        assertFalse("第二次点是取消", unliked.moments.single().likedByMe)
        assertEquals("复合主键保证只有一条", 0, db.momentLikeDao().likesOf(MOMENT_ID).size)
    }

    @Test
    fun commentAndReplyFormTwoLevels() = runBlocking {
        seedMoments(count = 1)
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.comment(MOMENT_ID, parentId = null, content = "第一条")
        val withTop = awaitState(viewModel) { state -> state.moments.single().comments.size == 1 }
        val top = withTop.moments.single().comments.single()
        assertEquals("第一条", top.content)
        assertTrue(top.replies.isEmpty())

        viewModel.comment(MOMENT_ID, parentId = top.id, content = "回一条")
        val withReply = awaitState(viewModel) { state -> state.moments.single().comments.single().replies.size == 1 }

        val comment = withReply.moments.single().comments.single()
        assertEquals("回复压在同一条顶层评论下", 1, comment.replies.size)
        assertEquals("回一条", comment.replies.single().content)
        assertEquals(SELF_NAME, comment.replies.single().senderName)

        val stored = db.momentCommentDao().observeComments(MOMENT_ID).first()
        val reply = stored.single { it.senderType == MpActorType.USER && it.content == "回一条" }
        assertEquals("回复的 parentId 指向父评论", top.id, reply.parentId)
        assertNull("顶层评论没有 parent", stored.single { it.content == "第一条" }.parentId)
    }

    @Test
    fun refreshInsertsOneGeneratedMomentFromACharacter() = runBlocking {
        val host = FakeMomentsHost(composed = "刚看完一场雨")
        val viewModel = createViewModel(host)
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.refresh()

        val state = awaitState(viewModel) { current -> !current.isRefreshing && current.moments.isNotEmpty() }
        val row = state.moments.single()
        assertEquals("刚看完一场雨", row.content)
        assertEquals(CHARACTER_ID, row.authorId)
        assertEquals(CHARACTER_NAME, row.authorName)
        assertEquals(1, host.composeCalls)
        assertEquals(MpMomentSource.AI, requireNotNull(db.momentDao().getMoment(row.id)).source)
    }

    @Test
    fun manualRefreshCountsAsGenerationForBackgroundDebounce() = runBlocking {
        // 白天起手(14:00):把「安静时段」这个更靠前的判定岔开,只验防抖
        clock = DAYTIME_MS
        val host = FakeMomentsHost(composed = "手刷出来的这条")
        val viewModel = createViewModel(host)
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.refresh()

        val state = awaitState(viewModel) { current -> !current.isRefreshing && current.moments.isNotEmpty() }
        val createdAt = requireNotNull(db.momentDao().getMoment(state.moments.single().id)).createdAt
        assertEquals("手刷成功也要记进防抖账本", createdAt, autoStore.lastGeneratedAtMillis)

        val decision = MpMomentAutoPlan.decide(
            nowMillis = createdAt + THIRTY_MINUTES_MS,
            lastGeneratedAtMillis = autoStore.lastGeneratedAtMillis,
            roll = 0.1,
            hasCharacter = true,
            zone = ZONE,
        )
        assertEquals("手刷后 30 分钟,后台任务应被判太快而跳过", MpMomentAutoDecision.Skip(MpMomentSkipReason.TOO_SOON), decision)
    }

    @Test
    fun refreshWithoutGeneratedTextLeavesTimelineAlone() = runBlocking {
        seedMoments(count = 1)
        val host = FakeMomentsHost(composed = null)
        val viewModel = createViewModel(host)
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.refresh()

        val state = awaitState(viewModel) { current -> !current.isRefreshing }
        assertEquals("生成失败不插入", 1, state.moments.size)
        assertEquals(1, host.composeCalls)
        assertNotNull(db.momentDao().getMoment(MOMENT_ID))
    }

    @Test
    fun ownMomentCanBeDeletedButACharacterMomentCannot() = runBlocking {
        db.momentDao().insert(
            MpMomentEntity(
                id = SELF_MOMENT_ID,
                authorId = MP_LOCAL_ACTOR_ID,
                authorName = SELF_NAME,
                content = "自己的动态",
                createdAt = 1L,
                source = MpMomentSource.USER,
            ),
        )
        seedMoments(count = 1)
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.deleteMoment(MOMENT_ID)
        viewModel.deleteMoment(SELF_MOMENT_ID)

        val afterDelete = awaitState(viewModel) { state -> state.moments.none { it.id == SELF_MOMENT_ID } && state.moments.size == 1 }
        assertEquals("角色那条不被删", MOMENT_ID, afterDelete.moments.single().id)
        assertNull("自己的动态已删", db.momentDao().getMoment(SELF_MOMENT_ID))
        assertNotNull("别人的动态没动", db.momentDao().getMoment(MOMENT_ID))
    }

    @Test
    fun deletingOwnCommentTakesItsRepliesWithIt() = runBlocking {
        seedMoments(count = 1)
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.comment(MOMENT_ID, parentId = null, content = "我的评论")
        val top = awaitState(viewModel) { state -> state.moments.single().comments.size == 1 }
            .moments.single().comments.single()
        viewModel.comment(MOMENT_ID, parentId = top.id, content = "我的回复")
        awaitState(viewModel) { state -> state.moments.single().comments.single().replies.size == 1 }
        // 别人回在同一条下面:跟着顶层一起走
        db.momentCommentDao().insert(comment(id = "k-other", momentId = MOMENT_ID, parentId = top.id, at = 90L))
        assertEquals(3, db.momentCommentDao().count(MOMENT_ID))

        viewModel.deleteComment(MOMENT_ID, top.id)

        val after = awaitState(viewModel) { state -> state.moments.single().comments.isEmpty() }
        assertTrue(after.moments.single().comments.isEmpty())
        assertEquals("评论与它下面的回复一起删", 0, db.momentCommentDao().count(MOMENT_ID))
    }

    @Test
    fun someoneElsesCommentCannotBeDeleted() = runBlocking {
        seedMomentsWithSocial()
        val viewModel = createViewModel(FakeMomentsHost())
        val loaded = awaitState(viewModel) { state -> state.isLoaded && state.moments.isNotEmpty() }
        val top = loaded.moments.first { it.id == "m1" }.comments.single()
        assertTrue("阿远写的评论不属于自己", !top.isMine)

        viewModel.deleteComment("m1", top.id)

        val after = awaitState(viewModel) { state -> state.moments.isNotEmpty() }
        assertEquals("阿远那条评论和它下面的回复都还在", 2, db.momentCommentDao().count("m1"))
        assertEquals("界面上也还在", 1, after.moments.first { it.id == "m1" }.comments.size)
    }

    @Test
    fun publishDefaultsToEveryoneVisible() = runBlocking {
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.publish(content = "默认可见", imageUris = emptyList())

        val row = awaitState(viewModel) { state -> state.moments.size == 1 }.moments.single()
        val stored = requireNotNull(db.momentDao().getMoment(row.id))
        assertEquals("默认空串 = 所有人可见", "", stored.visibleToJson)
    }

    @Test
    fun publishWithOnlySelfWritesOwnId() = runBlocking {
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.publish(content = "只给自己看", imageUris = emptyList(), visibleToSelf = true)

        val row = awaitState(viewModel) { state -> state.moments.size == 1 }.moments.single()
        val stored = requireNotNull(db.momentDao().getMoment(row.id))
        assertEquals("勾选后写入自己的 id", MP_LOCAL_ACTOR_ID, stored.visibleToJson)
        assertEquals("界面拿到的也是这份标记", MP_LOCAL_ACTOR_ID, row.visibleToJson)
    }

    @Test
    fun onlyMineTimelineShowsOnlyMineNewestFirst() = runBlocking {
        db.momentDao().insert(ownMoment(id = "mine-early", createdAt = 10L, content = "自己的早一条"))
        seedMoments(count = 3)
        db.momentDao().insert(ownMoment(id = "mine-late", createdAt = 50L, content = "自己的晚一条"))

        val host = FakeMomentsHost(composed = "角色刚发的")
        val viewModel = createViewModel(host, onlyMine = true)

        val state = awaitState(viewModel) { current -> current.isLoaded }

        assertEquals("别人的动态一条不出现,自己的按时间倒序", listOf("mine-late", "mine-early"), state.moments.map { it.id })
        assertTrue("每一行都是自己的", state.moments.all { moment -> moment.isMine })

        viewModel.refresh()

        assertEquals("我的朋友圈不叫角色生成", 0, host.composeCalls)
        assertEquals("库里的动态一条不多", 5, db.momentDao().count())
    }

    @Test
    fun onlyMineTimelineStillTakesMyNewMoment() = runBlocking {
        val viewModel = createViewModel(FakeMomentsHost(), onlyMine = true)
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.publish(content = "只在这一页发的一条", imageUris = emptyList())

        val row = awaitState(viewModel) { state -> state.moments.size == 1 }.moments.single()
        assertEquals("只在这一页发的一条", row.content)
        assertTrue(row.isMine)
        assertEquals(MpMomentSource.USER, requireNotNull(db.momentDao().getMoment(row.id)).source)
    }

    @Test
    fun aPageLoadsLikesAndCommentsInTwoBatchQueries() = runBlocking {
        seedMomentsWithSocial()
        val countingLikes = CountingLikeDao(db.momentLikeDao())
        val countingComments = CountingCommentDao(db.momentCommentDao())
        val batchRepository = MpMomentsRepository(
            momentDao = db.momentDao(),
            likeDao = countingLikes,
            commentDao = countingComments,
            imageStore = MpImageStore(directory = imageDir, openStream = ::openImage),
            now = { clock++ },
            newId = { "batch-${idSeq++}" },
        )

        val viewModel = MiniPhoneMomentsViewModel(
            repository = batchRepository,
            host = FakeMomentsHost(),
            settings = autoStore,
            material = material(),
            random = Random(0),
        )
        val state = awaitState(viewModel) { current -> current.isLoaded }

        assertEquals(3, state.moments.size)
        val byId = state.moments.associateBy { moment -> moment.id }
        assertEquals("m1 一个赞", listOf("阿远"), byId.getValue("m1").likes)
        assertEquals("m1 一条顶层评论", 1, byId.getValue("m1").comments.size)
        assertEquals("m1 的顶层评论下挂一条回复", 1, byId.getValue("m1").comments.single().replies.size)
        assertEquals("m2 两个赞按时间先后", listOf("阿远", "小满"), byId.getValue("m2").likes)
        assertEquals(1, byId.getValue("m2").comments.size)
        assertEquals(1, byId.getValue("m3").likes.size)
        assertEquals(1, byId.getValue("m3").comments.size)

        assertEquals("点赞一页一次批量查询", 1, countingLikes.batchCalls)
        assertEquals("评论一页一次批量查询", 1, countingComments.batchCalls)
        assertEquals("不逐条查点赞", 0, countingLikes.singleCalls)
        assertEquals("不逐条查评论", 0, countingComments.singleCalls)
    }

    /** 造 [count] 条动态:createdAt 递增,id 从 m1 到 mN。 */
    private suspend fun seedMoments(count: Int) {
        (1..count).forEach { index ->
            db.momentDao().insert(
                MpMomentEntity(
                    id = "m$index",
                    authorId = CHARACTER_ID,
                    authorName = CHARACTER_NAME,
                    content = "动态 $index",
                    createdAt = index.toLong(),
                    source = MpMomentSource.AI,
                ),
            )
        }
    }

    /** 三条动态各带点赞与评论:m2 两个赞,m1 带一条回复。 */
    private suspend fun seedMomentsWithSocial() {
        seedMoments(count = 3)
        db.momentLikeDao().insert(like(momentId = "m1", likerId = "c2", likerName = "阿远", at = 10L))
        db.momentLikeDao().insert(like(momentId = "m2", likerId = "c2", likerName = "阿远", at = 11L))
        db.momentLikeDao().insert(like(momentId = "m2", likerId = "c3", likerName = "小满", at = 12L))
        db.momentLikeDao().insert(like(momentId = "m3", likerId = "c2", likerName = "阿远", at = 13L))
        db.momentCommentDao().insert(comment(id = "k1", momentId = "m1", parentId = null, at = 20L))
        db.momentCommentDao().insert(comment(id = "k2", momentId = "m1", parentId = "k1", at = 21L))
        db.momentCommentDao().insert(comment(id = "k3", momentId = "m2", parentId = null, at = 22L))
        db.momentCommentDao().insert(comment(id = "k4", momentId = "m3", parentId = null, at = 23L))
    }

    private fun ownMoment(id: String, createdAt: Long, content: String): MpMomentEntity = MpMomentEntity(
        id = id,
        authorId = MP_LOCAL_ACTOR_ID,
        authorName = SELF_NAME,
        content = content,
        createdAt = createdAt,
        source = MpMomentSource.USER,
    )

    private fun like(momentId: String, likerId: String, likerName: String, at: Long): MpMomentLikeEntity = MpMomentLikeEntity(
        momentId = momentId,
        likerType = MpActorType.CHARACTER,
        likerId = likerId,
        likerName = likerName,
        createdAt = at,
    )

    private fun comment(id: String, momentId: String, parentId: String?, at: Long): MpMomentCommentEntity = MpMomentCommentEntity(
        id = id,
        momentId = momentId,
        parentId = parentId,
        senderType = MpActorType.CHARACTER,
        senderId = "c2",
        senderName = "阿远",
        content = "评论 $id",
        createdAt = at,
    )

    private fun createViewModel(host: MiniPhoneHost, onlyMine: Boolean = false): MiniPhoneMomentsViewModel = MiniPhoneMomentsViewModel(
        repository = repository,
        host = host,
        settings = autoStore,
        material = material(),
        onlyMine = onlyMine,
        random = Random(0),
    )

    /** 真的素材组装:会话与动态都从内存库读,时间用测试时钟。 */
    private fun material(): MpCharacterMaterial = MpCharacterMaterial(
        threadDao = db.threadDao(),
        messageDao = db.messageDao(),
        momentDao = db.momentDao(),
        now = { clock },
        zone = ZONE,
    )

    private suspend fun awaitState(vm: MiniPhoneMomentsViewModel, match: (MomentsUiState) -> Boolean): MomentsUiState =
        withTimeout(TIMEOUT_MS) { vm.uiState.first(match) }

    /** 造一张可读的假图并登记它的字节;同一张图选两次会得到同一个文件。 */
    private fun pickImage(name: String): Uri {
        val uri = Uri.parse("content://mp-test/$name")
        imageBytes[uri.toString()] = PNG_MAGIC + name.toByteArray()
        return uri
    }

    private fun openImage(uri: Uri): InputStream? = imageBytes[uri.toString()]?.let(::ByteArrayInputStream)

    private companion object {
        const val MOMENT_ID: String = "m1"
        const val SELF_MOMENT_ID: String = "mine"
        const val CHARACTER_ID: String = "c1"
        const val CHARACTER_NAME: String = "阿离"
        const val SELF_NAME: String = "子奇"
        const val TIMEOUT_MS: Long = 5_000L

        /** 假时钟起点,保证新发布/点赞/评论都比种子数据新。 */
        const val NOW_BASE: Long = 100_000L

        /** 白天 14:00(东八区):用于防抖用例,避开安静时段。 */
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        val DAYTIME_MS: Long = LocalDateTime.of(2026, 5, 12, 14, 0).atZone(ZONE).toInstant().toEpochMilli()

        const val THIRTY_MINUTES_MS: Long = 30L * 60L * 1000L

        val PNG_MAGIC: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

/** 只做朋友圈需要的两件事:给一份角色名单,按剧本生成(或不生成)一条文本。 */
private val CHARACTER = MpCharacter(id = "c1", name = "阿离", tagline = "住在楼下")

private class FakeMomentsHost(
    private val composed: String? = null,
) : MiniPhoneHost {

    var composeCalls: Int = 0
        private set

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(listOf(CHARACTER))

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? {
        composeCalls += 1
        return composed
    }

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** 假设置:只服务防抖断言 —— 记下写进来的时间,开关恒为开。 */
private class FakeAutoStore : MpMomentAutoStore {

    private val enabledState = MutableStateFlow(true)

    var lastGeneratedAtMillis: Long? = null
        private set

    override val enabledFlow: Flow<Boolean> = enabledState

    override suspend fun isEnabled(): Boolean = enabledState.value

    override suspend fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    override suspend fun lastGeneratedAt(): Long? = lastGeneratedAtMillis

    override suspend fun markGenerated(atMillis: Long) {
        lastGeneratedAtMillis = atMillis
    }

    override val chatProactiveEnabledFlow: Flow<Boolean> = flowOf(false)

    override suspend fun isChatProactiveEnabled(): Boolean = false

    override suspend fun setChatProactiveEnabled(enabled: Boolean) = Unit

    override suspend fun lastProactiveChatAt(): Long? = null

    override suspend fun markProactiveChat(atMillis: Long) = Unit
}

/** 点赞 DAO 的装饰器:只为一件事存在 —— 数清「批量一次」还是「逐条 N 次」。 */
private class CountingLikeDao(private val delegate: MpMomentLikeDao) : MpMomentLikeDao {

    var batchCalls: Int = 0
        private set

    var singleCalls: Int = 0
        private set

    override suspend fun likesOf(momentId: String): List<MpMomentLikeEntity> {
        singleCalls += 1
        return delegate.likesOf(momentId)
    }

    override suspend fun likesOfMomentIds(momentIds: List<String>): List<MpMomentLikeEntity> {
        batchCalls += 1
        return delegate.likesOfMomentIds(momentIds)
    }

    override fun observeLikeCount(momentId: String): Flow<Int> = delegate.observeLikeCount(momentId)

    override suspend fun isLiked(momentId: String, likerType: MpActorType, likerId: String): Boolean =
        delegate.isLiked(momentId, likerType, likerId)

    override suspend fun insert(like: MpMomentLikeEntity) = delegate.insert(like)

    override suspend fun delete(momentId: String, likerType: MpActorType, likerId: String) = delegate.delete(momentId, likerType, likerId)
    override suspend fun deleteAllOf(momentId: String) = delegate.deleteAllOf(momentId)
}

/** 评论 DAO 的装饰器:同上。 */
private class CountingCommentDao(private val delegate: MpMomentCommentDao) : MpMomentCommentDao {

    var batchCalls: Int = 0
        private set

    var singleCalls: Int = 0
        private set

    override fun observeComments(momentId: String): Flow<List<MpMomentCommentEntity>> = delegate.observeComments(momentId)

    override suspend fun commentsOf(momentId: String): List<MpMomentCommentEntity> {
        singleCalls += 1
        return delegate.commentsOf(momentId)
    }

    override suspend fun commentsOfMomentIds(momentIds: List<String>): List<MpMomentCommentEntity> {
        batchCalls += 1
        return delegate.commentsOfMomentIds(momentIds)
    }

    override suspend fun count(momentId: String): Int = delegate.count(momentId)

    override suspend fun insert(comment: MpMomentCommentEntity) = delegate.insert(comment)

    override suspend fun delete(commentId: String) = delegate.delete(commentId)

    override suspend fun deleteByIds(commentIds: List<String>) = delegate.deleteByIds(commentIds)

    override suspend fun deleteAllOf(momentId: String) = delegate.deleteAllOf(momentId)
}
