package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MiniPhoneHost
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
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import io.zer0.muse.miniphone.ui.shell.UNKNOWN_CHARACTER_NAME
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 群聊的落库、选人与节奏。
 *
 * 用真 Room(内存库):「谁说了什么」「谁先落库」都是从这里读出来的断言。
 * 宿主用替身记录每次被叫的是谁、当时看到的历史、库里已经有多少条消息 ——
 * 串行与停顿只能这样证明。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneGroupChatViewModelTest {

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
    fun groupMessagesRecordTheSpeakerAndTheOwnersMessageRecordsNothing() = runBlocking {
        seedGroup()
        val host = CastHost()
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 2)

        viewModel.send("大家早")

        val state = awaitState(viewModel) { it.messages.size == 3 && !it.isGenerating }
        val stored = storedMessages()

        assertEquals("自己那条不记角色", listOf(""), stored.filter { it.sender == MpActorType.USER }.map { it.characterId })
        assertEquals("两条回复都记着发言角色", host.calls.toSet(), stored.filter { it.sender == MpActorType.CHARACTER }.map { it.characterId }.toSet())
        assertTrue(state.isGroup)
        assertEquals("阿远、小满", state.groupTitle)
        assertEquals(listOf("阿远", "小满"), state.members.map { it.name })
        assertEquals("顶栏人数含自己", 3, state.memberCount)
    }

    @Test
    fun groupMembersArriveInJoinOrderAndKeepThePlaceholderWhenACharacterIsGone() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2", "c-gone"))
        val viewModel = createViewModel(CastHost())

        val state = awaitState(viewModel) { it.members.size == 3 }

        assertEquals(listOf("c1", "c2", "c-gone"), state.members.map { it.characterId })
        assertEquals(listOf("阿远", "小满", "已注销的角色"), state.members.map { it.name })
        assertEquals("少了一个人也还是三行 + 自己", 4, state.memberCount)
    }

    @Test
    fun fiveMemberGroupAnswersWithAtMostTwoCharacters() = runBlocking {
        seedGroup(memberIds = FIVE_MEMBER_IDS)
        val host = CastHost()
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 5)

        viewModel.send("大家晚上好")

        awaitState(viewModel) { !it.isGenerating && it.messages.size >= 3 }
        assertEquals("一条消息最多两个人接", 2, host.calls.size)
        assertEquals("不能是同一个人说两遍", 2, host.calls.distinct().size)
        assertEquals(2, storedMessages().count { it.sender == MpActorType.CHARACTER })
    }

    @Test
    fun aNamedMemberAlwaysAnswers() = runBlocking {
        seedGroup(memberIds = FIVE_MEMBER_IDS)
        val host = CastHost()
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 5)

        viewModel.send("小满你在吗")

        awaitState(viewModel) { !it.isGenerating && it.messages.size >= 3 }
        assertTrue("点名的人必回: ${host.calls}", host.calls.contains("c2"))
        assertEquals(2, host.calls.size)
    }

    @Test
    fun aNamedMemberPlusTheRecentSpeakerAreExactlyTheTwo() = runBlocking {
        seedGroup(memberIds = FIVE_MEMBER_IDS)
        // 阿岑刚说过话:点名的阿岑不会,但小满被点名,阿岑是最近发言者
        db.messageDao().insert(
            MpMessageEntity(
                id = "recent-1",
                threadId = GROUP_THREAD_ID,
                characterId = "c4",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "我先说一句",
                createdAt = NOW_BASE - 1_000L,
            ),
        )
        val host = CastHost()
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 5)

        viewModel.send("小满你怎么看")

        awaitState(viewModel) { !it.isGenerating && it.messages.size >= 4 }
        assertEquals("点名的在前,最近说话的补上", listOf("c2", "c4"), host.calls)
    }

    @Test
    fun theSecondSpeakerSeesTheFirstOnesWordsPrefixedWithTheirName() = runBlocking {
        seedGroup()
        val host = CastHost(answerOf = { characterId -> if (characterId == "c1") "在的" else "我也在" })
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 2)

        viewModel.send("在吗")

        awaitState(viewModel) { !it.isGenerating && it.messages.size == 3 }
        val first = host.calls[0]
        val second = host.calls[1]
        val firstReply = if (first == "c1") "在的" else "我也在"
        val seenBySecond = requireNotNull(host.histories[second])
        assertEquals(
            "后说话的人看得到前一个人说的话,而且带着名字",
            "${nameOf(first)}：$firstReply",
            seenBySecond.last().content,
        )
        assertEquals(MpActorType.CHARACTER, seenBySecond.last().sender)
    }

    @Test
    fun duplicateSubmissionStartsOnlyOneRound() = runBlocking {
        seedGroup()
        val gate = CompletableDeferred<Unit>()
        val host = CastHost(gate = gate)
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 2)

        viewModel.send("在吗")
        awaitCalls(host, 1)
        // 同一句话又提交了一次(手抖、重试):不再起一轮
        viewModel.send("在吗")
        awaitDuplicateSettled()

        gate.complete(Unit)
        awaitState(viewModel) { !it.isGenerating && it.messages.size >= 3 }

        assertEquals("只跑一轮应答", 2, host.calls.size)
        assertEquals("两条用户消息都落库了", 2, storedMessages().count { it.sender == MpActorType.USER })
    }

    @Test
    fun respondersRunOneAfterAnotherWithAGapBetweenThem() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2", "c3"))
        val storedAtCall = mutableListOf<Int>()
        val storedAtPause = mutableListOf<Int>()
        val host = CastHost(onCall = { _, _ -> storedAtCall += db.messageDao().countInThread(GROUP_THREAD_ID) })
        val viewModel = createViewModel(host, pause = { storedAtPause += db.messageDao().countInThread(GROUP_THREAD_ID) })
        readyGroup(viewModel, 3)

        viewModel.send("在吗")

        awaitState(viewModel) { !it.isGenerating && it.messages.size == 3 }
        assertEquals("两个角色之间只停一次", 1, storedAtPause.size)
        assertEquals("停顿发生时第一条回复已经落库", 2, storedAtPause.single())
        assertEquals("第一个人开始时只有用户那条,第二个人开始时多了第一条回复", listOf(1, 2), storedAtCall)
        assertEquals("两个人各叫一次", 2, host.calls.size)
    }

    @Test
    fun typingCarriesTheNameOfTheCharacterWhoIsAboutToSpeak() = runBlocking {
        seedGroup()
        val gate = CompletableDeferred<Unit>()
        val typingNames = mutableListOf<String?>()
        lateinit var viewModel: MiniPhoneChatViewModel
        val host = CastHost(gate = gate, onCall = { _, _ -> typingNames += viewModel.uiState.value.typingName })
        viewModel = createViewModel(host)
        readyGroup(viewModel, 2)

        viewModel.send("在吗")
        awaitCalls(host, 1)

        assertEquals("第一个人开口时界面上写的是他的名字", listOf(nameOf(host.calls.single())), typingNames)

        gate.complete(Unit)
        awaitState(viewModel) { !it.isGenerating && it.messages.size == 3 }
        assertNull("一轮结束后不再挂着谁在打字", viewModel.uiState.value.typingName)
    }

    @Test
    fun renamingUpdatesTheTitleAndStopsAutoRenaming() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2"), title = "阿远、小满")
        val viewModel = createViewModel(CastHost())
        readyGroup(viewModel, 2)

        viewModel.groupAdmin.renameGroup("  周末小队 ")

        val renamed = awaitState(viewModel) { state -> state.groupTitle == "周末小队" }
        assertEquals("界面上的群名跟着换", "周末小队", renamed.groupTitle)
        assertTrue("库里写入了「改过名」", db.threadDao().getThread(GROUP_THREAD_ID)?.titleCustomized ?: false)

        viewModel.groupAdmin.addMembers(listOf("c3"))
        val afterAdd = awaitState(viewModel) { state -> state.members.size == 3 }
        assertEquals("改过名之后不再自动重算", "周末小队", afterAdd.groupTitle)
    }

    @Test
    fun addingPeopleRecalculatesTheDefaultTitle() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2"), title = "阿远、小满")
        val viewModel = createViewModel(CastHost())
        readyGroup(viewModel, 2)

        viewModel.groupAdmin.addMembers(listOf("c2", "c3"))

        val state = awaitState(viewModel) { current -> current.members.size == 3 }
        assertEquals("重复与已在群里的不算新人", listOf("c1", "c2", "c3"), state.members.map { it.characterId })
        assertEquals("默认名跟着重算", "阿远、小满、阿吾", state.groupTitle)
    }

    @Test
    fun removingSomeoneUpdatesTheMembersButKeepsTheirWords() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2", "c3"), title = "阿远、小满、阿吾")
        db.messageDao().insert(
            MpMessageEntity(
                id = "said-1",
                threadId = GROUP_THREAD_ID,
                characterId = "c2",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "我说过的",
                createdAt = NOW_BASE - 1_000L,
            ),
        )
        val viewModel = createViewModel(CastHost())
        readyGroup(viewModel, 3)

        viewModel.groupAdmin.removeMember("c2")

        val state = awaitState(viewModel) { current -> current.members.size == 2 }
        assertEquals(listOf("c1", "c3"), state.members.map { it.characterId })
        assertEquals("他说过的话还在", 1, db.messageDao().countInThread(GROUP_THREAD_ID))
        assertEquals("默认名跟着剩下的人重算", "阿远、阿吾", state.groupTitle)
    }

    @Test
    fun disbandingRemovesTheWholeGroup() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2"))
        val viewModel = createViewModel(CastHost())
        readyGroup(viewModel, 2)

        viewModel.groupAdmin.disbandGroup()

        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().getThread(GROUP_THREAD_ID) != null) delay(POLL_MS)
        }
        assertEquals(0, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
        assertEquals(0, db.messageDao().countInThread(GROUP_THREAD_ID))
    }

    @Test
    fun directMessagesStillAnswerOnce() = runBlocking {
        db.threadDao().upsert(MpThreadEntity(id = "t-dm", characterId = "c1", lastMessageAt = NOW_BASE))
        val host = CastHost()
        val viewModel = createViewModel(host, threadId = "t-dm")

        viewModel.send("在吗")

        awaitState(viewModel) { !it.isGenerating && it.messages.size == 2 }
        assertEquals("私信只有一个人接", listOf("c1"), host.calls)
        val stored = db.messageDao().observeMessages("t-dm").first()
        assertEquals("私信里自己那句也记会话角色", "c1", stored.first { it.sender == MpActorType.USER }.characterId)
    }

    private fun createViewModel(
        host: MiniPhoneHost,
        threadId: String = GROUP_THREAD_ID,
        pause: suspend () -> Unit = {},
    ): MiniPhoneChatViewModel {
        var clock = NOW_BASE
        return MiniPhoneChatViewModel(
            threadId = threadId,
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
            imageStore = MpImageStore(directory = File(System.getProperty("java.io.tmpdir"), "mp-group-images"), openStream = { null }),
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
            pauseBetweenResponders = pause,
        )
    }

    private suspend fun seedGroup(memberIds: List<String> = listOf("c1", "c2"), title: String = "阿远、小满") {
        db.threadDao().upsert(
            MpThreadEntity(
                id = GROUP_THREAD_ID,
                characterId = memberIds.first(),
                lastMessageAt = NOW_BASE,
                isGroup = true,
                title = title,
            ),
        )
        db.threadMemberDao().upsertAll(
            memberIds.mapIndexed { index, characterId ->
                MpThreadMemberEntity(threadId = GROUP_THREAD_ID, characterId = characterId, joinedAt = index.toLong())
            },
        )
    }

    private suspend fun storedMessages(): List<MpMessageEntity> = db.messageDao().observeMessages(GROUP_THREAD_ID).first()

    private suspend fun awaitState(viewModel: MiniPhoneChatViewModel, predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first(predicate) }

    /** 等宿主被叫到第 [count] 次:落库是异步的,发完消息那一瞬还没开始生成。 */
    private suspend fun awaitCalls(host: CastHost, count: Int) = withTimeout(TIMEOUT_MS) {
        while (host.calls.size < count) delay(POLL_MS)
    }

    /** 等重复提交被认出来:第二条用户消息直接落到「已发出」,没有自己的应答轮。 */
    private suspend fun awaitDuplicateSettled() = withTimeout(TIMEOUT_MS) {
        while (true) {
            val mine = storedMessages().filter { it.sender == MpActorType.USER }.sortedBy { it.createdAt }
            if (mine.size >= 2 && mine.last().status == MpMessageStatus.SENT) return@withTimeout
            delay(POLL_MS)
        }
    }

    /**
     * 等成员名单到位再发消息。
     *
     * 成员表与角色名单是两条独立的流:名字还没到时点名匹配不出来,先等它们都到齐,
     * 下面那些断言才说明的是选人规则,而不是「碰上了哪一秒的状态」。
     */
    private suspend fun readyGroup(viewModel: MiniPhoneChatViewModel, size: Int): ChatUiState = withTimeout(TIMEOUT_MS) {
        viewModel.uiState.first { state ->
            state.members.size == size && state.members.none { it.name == UNKNOWN_CHARACTER_NAME }
        }
    }

    private fun nameOf(characterId: String): String = CAST.first { it.id == characterId }.name

    private companion object {
        const val GROUP_THREAD_ID: String = "g1"
        const val NOW_BASE: Long = 10_000_000L
        const val TIMEOUT_MS: Long = 5_000L
        const val POLL_MS: Long = 10L

        val CAST: List<MpCharacter> = listOf(
            MpCharacter(id = "c1", name = "阿远"),
            MpCharacter(id = "c2", name = "小满"),
            MpCharacter(id = "c3", name = "阿吾"),
            MpCharacter(id = "c4", name = "阿岑"),
            MpCharacter(id = "c5", name = "阿迟"),
        )
        val FIVE_MEMBER_IDS: List<String> = CAST.map { it.id }
    }
}

/**
 * 会记事的宿主:谁被叫了、看到什么历史、第几次被叫时库里已经有多少条消息。
 *
 * [gate] 用来把一轮生成卡在半路:测试要在这时候看界面状态、或者再提交一条消息。
 */
private class CastHost(
    private val answerOf: (String) -> String? = { "好" },
    private val gate: CompletableDeferred<Unit>? = null,
    private val onCall: suspend (String, List<MpChatTurn>) -> Unit = { _, _ -> },
) : MiniPhoneHost {

    val calls: MutableList<String> = mutableListOf()

    val histories: MutableMap<String, List<MpChatTurn>> = mutableMapOf()

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(CAST)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        calls += characterId
        histories[characterId] = history
        onCall(characterId, history)
        gate?.await()
        val answer = answerOf(characterId)
        emit(if (answer == null) MpReplyEvent.Failed else MpReplyEvent.Text(answer))
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false

    private companion object {
        val CAST: List<MpCharacter> = listOf(
            MpCharacter(id = "c1", name = "阿远"),
            MpCharacter(id = "c2", name = "小满"),
            MpCharacter(id = "c3", name = "阿吾"),
            MpCharacter(id = "c4", name = "阿岑"),
            MpCharacter(id = "c5", name = "阿迟"),
        )
    }
}
