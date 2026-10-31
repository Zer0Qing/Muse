package io.zer0.muse.miniphone.ui.chat

import android.net.Uri
import io.zer0.muse.miniphone.api.MiniPhoneHost
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 发图回路:多选各落一条、顺序不乱、不触发角色回复、坏图跳过。
 *
 * 用真 Room 与真落盘目录,只有输入流是假的 —— 「库里到底有没有这三条、路径指的
 * 是不是刚写下的那份文件」只能这么验。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatImageSendTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var directory: File
    private var idCounter: Int = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = newInMemoryMiniPhoneDb()
        directory = Files.createTempDirectory("mp-chat-images").toFile()
        idCounter = 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        directory.deleteRecursively()
    }

    @Test
    fun threeImagesBecomeThreeMessagesInSelectionOrderWithoutAskingTheCharacter() = runBlocking {
        seedThread()
        val host = RecordingHost()
        val viewModel = createViewModel(host)

        viewModel.sendImages(listOf(uriOf("a"), uriOf("b"), uriOf("c")))

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 3 }
        assertEquals("三张图各一条", 3, state.messages.size)
        assertTrue(
            "都是用户发的图片",
            state.messages.all { it.sender == MpActorType.USER && it.type == MpMessageType.IMAGE },
        )
        assertEquals("顺序与选择一致", listOf("a", "b", "c"), storedPayloads(state))
        assertEquals("三条各存一份文件", 3, directory.listFiles().orEmpty().size)
        assertEquals("发图不请求角色回复", 0, host.callCount)
        assertFalse("没有生成中的提示", state.isGenerating)
    }

    @Test
    fun unreadableImageIsSkippedAndTheOthersStillGoThrough() = runBlocking {
        seedThread()
        val viewModel = createViewModel(RecordingHost())

        viewModel.sendImages(listOf(uriOf("ok"), uriOf(BAD_ID)))

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 1 }
        assertEquals("坏的那张直接跳过", listOf("ok"), storedPayloads(state))
    }

    @Test
    fun allImagesUnreadableLeavesTheChatUntouched() = runBlocking {
        seedThread()
        val viewModel = createViewModel(RecordingHost())

        viewModel.sendImages(listOf(uriOf(BAD_ID)))

        // 给它足够时间跑完一轮:坏图不应留下任何痕迹
        delay(NO_CHANGE_WAIT_MS)
        val state = viewModel.uiState.value
        assertEquals(0, state.messages.size)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun characterImageCanBeSeededWithoutGenerating() = runBlocking {
        seedThread()
        val host = RecordingHost()
        val viewModel = createViewModel(host)
        val file = File(directory, "from-character.jpg").apply { writeBytes("角色发的图".toByteArray()) }

        viewModel.insertCharacterImage(THREAD_ID, CHARACTER_ID, file.absolutePath)

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 1 }
        val message = state.messages.single()
        assertEquals(MpActorType.CHARACTER, message.sender)
        assertEquals(MpMessageType.IMAGE, message.type)
        assertEquals(file.absolutePath, message.mediaUri)
        assertEquals("种子不发请求", 0, host.callCount)
        assertEquals(file.absolutePath, db.messageDao().getMessage(message.id)?.mediaUri)
    }

    @Test
    fun profileFillsTheOwnSideNameAndAvatar() = runBlocking {
        seedThread()
        val withAvatar = createViewModel(RecordingHost(avatarUri = AVATAR_URI))

        val state = awaitChat(withAvatar) { chat -> chat.selfAvatarUri != null }
        assertEquals("子奇", state.selfName)
        assertEquals(AVATAR_URI, state.selfAvatarUri)

        // 空白头像当成没设置:界面回落到首字圆章
        val blankAvatar = createViewModel(RecordingHost(avatarUri = " "))
        val blank = awaitChat(blankAvatar) { chat -> chat.selfName.isNotEmpty() }
        assertNull(blank.selfAvatarUri)
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
            imageStore = MpImageStore(directory = directory, openStream = ::openStream),
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

    /** 选择器给出的 uri 内容就是它的最后一段:落盘文件的字节能反证选择顺序。 */
    private fun openStream(uri: Uri): ByteArrayInputStream {
        val id = uri.lastPathSegment.orEmpty()
        if (id == BAD_ID) throw IOException("原件已被删除")
        return ByteArrayInputStream(id.toByteArray())
    }

    private suspend fun seedThread() {
        db.threadDao().upsert(MpThreadEntity(id = THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = MINUTE_MS))
    }

    private fun storedPayloads(state: ChatUiState): List<String> = state.messages.map { message ->
        message.mediaUri?.let { path -> File(path).readBytes().decodeToString() }.orEmpty()
    }

    private suspend fun awaitChat(viewModel: MiniPhoneChatViewModel, predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first(predicate) }

    private fun uriOf(id: String): Uri = Uri.parse("content://picker/$id")

    private companion object {
        const val THREAD_ID: String = "t1"
        const val CHARACTER_ID: String = "c1"
        const val BAD_ID: String = "bad"
        const val AVATAR_URI: String = "content://profile/avatar"
        const val TIMEOUT_MS: Long = 5_000L
        const val NO_CHANGE_WAIT_MS: Long = 200L
        const val MINUTE_MS: Long = 60_000L
        const val NOW_BASE: Long = 10_000_000L
    }
}

/** 只记调用次数的宿主:发图不该走到这里。 */
private class RecordingHost(
    private val avatarUri: String? = null,
) : MiniPhoneHost {

    @Volatile
    var callCount: Int = 0
        private set

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(listOf(MpCharacter(id = "c1", name = "阿离")))

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇", avatarUri = avatarUri))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        callCount += 1
        emit(MpReplyEvent.Failed)
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
