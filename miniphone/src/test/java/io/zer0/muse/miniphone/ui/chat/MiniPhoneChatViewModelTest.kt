package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.chat.MpGroupChatStore
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 聊天页状态:发送回路、串行化、未读清零、倒序分页。
 *
 * 用真 Room(内存库)验证「先落库再请求」这件事 —— 假 DAO 只能证明调用顺序,
 * 证明不了库里真的先有用户那句话。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatViewModelTest {

    private lateinit var db: MiniPhoneDb
    private var idCounter: Int = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = newInMemoryMiniPhoneDb()
        idCounter = 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun userMessageIsPersistedBeforeTheCharacterIsAsked() = runBlocking {
        seedThread()
        val host = GatedHost(answer = "在的")
        val viewModel = createViewModel(host)

        viewModel.send("在吗")
        withTimeout(TIMEOUT_MS) { host.firstCallReached.await() }

        // 生成已经在跑,此刻库里必须已经有用户消息,而且还没有角色回复
        assertEquals(listOf(MpActorType.USER), storedSenders())
        assertEquals("在吗", host.lastHistory.last().content)

        host.letThrough()

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        assertEquals(listOf(MpActorType.USER, MpActorType.CHARACTER), storedSenders())
        assertEquals(listOf("在吗", "在的"), bubbleContents(state))
        assertEquals("阿离", state.peerName)
    }

    @Test
    fun failedReplyBecomesASystemNotice() = runBlocking {
        seedThread()
        val viewModel = createViewModel(FakeHost(answer = null))

        viewModel.send("在吗")

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        val notice = state.messages.last()
        assertEquals(MpMessageType.SYSTEM, notice.type)
        assertEquals("对方没有回应", notice.content)
        assertFalse("系统提示不参与左右分栏", notice.isMine)
        assertEquals(listOf(MpActorType.USER, MpActorType.SYSTEM), storedSenders())
    }

    @Test
    fun activityIsShownWhileWorkingAndClearedAfterTheText() = runBlocking {
        seedThread()
        val host = GatedHost(answer = "找到了")
        val viewModel = createViewModel(host)

        viewModel.send("帮我找找")
        withTimeout(TIMEOUT_MS) { host.firstCallReached.await() }

        val working = awaitChat(viewModel) { it.activity != null }
        assertEquals(MpActivityKind.SEARCHING, working.activity)
        assertEquals("活动提示不进消息列表", 1, working.messages.size)

        host.letThrough()

        val done = awaitChat(viewModel) { chat -> chat.messages.size == 2 && chat.activity == null }
        assertEquals("找到了", done.messages.last().content)
    }

    @Test
    fun repliesQueueUpInsteadOfRunningConcurrently() = runBlocking {
        seedThread()
        val host = GatedHost(answer = "好")
        val viewModel = createViewModel(host)

        viewModel.send("第一句")
        withTimeout(TIMEOUT_MS) { host.firstCallReached.await() }
        // 回复还没回来就再发一条:允许,但只能排队
        viewModel.send("第二句")
        // 等第二句真的落库再放行第一条回复：否则「谁先入库」就取决于线程调度,而不是行为
        withTimeout(TIMEOUT_MS) {
            while (db.messageDao().countInThread(THREAD_ID) < 2) delay(5L)
        }
        assertEquals("第一条还没回来，不应该开始第二次生成", 1, host.callCount)

        host.letThrough()

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 4 && !chat.isGenerating }
        assertEquals(2, host.callCount)
        assertEquals("两次生成不能重叠", 1, host.maxConcurrent)
        // 用户消息不等网络:第二句先落库,两条回复排队依次到
        assertEquals(listOf("第一句", "第二句", "好", "好"), bubbleContents(state))
    }

    @Test
    fun openingTheChatClearsUnreadCount() = runBlocking {
        seedThread(unread = 5)
        assertEquals(5, db.threadDao().unreadCount(THREAD_ID))

        createViewModel(FakeHost(answer = "在的"))

        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().unreadCount(THREAD_ID) != 0) delay(POLL_INTERVAL_MS)
        }
    }

    @Test
    fun reEnteringTheChatClearsUnreadAgain() = runBlocking {
        seedThread()
        val viewModel = createViewModel(FakeHost(answer = "在的"))
        awaitChat(viewModel) { chat -> chat.characterId.isNotEmpty() }

        // 同一个会话复用同一个 ViewModel:再次进页时还得把新未读抹掉
        db.threadDao().setUnread(THREAD_ID, 3)
        viewModel.markRead()

        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().unreadCount(THREAD_ID) != 0) delay(POLL_INTERVAL_MS)
        }
    }

    @Test
    fun newMessageWhileChatIsVisibleClearsUnread() = runBlocking {
        seedThread(unread = 1)
        val viewModel = createViewModel(FakeHost(answer = "在的"))
        // 页面可见:进页清一次,之后每来一条新消息再清一次
        viewModel.setVisible(true)
        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().unreadCount(THREAD_ID) != 0) delay(POLL_INTERVAL_MS)
        }

        // 后台落一条角色消息并把未读加一(与主动私信走同一条写入路径)
        db.messageDao().insert(
            MpMessageEntity(
                id = "bg-1",
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "在忙吗",
                createdAt = NOW_BASE + MINUTE_MS,
            ),
        )
        db.threadDao().increaseUnread(THREAD_ID)

        // 消息数变了就再清一次:用户正看着这条会话,列表上不该留未读角标
        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().unreadCount(THREAD_ID) != 0) delay(POLL_INTERVAL_MS)
        }
        viewModel.setVisible(false)
    }

    @Test
    fun leavingTheChatStopsClearingUnread() = runBlocking {
        seedThread()
        val viewModel = createViewModel(FakeHost(answer = "在的"))
        viewModel.setVisible(true)
        awaitChat(viewModel) { chat -> chat.characterId.isNotEmpty() }
        viewModel.setVisible(false)

        // 离页后后台又来了消息:未读必须留着,否则用户永远看不到提示
        db.messageDao().insert(
            MpMessageEntity(
                id = "bg-2",
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "睡了吗",
                createdAt = NOW_BASE + MINUTE_MS,
            ),
        )
        db.threadDao().increaseUnread(THREAD_ID)
        delay(QUIET_CHECK_MS)

        assertEquals(1, db.threadDao().unreadCount(THREAD_ID))
    }

    @Test
    fun firstPageLoadsThirtyAndOlderPageCompletesTheHistory() = runBlocking {
        seedThread(messageCount = 40)
        val viewModel = createViewModel(FakeHost(answer = "在的"))

        val first = awaitChat(viewModel) { chat -> chat.messages.size == CHAT_PAGE_SIZE }
        assertTrue("还有更早的消息", first.hasMore)
        assertEquals((11..40).map { "第 $it 句" }, bubbleContents(first))

        viewModel.loadOlder()

        val full = awaitChat(viewModel) { chat -> chat.messages.size == 40 }
        assertFalse("历史已全部加载", full.hasMore)
        assertEquals((1..40).map { "第 $it 句" }, bubbleContents(full))
        assertTrue("更早的消息排在前面", full.messages.first().createdAt < full.messages.last().createdAt)
    }

    @Test
    fun unknownCharacterFallsBackToThePlaceholderName() = runBlocking {
        seedThread()
        val viewModel = createViewModel(FakeHost(answer = "在的", characters = listOf(MpCharacter(id = "someone-else", name = "别人"))))

        val state = awaitChat(viewModel) { chat -> chat.peerName.isNotEmpty() }
        assertEquals("已注销的角色", state.peerName)
    }

    @Test
    fun failedSendIsMarkedAndResendingTurnsItGreen() = runBlocking {
        seedThread()
        val host = SwitchableHost(answer = null)
        val viewModel = createViewModel(host)

        viewModel.send("在吗")
        val failed = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        val mine = failed.messages.first { it.isMine }
        assertEquals("一轮下来一句都没落,用户那条算失败", MpMessageStatus.FAILED, mine.status)
        assertEquals(MpMessageStatus.FAILED, db.messageDao().getMessage(mine.id)?.status)

        // 重发:同一行回到「发送中」,拿到回复后变成已发出
        host.answer = "在的"
        viewModel.resend(mine.id)

        val done = awaitChat(viewModel) { chat -> chat.messages.lastOrNull()?.content == "在的" && !chat.isGenerating }
        assertEquals(MpMessageStatus.SENT, db.messageDao().getMessage(mine.id)?.status)
        assertEquals(MpMessageStatus.SENT, done.messages.first { it.id == mine.id }.status)
        assertEquals("重发不能多出一条一样的", 1, done.messages.count { it.isMine && it.content == "在吗" })
    }

    @Test
    fun failedSendStartsAsSendingAndEndsAsFailedInTheDatabase() = runBlocking {
        seedThread()
        val host = GatedHost(answer = null)
        val viewModel = createViewModel(host)

        viewModel.send("在吗")
        withTimeout(TIMEOUT_MS) { host.firstCallReached.await() }

        // 请求还在路上:这一条是「发送中」,不是失败也不是成功
        val sending = awaitChat(viewModel) { chat -> chat.messages.size == 1 }
        assertEquals(MpMessageStatus.SENDING, sending.messages.single().status)
        assertEquals(MpMessageStatus.SENDING, db.messageDao().getMessage(sending.messages.single().id)?.status)

        host.letThrough()

        val failed = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        assertEquals(MpMessageStatus.FAILED, failed.messages.first { it.isMine }.status)
        assertTrue("失败仍落一条系统提示", failed.messages.last().isSystem)
    }

    @Test
    fun deletingAMessageRemovesItFromTheWindowAndTheDatabase() = runBlocking {
        seedThread(messageCount = 2)
        val viewModel = createViewModel(FakeHost(answer = "在的"))
        val loaded = awaitChat(viewModel) { chat -> chat.messages.size == 2 }
        val target = loaded.messages.first()

        viewModel.deleteMessage(target.id)

        val after = awaitChat(viewModel) { chat -> chat.messages.size == 1 }
        assertEquals(listOf("第 2 句"), after.messages.map { it.content })
        assertNull(db.messageDao().getMessage(target.id))
        assertEquals("只删消息,会话留着", 1, db.messageDao().countInThread(THREAD_ID))
        assertNotNull(db.threadDao().getThread(THREAD_ID))
    }

    private fun createViewModel(host: MiniPhoneHost): MiniPhoneChatViewModel {
        var clock = NOW_BASE
        return MiniPhoneChatViewModel(
            threadId = THREAD_ID,
            messageDao = db.messageDao(),
            threadDao = db.threadDao(),
            memberDao = db.threadMemberDao(),
            groupStore = MpGroupChatStore(
                db = db,
                threadDao = db.threadDao(),
                memberDao = db.threadMemberDao(),
                messageDao = db.messageDao(),
            ),
            host = host,
            imageStore = MpImageStore(directory = File(System.getProperty("java.io.tmpdir"), "mp-chat-images"), openStream = { null }),
            clock = ChatWriteClock(
                now = {
                    clock += 1_000L
                    clock
                },
                newId = {
                    idCounter += 1
                    "new-$idCounter"
                },
            ),
        )
    }

    private suspend fun seedThread(messageCount: Int = 0, unread: Int = 0) {
        db.threadDao().upsert(
            MpThreadEntity(
                id = THREAD_ID,
                characterId = CHARACTER_ID,
                unreadCount = unread,
                lastMessageAt = messageCount * MINUTE_MS,
            ),
        )
        repeat(messageCount) { index ->
            val number = index + 1
            db.messageDao().insert(
                MpMessageEntity(
                    id = "seed-$number",
                    threadId = THREAD_ID,
                    characterId = CHARACTER_ID,
                    sender = if (number % 2 == 0) MpActorType.CHARACTER else MpActorType.USER,
                    type = MpMessageType.TEXT,
                    content = "第 $number 句",
                    createdAt = number * MINUTE_MS,
                ),
            )
        }
    }

    private suspend fun storedSenders(): List<MpActorType> = db.messageDao().observeMessages(THREAD_ID).first().map { it.sender }

    private suspend fun awaitChat(viewModel: MiniPhoneChatViewModel, predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first(predicate) }

    /** 只取消息行,时间分隔不进比较。 */
    private fun bubbleContents(state: ChatUiState): List<String> =
        state.items.filterIsInstance<ChatItem.Message>().map { it.message.content }

    private companion object {
        const val THREAD_ID: String = "t1"
        const val CHARACTER_ID: String = "c1"
        const val TIMEOUT_MS: Long = 5_000L
        const val POLL_INTERVAL_MS: Long = 5L

        /** 验证「离页后不再动未读」时要等的空窗期:比轮询间隔长得多,足够让一个错误的收集器发作。 */
        const val QUIET_CHECK_MS: Long = 300L
        const val MINUTE_MS: Long = 60_000L

        /** 假时钟的起点,保证新消息一定比种子消息新。 */
        const val NOW_BASE: Long = 10_000_000L
    }
}

/** 可中途改口的宿主:第一次不给回复,把 [answer] 填上之后就正常回。 */
private class SwitchableHost(
    var answer: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "阿离")),
) : MiniPhoneHost {

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        val reply = answer
        emit(if (reply == null) MpReplyEvent.Failed else MpReplyEvent.Text(reply))
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** 立即给答案的宿主。`answer` 为 null 时发 `Failed`,与「对方没有回应」对应。 */
private class FakeHost(
    private val answer: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "阿离")),
) : MiniPhoneHost {

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        emit(if (answer == null) MpReplyEvent.Failed else MpReplyEvent.Text(answer))
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/**
 * 会卡住的宿主:先报一句「在翻找」,然后一直等到 [letThrough] 才给出文本。
 *
 * 顺带记下调用次数与并发峰值 —— 「串行」这件事只能从这里读出来。
 */
private class GatedHost(
    private val answer: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "阿离")),
) : MiniPhoneHost {

    val firstCallReached: CompletableDeferred<Unit> = CompletableDeferred()

    @Volatile
    var callCount: Int = 0
        private set

    @Volatile
    var maxConcurrent: Int = 0
        private set

    @Volatile
    var lastHistory: List<MpChatTurn> = emptyList()
        private set

    private val release: CompletableDeferred<Unit> = CompletableDeferred()

    @Volatile
    private var active: Int = 0

    fun letThrough() {
        release.complete(Unit)
    }

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        callCount += 1
        lastHistory = history
        active += 1
        maxConcurrent = maxOf(maxConcurrent, active)
        try {
            emit(MpReplyEvent.Activity(MpActivityKind.SEARCHING))
            firstCallReached.complete(Unit)
            release.await()
            emit(if (answer == null) MpReplyEvent.Failed else MpReplyEvent.Text(answer))
        } finally {
            active -= 1
        }
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
