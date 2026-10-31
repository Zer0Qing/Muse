package io.zer0.muse.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.MediaGenKind
import io.zer0.muse.tools.MediaGenResult
import io.zer0.muse.tools.MediaGenSlot
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/**
 * 同一回合连发多条语音时的消息归属护栏。
 *
 * 消息上的语音只占一个字段,第二次写入会把前一条覆盖掉 —— 所以本测试盯的是
 * [ChatMediaGenHost] 的分流:第一条挂在本轮助手消息上,第二条及以后各起一条新消息,
 * 且顺序等于投递顺序。图片/视频路径必须保持"只产生一条消息"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatMediaGenHostVoiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `同一回合三条语音落在三条助手消息上且按投递顺序排列`() = runBlocking {
        val fixture = displayedFixture()
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.VOICE))

        deliverThree(slot)

        val messages = fixture.accessor.messagesSnapshot
        assertEquals("锚定消息 + 两条新消息", 3, messages.size)
        assertEquals("第一条仍挂在锚定消息上", ANCHOR_ID, messages.first().id)
        assertTrue("三条都是助手消息", messages.all { it.role == MessageRole.ASSISTANT })
        assertEquals("语音按投递顺序排列且互不覆盖", listOf(VOICE_1, VOICE_2, VOICE_3), messages.map { it.voiceUri })
        assertEquals("时长跟各自语音走", listOf(1000L, 2000L, 3000L), messages.map { it.voiceDurationMs })
    }

    @Test
    fun `第一条语音只挂在锚定消息上且不额外多出消息`() = runBlocking {
        val fixture = displayedFixture()
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.VOICE))

        deliverThree(slot)

        val messages = fixture.accessor.messagesSnapshot
        assertEquals(VOICE_1, messages.first().voiceUri)
        assertEquals("只新增两条消息", 2, messages.count { it.id != ANCHOR_ID })
        val placeholder = context.getString(R.string.err_chat_voice_generated)
        assertEquals("新消息沿用同一句占位文案", placeholder, messages[1].content)
        assertEquals(placeholder, messages[2].content)
        // 落盘与收尾登记都要覆盖到新消息,否则重启后新语音消息消失
        assertEquals(3, fixture.persisted.toSet().size)
        assertEquals(3, fixture.registered.toSet().size)
    }

    @Test
    fun `锚定消息已有正文时正文不被覆盖`() = runBlocking {
        val fixture = displayedFixture(anchorContent = EXISTING_TEXT)
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.VOICE))

        deliverThree(slot)

        val messages = fixture.accessor.messagesSnapshot
        assertTrue("正文被占位文案覆盖了: ${messages.first().content}", messages.first().content.contains(EXISTING_TEXT))
        assertEquals(VOICE_1, messages.first().voiceUri)
        assertEquals("后两条各起新消息", 2, messages.count { it.id != ANCHOR_ID })
        assertEquals(listOf(VOICE_2, VOICE_3), messages.drop(1).map { it.voiceUri })
    }

    @Test
    fun `投递一张图片仍然只产生一条消息`() = runBlocking {
        val repository = mockk<SessionRepository>(relaxed = true)
        coEvery { repository.toPersistableImageUrls(any(), any()) } returns listOf(PERSISTED_IMAGE)
        val fixture = displayedFixture(repository = repository)
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.IMAGE))

        slot.report(MediaGenResult.Success(imageUrls = listOf(RAW_IMAGE)))

        val messages = fixture.accessor.messagesSnapshot
        assertEquals("图片路径保持一图一条消息", 1, messages.size)
        assertEquals(ANCHOR_ID, messages.single().id)
        assertEquals(listOf(PERSISTED_IMAGE), messages.single().imageUrls)
    }

    @Test
    fun `会话不在显示时三条语音插库并通过 attachMediaToMessage 挂到不同消息上`() = runBlocking {
        val repository = VoiceRepositoryFake()
        val fixture = displayedFixture(displayed = false, repository = repository.asMock())
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.VOICE))

        deliverThree(slot)

        val targets = repository.attachTargets
        assertEquals("三条语音各挂一次", 3, targets.size)
        assertEquals("第一条挂锚定消息", ANCHOR_ID, targets.first())
        assertEquals("三条目标消息互不相同", 3, targets.toSet().size)
        assertNotEquals("后两条必须是新消息", ANCHOR_ID, targets[1])
        assertEquals("新消息两条", 2, repository.appended.size)
        assertEquals("新消息按投递顺序插入", listOf(targets[1], targets[2]), repository.appended.map { it.id })
        assertEquals("第一条语音落在锚定消息上", VOICE_1, repository.message(ANCHOR_ID)?.voiceUri)
        assertEquals(
            "语音按投递顺序落库",
            listOf(VOICE_2, VOICE_3),
            listOf(targets[1], targets[2]).map { repository.message(it)?.voiceUri },
        )
        assertEquals(
            "新消息内容为同一句占位文案",
            context.getString(R.string.err_chat_voice_generated),
            repository.message(targets[1])?.content,
        )
        assertEquals("非显示分支不新增内存消息", 1, fixture.accessor.messagesSnapshot.size)
        assertNull("内存里的锚定消息没被写语音", fixture.accessor.messagesSnapshot.single().voiceUri)
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private class Fixture(
        val host: ChatMediaGenHost,
        val accessor: FakeAccessor,
        val persisted: MutableList<Uuid>,
        val registered: MutableList<Uuid>,
    )

    private fun displayedFixture(
        displayed: Boolean = true,
        anchorContent: String = "",
        repository: SessionRepository = mockk(relaxed = true),
    ): Fixture {
        val accessor = FakeAccessor(if (displayed) SESSION_ID else OTHER_SESSION_ID)
        accessor.seed(listOf(anchor(anchorContent)))
        val persisted = mutableListOf<Uuid>()
        val registered = mutableListOf<Uuid>()
        val coordinator = ChatStreamCoordinator(
            accessor = accessor,
            sessionRepository = repository,
            memoryTicker = mockk(relaxed = true),
            settings = mockk(relaxed = true),
            appContext = context,
            notificationManager = mockk(relaxed = true),
            assistantRepository = mockk(relaxed = true),
            visionBridge = mockk(relaxed = true),
            toolRegistry = mockk(relaxed = true),
            skillRepository = mockk(relaxed = true),
            idListJson = Json,
            lorebookRepository = mockk(relaxed = true),
            promptInjectionRepository = mockk(relaxed = true),
            transformerPipeline = mockk(relaxed = true),
            checkpointReader = mockk(relaxed = true),
        )
        val host = ChatMediaGenHost(
            accessor = accessor,
            sessionRepository = repository,
            streamCoordinator = coordinator,
            appContext = context,
            toolAssistantIdProvider = { ANCHOR_ID },
            toolSessionIdProvider = { SESSION_ID },
            generationTokenProvider = { GENERATION_TOKEN },
            updateAssistant = { id, content -> coordinator.appendMediaToAssistant(id = id, content = content) },
            persistMediaMessage = { _, id -> persisted += id },
            registerMediaMessage = { id -> registered += id },
        )
        return Fixture(host, accessor, persisted, registered)
    }

    private fun anchor(content: String) = UIMessage(
        id = ANCHOR_ID,
        role = MessageRole.ASSISTANT,
        content = content,
    )

    private suspend fun deliverThree(slot: MediaGenSlot) {
        slot.report(MediaGenResult.Success(voiceUri = VOICE_1, voiceDurationMs = 1000))
        slot.report(MediaGenResult.Success(voiceUri = VOICE_2, voiceDurationMs = 2000))
        slot.report(MediaGenResult.Success(voiceUri = VOICE_3, voiceDurationMs = 3000))
    }

    /** 内存态 [ChatStateAccessor]:消息列表 + 当前显示会话。 */
    private class FakeAccessor(displayedSessionId: String?) : ChatStateAccessor {
        private val state = MutableStateFlow(
            ChatUiState(sessionState = ChatSessionState(currentSessionId = displayedSessionId)),
        )
        private val messages = MutableStateFlow<List<UIMessage>>(emptyList())

        override val snapshot: ChatUiState get() = state.value

        override fun update(transform: (ChatUiState) -> ChatUiState) = state.update(transform)

        override val messagesSnapshot: List<UIMessage> get() = messages.value

        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = messages.update(transform)

        override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

        fun seed(list: List<UIMessage>) {
            messages.value = list
        }
    }

    /** 有状态的会话仓储替身:锚定消息随 attachMediaToMessage 记住自己的语音。 */
    private class VoiceRepositoryFake {

        private val stored = linkedMapOf(ANCHOR_ID.toString() to anchor(""))
        val attachTargets = mutableListOf<Uuid>()
        val appended = mutableListOf<UIMessage>()

        fun message(id: Uuid): UIMessage? = stored[id.toString()]

        fun asMock(): SessionRepository = mockk<SessionRepository>(relaxed = true).also { repository ->
            coEvery { repository.getMessageAsUiMessage(any()) } answers {
                stored[invocation.args[0] as String]
            }
            coEvery { repository.appendMessage(any(), any()) } answers {
                val message = invocation.args[1] as UIMessage
                stored[message.id.toString()] = message
                appended += message
                message.id.toString()
            }
            coEvery {
                repository.attachMediaToMessage(any(), any(), any(), any(), any(), any(), any())
            } answers {
                val args = invocation.args
                val messageId = args[1] as Uuid
                attachTargets += messageId
                stored[messageId.toString()] = stored.getValue(messageId.toString()).copy(
                    content = args[2] as String,
                    voiceUri = args[5] as String?,
                    voiceDurationMs = args[6] as Long,
                )
            }
        }

        private fun anchor(content: String) = UIMessage(
            id = ANCHOR_ID,
            role = MessageRole.ASSISTANT,
            content = content,
        )
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val OTHER_SESSION_ID = "session-2"
        const val GENERATION_TOKEN = 7L
        const val EXISTING_TEXT = "先说的话"
        const val VOICE_1 = "file:///voice/1.wav"
        const val VOICE_2 = "file:///voice/2.wav"
        const val VOICE_3 = "file:///voice/3.wav"
        const val RAW_IMAGE = "data:image/png;base64,AAAA"
        const val PERSISTED_IMAGE = "file:///images/1.png"

        val ANCHOR_ID: Uuid = Uuid.random()
    }
}
