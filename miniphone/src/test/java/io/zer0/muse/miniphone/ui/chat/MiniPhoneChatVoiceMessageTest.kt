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
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 一轮回复里带语音时,消息怎么落库、怎么排。
 *
 * 宿主给的事件顺序就是消息顺序:一段文本一条,一段语音一条。语音那条的正文只存
 * 会话列表用的预览文案,音频引用与时长各进自己的字段。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatVoiceMessageTest {

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
    fun textAndVoiceSegmentsLandInEventOrder() = runBlocking {
        seedThread()
        val viewModel = createViewModel(
            ScriptHost(
                listOf(
                    MpReplyEvent.Text("先说话"),
                    MpReplyEvent.Voice(VOICE_URI, DURATION_MS),
                    MpReplyEvent.Text("再说话"),
                ),
            ),
        )

        viewModel.send("在吗")

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 4 && !chat.isGenerating }
        assertEquals(
            listOf(MpMessageType.TEXT, MpMessageType.TEXT, MpMessageType.VOICE, MpMessageType.TEXT),
            state.messages.map { it.type },
        )
        val voice = state.messages[2]
        assertEquals(MpActorType.CHARACTER, voice.sender)
        assertEquals("[语音]", voice.content)
        assertEquals(VOICE_URI, voice.mediaUri)
        assertEquals(DURATION_MS, voice.voiceDurationMs)
        assertTrue("语音消息走语音渲染分支", voice.isVoice)
    }

    @Test
    fun storedVoiceRowKeepsTheAudioReferenceAndDuration() = runBlocking {
        seedThread()
        val viewModel = createViewModel(ScriptHost(listOf(MpReplyEvent.Voice(VOICE_URI, DURATION_MS))))

        viewModel.send("在吗")
        awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }

        val stored = db.messageDao().observeMessages(THREAD_ID).first().last()
        assertEquals(MpMessageType.VOICE, stored.type)
        assertEquals(VOICE_URI, stored.mediaUri)
        assertEquals(DURATION_MS, stored.voiceDurationMs)
        assertEquals("[语音]", stored.content)
    }

    @Test
    fun activitiesBeforeTheVoiceDoNotBecomeMessages() = runBlocking {
        seedThread()
        val viewModel = createViewModel(
            ScriptHost(
                listOf(
                    MpReplyEvent.Activity(MpActivityKind.SEARCHING),
                    MpReplyEvent.Voice(VOICE_URI, DURATION_MS),
                ),
            ),
        )

        viewModel.send("在吗")

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        assertEquals("活动提示不进消息列表", listOf(MpMessageType.TEXT, MpMessageType.VOICE), state.messages.map { it.type })
        assertEquals("结束后收起活动提示", null, state.activity)
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
            imageStore = MpImageStore(
                directory = File(System.getProperty("java.io.tmpdir"), "mp-chat-voices"),
                openStream = { null },
            ),
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

    private suspend fun seedThread() {
        db.threadDao().upsert(
            MpThreadEntity(
                id = THREAD_ID,
                characterId = CHARACTER_ID,
                unreadCount = 0,
                lastMessageAt = 0L,
            ),
        )
    }

    private suspend fun awaitChat(viewModel: MiniPhoneChatViewModel, predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first(predicate) }

    private companion object {
        const val THREAD_ID: String = "t1"
        const val CHARACTER_ID: String = "c1"
        const val TIMEOUT_MS: Long = 5_000L
        const val NOW_BASE: Long = 10_000_000L
        const val VOICE_URI: String = "file:///data/voice_messages/abc.wav"
        const val DURATION_MS: Long = 7_000L
    }
}

/** 按给定顺序吐事件的宿主:用来钉住「事件顺序 = 消息顺序」。 */
private class ScriptHost(
    private val events: List<MpReplyEvent>,
) : MiniPhoneHost {

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(listOf(MpCharacter(id = "c1", name = "阿离")))

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        events.forEach { emit(it) }
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
