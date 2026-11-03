package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
 * 发送控制器的四条边界（细化路线 P2）。
 *
 * 覆盖的都是"错了直接伤到用户"的分支：
 *  1. 第一轮未回时重复提交同一句 —— 只落库，不再问一次角色（否则连点两下会收到两条相同回复）；
 *  2. 群里没人可接 —— 定稿成「已发出」，不能永远挂着「发送中」；
 *  3. 页面正在销毁（队列已关）—— 标成「发送失败」，界面才能指着它重发；
 *  4. 已认领过的一轮 —— `resend` 不能再跑一遍。
 *
 * 不套 ViewModel 那套 `GatedHost`：它只为"挡住第一次请求"一个场景设计，
 * 第二句 `send` 之后什么都等不到。
 *
 * 作用域配方是踩过坑才定下来的（上一轮四条全红，其中一条报 `UncaughtExceptionsBeforeTest`）：
 * **不能用自带虚拟时间的 TestDispatcher** —— 落库走 Room 的 `withContext(Dispatchers.IO)`，
 * 恢复要送回作用域自己的队列，而 `runCurrent()` 排的是 `runTest` 那条队列，
 * 于是永远不落库。这里用 `Dispatchers.Unconfined + Job + CoroutineExceptionHandler`：
 * 真实派发、异常有地方落，`settleUntil` 轮询代替靠虚拟时间推进。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatSendControllerTest {

    private companion object {
        const val THREAD_ID = "thread-1"
        const val CHARACTER_ID = "char-1"
        const val PEER = "小美"
        const val SETTLE_TIMEOUT_MS = 3_000L
    }

    private lateinit var db: MiniPhoneDb
    private var clock = 1_700_000_000_000L
    private var idCounter = 0
    private val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + Job() + CoroutineExceptionHandler { _, error -> errors += error },
    )

    @Before
    fun seedThread() = runBlocking {
        db = newInMemoryMiniPhoneDb()
        db.threadDao().upsert(
            MpThreadEntity(
                id = THREAD_ID,
                characterId = CHARACTER_ID,
                unreadCount = 0,
                lastMessageAt = clock,
            ),
        )
    }

    @After
    fun closeDb() {
        scope.coroutineContext[Job]?.cancel()
        db.close()
    }

    /** 计调用次数的宿主；`gate` 非空时第一次应答挂住不返回。 */
    private class RecordingHost(private val gate: CompletableDeferred<Unit>? = null) : MiniPhoneHost {
        var respondCalls = 0
            private set

        override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(emptyList())

        override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "我"))

        override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
            respondCalls += 1
            gate?.await()
            emit(MpReplyEvent.Text("好的"))
        }

        override suspend fun composeMoment(characterId: String, hint: String?): String? = null

        override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

        override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

        override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

        override suspend fun supportsVoiceTranscription(): Boolean = false

        override fun appVersionName(): String = "test"
    }

    private class Box(
        val state: MutableStateFlow<ChatUiState>,
        val controller: ChatSendController,
    )

    private fun box(
        host: MiniPhoneHost,
        group: Boolean = false,
        groupCandidates: suspend () -> List<GroupResponderCandidate> = { emptyList() },
    ): Box {
        val state = MutableStateFlow(
            ChatUiState(
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                peerName = PEER,
                isGroup = group,
            ),
        )
        val writer = ChatMessageWriter(
            threadId = THREAD_ID,
            messageDao = db.messageDao(),
            threadDao = db.threadDao(),
            now = { ++clock },
            newId = { "m${++idCounter}" },
            // 控制器判「重复提交」看的就是这份状态，所以落库与状态变化都要同步进来
            onStored = { ui -> state.value = state.value.copy(messages = state.value.messages + ui) },
            onStatusChanged = { id, status ->
                val current = state.value
                state.value = current.copy(
                    messages = current.messages.map { if (it.id == id) it.copy(status = status) else it },
                )
            },
        )
        val media = ChatMediaWriter(
            imageStore = MpImageStore(
                directory = File(System.getProperty("java.io.tmpdir"), "mp-controller-images"),
                openStream = { null },
            ),
            writer = writer,
            threadId = THREAD_ID,
        )
        return Box(
            state,
            ChatSendController(
                threadId = THREAD_ID,
                writer = writer,
                threadDao = db.threadDao(),
                host = host,
                media = media,
                state = state,
                scope = scope,
                pauseBetweenResponders = { },
                groupCandidates = groupCandidates,
            ),
        )
    }

    /** 等真实线程把链跑完；到点还不满足就直接用后面的断言报出实际状态。 */
    private fun settleUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun assertNoSwallowedErrors() {
        assertTrue(
            "作用域里有被吞的异常：" + errors.map { "${it.javaClass.name}: ${it.message}" },
            errors.isEmpty(),
        )
    }

    @Test
    fun `重复提交的第二句只落库，不再问一次角色`() {
        val gate = CompletableDeferred<Unit>()
        val host = RecordingHost(gate)
        val box = box(host)
        box.controller.start()

        box.controller.send("在吗")
        settleUntil { host.respondCalls == 1 }
        box.controller.send("在吗")
        settleUntil { box.state.value.messages.size == 2 }

        val messages = box.state.value.messages
        assertEquals("两条都该在（用户敲过的字不能凭空消失）", 2, messages.size)
        assertEquals("第一轮还没回，只该问过一次角色", 1, host.respondCalls)
        assertEquals(MpMessageStatus.SENDING, messages.first().status)
        assertEquals(
            "重复的那句要立刻定稿，不该挂着「发送中」",
            MpMessageStatus.SENT,
            messages.last().status,
        )
        assertNoSwallowedErrors()
    }

    @Test
    fun `群里没人可接时消息定稿而不是卡住`() {
        val host = RecordingHost()
        // 群状态里没有成员，库里也补不到候选 → 没人可接
        val box = box(host, group = true, groupCandidates = { emptyList() })
        box.controller.start()

        box.controller.send("随便说点什么")
        settleUntil { box.state.value.messages.firstOrNull()?.status == MpMessageStatus.SENT }

        assertEquals("没人接就不该去问角色", 0, host.respondCalls)
        val stored = box.state.value.messages
        assertEquals(1, stored.size)
        assertEquals("不能永远停在「发送中」", MpMessageStatus.SENT, stored.single().status)
        assertEquals(MpActorType.USER, stored.single().sender)
        assertNoSwallowedErrors()
    }

    @Test
    fun `页面正在销毁时那一条标成发送失败`() {
        val host = RecordingHost()
        val box = box(host)
        box.controller.close()

        box.controller.send("在吗")
        settleUntil { box.state.value.messages.firstOrNull()?.status == MpMessageStatus.FAILED }

        assertEquals("队列都关了就不该再请求角色", 0, host.respondCalls)
        assertEquals(
            "必须落到「发送失败」，界面上才能指着它重发",
            MpMessageStatus.FAILED,
            box.state.value.messages.single().status,
        )
        assertNoSwallowedErrors()
    }

    @Test
    fun `已经认领过的一轮不会因为重发再跑一次`() {
        val gate = CompletableDeferred<Unit>()
        val host = RecordingHost(gate)
        val box = box(host)
        box.controller.start()

        box.controller.send("在吗")
        settleUntil { host.respondCalls == 1 }
        val first = box.state.value.messages.first()
        assertEquals(1, host.respondCalls)

        // 真实路径是"网络失败 → 用户点重发"；本条测的是认领集合，
        // 失败状态怎么产生由上一条管，不在这里重复造。
        val current = box.state.value
        box.state.value = current.copy(
            messages = current.messages.map {
                if (it.id == first.id) it.copy(status = MpMessageStatus.FAILED) else it
            },
        )

        box.controller.resend(first.id)
        box.controller.resend(first.id)
        // 认领还挂在上一轮上（gate 没放，回复没结算），所以两次重发都不该有下文：
        // 既不再问一次角色，也不该把那条从「发送失败」改回「发送中」看着像在工作。
        Thread.sleep(200)

        assertEquals("已认领的一轮不能被 resend 再跑一次", 1, host.respondCalls)
        assertEquals(
            "状态不能被改回「发送中」，否则用户会以为它在重发",
            MpMessageStatus.FAILED,
            box.state.value.messages.first().status,
        )
        assertNoSwallowedErrors()
    }
}
