package io.zer0.muse.miniphone.data.call

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
 * 通话会话控制器：阶段、时长、降级、并发保护与落库。
 *
 * 这些边界只有钉死时间才测得准，所以时钟与延迟全部注入。
 * 每个用例都用 backgroundScope：一通还没挂断的通话必须继续计时，
 * 挂在测试作用域上会让 runTest 等一个永远不会结束的计时器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpCallControllerTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var host: CallHost

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        host = CallHost()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun controller(scope: CoroutineScope, clock: FakeClock = FakeClock()) = MpCallController(
        host = host,
        store = MpCallStore(db.callSessionDao()),
        scope = scope,
        session = MpCallSession(now = clock::read, newId = { "call-1" }),
        now = clock::read,
        ringDelayMs = 0L,
        // 计时节拍给真实的一秒：tickMs 给 0 会让「计时循环」在虚拟时间里无限自转，
        // runCurrent() 因此永远回不来。需要走一个节拍时用 advanceTimeBy 明说。
        tickMs = 1_000L,
    )

    @Test
    fun `拨号会推进到接通并显示时长`() = runTest {
        val clock = FakeClock()
        val scope = backgroundScope
        val c = controller(scope, clock)
        c.dial("t1", "c1", MpCallKind.VOICE, cameraAllowed = true)
        runCurrent()
        assertEquals(MpCallPhase.CONNECTED, c.state.value?.phase)
        clock.advance(5_000L)
        c.runRound(listOf(MpChatTurn(sender = MpActorType.USER, content = "喂")))
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(5_000L, c.elapsedMs.value)
    }

    @Test
    fun `挂断落下通话记录且时长取实际通话时间`() = runTest {
        val clock = FakeClock()
        val scope = backgroundScope
        val c = controller(scope, clock)
        c.dial("t1", "c1", MpCallKind.VOICE, cameraAllowed = true)
        runCurrent()
        clock.advance(9_000L)
        assertEquals(9_000L, c.hangUp())
        runCurrent()
        val rows = db.callSessionDao().observeHistory(10).first()
        assertEquals(1, rows.size)
        assertEquals("call-1", rows.single().id)
        assertEquals(9_000L, rows.single().durationMs)
        assertEquals("voice", rows.single().kind)
    }

    @Test
    fun `还没接通就挂断记为未接通而不是通话零秒`() = runTest {
        val scope = backgroundScope
        val c = controller(scope)
        c.dial("t1", "c1", MpCallKind.VOICE, cameraAllowed = true)
        // 没有 runCurrent：拨号协程还没被调度，阶段仍停在 DIALING，等价于「对方一直没接」
        assertEquals(MpCallPhase.DIALING, c.state.value?.phase)
        assertEquals(0L, c.hangUp())
        runCurrent()
        val rows = db.callSessionDao().observeHistory(10).first()
        assertEquals(1, rows.size)
        assertEquals("未接通要记 0", 0L, rows.single().durationMs)
        assertTrue(c.state.value?.noAnswer == true)
    }

    @Test
    fun `视频拿不到相机权限就退回语音并说明原因`() = runTest {
        val scope = backgroundScope
        val c = controller(scope)
        c.dial("t1", "c1", MpCallKind.VIDEO, cameraAllowed = false)
        runCurrent()
        assertEquals(MpCallKind.VOICE, c.state.value?.kind)
        assertEquals(MpCallRejectReason.CAMERA_DENIED, c.state.value?.rejectReason)
    }

    @Test
    fun `已经有一通未结束时再拨号不会攒出第二通`() = runTest {
        val scope = backgroundScope
        val c = controller(scope)
        c.dial("t1", "c1", MpCallKind.VOICE, cameraAllowed = true)
        runCurrent()
        val firstCallId = c.state.value?.callId
        c.dial("t2", "c2", MpCallKind.VOICE, cameraAllowed = true)
        runCurrent()
        assertEquals("并发拨号必须被挡住", firstCallId, c.state.value?.callId)
    }

    @Test
    fun `生成失败标失败且不写一条从没发生的通话记录`() = runTest {
        host.failNext = true
        val scope = backgroundScope
        val c = controller(scope)
        c.dial("t1", "c1", MpCallKind.VOICE, cameraAllowed = true)
        runCurrent()
        c.runRound(listOf(MpChatTurn(sender = MpActorType.USER, content = "喂")))
        runCurrent()
        assertEquals(MpCallPhase.FAILED, c.state.value?.phase)
        c.hangUp()
        runCurrent()
        assertEquals("失败的通话不该进历史记录", 0, db.callSessionDao().observeHistory(10).first().size)
    }

    @Test
    fun `用户说的话也进记录`() = runTest {
        val scope = backgroundScope
        val c = controller(scope)
        c.dial("t1", "c1", MpCallKind.VOICE, cameraAllowed = true)
        runCurrent()
        c.speak("听到吗")
        runCurrent()
        assertEquals(listOf(MpActorType.USER), c.state.value?.turns?.map { it.speaker })
    }

    @Test
    fun `挂断后到达的事件不再写进会话`() = runTest {
        val scope = backgroundScope
        val c = controller(scope)
        c.dial("t1", "c1", MpCallKind.VOICE, cameraAllowed = true)
        runCurrent()
        val gate = kotlinx.coroutines.channels.Channel<MpReplyEvent>(capacity = 2)
        host.gate = gate
        c.runRound(listOf(MpChatTurn(sender = MpActorType.USER, content = "喂")))
        runCurrent()
        c.hangUp()
        runCurrent()
        gate.trySend(MpReplyEvent.Text("迟到的半句"))
        runCurrent()
        val turns = c.state.value?.turns.orEmpty()
        assertFalse("结束后不该再追加内容", turns.any { it.text.contains("迟到的半句") })
    }

    @Test
    fun `离开页面时未结束的通话会被收尾`() = runTest {
        val scope = backgroundScope
        val c = controller(scope)
        c.dial("t1", "c1", MpCallKind.VOICE, cameraAllowed = true)
        runCurrent()
        c.dispose()
        runCurrent()
        assertEquals(MpCallPhase.ENDED, c.state.value?.phase)
        assertEquals(1, db.callSessionDao().observeHistory(10).first().size)
    }
}

/** 只实现通话用得到的两条：respond 与默认空实现的其余方法。 */
private class CallHost : MiniPhoneHost {
    var failNext = false
    var gate: kotlinx.coroutines.channels.Channel<MpReplyEvent>? = null

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(emptyList())

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = ""))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> {
        gate?.let { return it.receiveAsFlow() }
        return if (failNext) flowOf(MpReplyEvent.Failed) else flowOf(MpReplyEvent.Text("在的，你说"))
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** 手动推进的时间源。 */
private class FakeClock {
    private var value = 1_000L

    fun read(): Long = value

    fun advance(ms: Long) {
        value += ms
    }
}
