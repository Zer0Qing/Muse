package io.zer0.muse.miniphone.data.chat.auto

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 主动私信的一轮完整流程:判定 → 找会话 → 宿主生成 → 落库 → 未读 +1 → 记时间。
 *
 * 库里跑真 Room(内存库),宿主与设置用替身:这样「真的写进去了」「失败时一条也没写」
 * 是断言而不是自证。判定本身在 [MpProactiveChatPlanTest] 里逐条钉过,
 * 这里关心的是判定之后做了什么。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpProactiveChatRunnerTest {

    private lateinit var db: MiniPhoneDb

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sendsOneCharacterMessageAndBumpsUnread() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        val host = FakeChatHost(content = COMPOSED_TEXT)
        val store = FakeChatStore(enabled = true)

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpProactiveChatOutcome.Sent(threadId = THREAD_ID, characterId = CHARACTER_ID), outcome)
        assertEquals(1, host.composeCalls)
        val messages = db.messageDao().pageMessages(THREAD_ID, limit = 10, offset = 0)
        assertEquals(1, messages.size)
        assertEquals(MpActorType.CHARACTER, messages[0].sender)
        assertEquals(MpMessageType.TEXT, messages[0].type)
        assertEquals(COMPOSED_TEXT, messages[0].content)
        assertEquals(NOW, messages[0].createdAt)
        // 未读 +1 与预览更新都要落到会话行上:列表里那一行看起来才像「刚来过消息」
        assertEquals(1, db.threadDao().unreadCount(THREAD_ID))
        assertEquals(COMPOSED_TEXT, db.threadDao().getThread(THREAD_ID)?.lastMessagePreview)
        assertEquals(NOW, store.markedAt)
    }

    @Test
    fun blankContentIsNotStoredAndDoesNotMarkTheTime() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        val store = FakeChatStore(enabled = true)

        val outcome = runner(host = FakeChatHost(content = "   "), store = store).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.NO_CONTENT), outcome)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
        assertEquals(0, db.threadDao().unreadCount(THREAD_ID))
        // 没落库就不记时间戳,否则下一轮会被自己的 4 小时间隔挡住
        assertNull(store.markedAt)
    }

    @Test
    fun noHistorySkipsWithoutCallingHost() = runBlocking {
        // 一条消息都没有的会话不算「能搭话」:角色凭空开口会像骚扰
        db.threadDao().upsert(thread(lastMessageAt = 0L))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.NO_HISTORY), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun disabledByDefaultSkipsEverything() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = false)).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.DISABLED), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun userJustTalkedSkipsWithoutCallingHost() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        // 用户 3 分钟前刚说过话:此刻插一句就是打断
        db.messageDao().insert(
            MpMessageEntity(
                id = "recent-user",
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                sender = MpActorType.USER,
                type = MpMessageType.TEXT,
                content = "我刚回来",
                createdAt = NOW - THREE_MINUTES_MS,
            ),
        )
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.USER_JUST_ACTIVE), outcome)
        assertEquals(0, host.composeCalls)
        // 只有那条用户消息,没有新写的
        assertEquals(1, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun threeHoursAfterLastProactiveChatSkipsAsTooSoon() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        val host = FakeChatHost(content = COMPOSED_TEXT)
        val store = FakeChatStore(enabled = true, lastProactiveAt = NOW - THREE_HOURS_MS)

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.TOO_SOON), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun picksTheMostRecentlyActiveThread() = runBlocking {
        db.threadDao().upsert(thread(id = "older", characterId = "c2", lastMessageAt = LAST_USER_AT))
        db.threadDao().upsert(thread(id = THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = LAST_USER_AT + 5_000L))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals(MpProactiveChatOutcome.Sent(threadId = THREAD_ID, characterId = CHARACTER_ID), outcome)
        assertEquals(CHARACTER_ID, host.lastCharacterId)
        assertEquals(0, db.messageDao().countInThread("older"))
    }

    @Test
    fun groupThreadsAreNeverPickedForAProactiveMessage() = runBlocking {
        // 群会话更新:也不能拿它当「找一个人开口」的地方
        db.threadDao().upsert(
            thread(id = GROUP_THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = LAST_USER_AT + 5_000L)
                .copy(isGroup = true, title = "阿远、小满"),
        )
        db.threadDao().upsert(thread(id = THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = LAST_USER_AT))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals("落到了私信上", MpProactiveChatOutcome.Sent(threadId = THREAD_ID, characterId = CHARACTER_ID), outcome)
        assertEquals(0, db.messageDao().countInThread(GROUP_THREAD_ID))
        assertEquals(1, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun aGroupWithoutADirectMessageSkipsInsteadOfTalkingInTheGroup() = runBlocking {
        db.threadDao().upsert(
            thread(lastMessageAt = LAST_USER_AT).copy(isGroup = true, title = "阿远、小满"),
        )
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals("只有群会话时就没有能单独搭话的人", MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.NO_HISTORY), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun sendsWithRecentChatContextAndTimeThenTheFocus() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        db.messageDao().insert(message(id = "m1", sender = MpActorType.USER, content = "今天加班到好晚", at = LAST_USER_AT))
        db.messageDao().insert(message(id = "m2", sender = MpActorType.CHARACTER, content = "到家了吗", at = LAST_USER_AT + 1L))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        runner(host = host, store = FakeChatStore(enabled = true)).run()

        val hint = host.lastHint.orEmpty()
        assertTrue("素材应带最近对话: $hint", hint.contains("用户：今天加班到好晚"))
        assertTrue("素材应带角色自己说过的话: $hint", hint.contains("我：到家了吗"))
        assertTrue("素材应带当前时间: $hint", hint.contains("现在是周二下午两点"))
        assertTrue("由头放在最后一句: $hint", hint.lines().last().startsWith("此刻的由头："))
    }

    private fun runner(host: MiniPhoneHost, store: MpMomentAutoStore): MpProactiveChatRunner = MpProactiveChatRunner(
        host = host,
        threadDao = db.threadDao(),
        messageDao = db.messageDao(),
        store = store,
        material = MpCharacterMaterial(
            threadDao = db.threadDao(),
            messageDao = db.messageDao(),
            momentDao = db.momentDao(),
            now = { NOW },
            zone = ZONE,
        ),
        now = { NOW },
        random = { 0.1f },
        zone = ZONE,
        newId = { MESSAGE_ID },
    )

    private fun message(id: String, sender: MpActorType, content: String, at: Long): MpMessageEntity = MpMessageEntity(
        id = id,
        threadId = THREAD_ID,
        characterId = CHARACTER_ID,
        sender = sender,
        type = MpMessageType.TEXT,
        content = content,
        createdAt = at,
    )

    private fun thread(id: String = THREAD_ID, characterId: String = CHARACTER_ID, lastMessageAt: Long): MpThreadEntity {
        return MpThreadEntity(
            id = id,
            characterId = characterId,
            lastMessagePreview = "在吗",
            lastMessageAt = lastMessageAt,
        )
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 白天 14:00:不在安静时段。 */
        val NOW: Long = LocalDateTime.of(2026, 5, 12, 14, 0).atZone(ZONE).toInstant().toEpochMilli()

        const val THREE_HOURS_MS: Long = 3L * 60L * 60L * 1000L

        const val THREE_MINUTES_MS: Long = 3L * 60L * 1000L

        const val LAST_USER_AT: Long = 1_000L

        const val THREAD_ID: String = "t1"

        const val GROUP_THREAD_ID: String = "g1"

        const val CHARACTER_ID: String = "c1"

        const val MESSAGE_ID: String = "proactive-1"

        const val COMPOSED_TEXT: String = "刚才路过你说的那家店，人还挺多。"
    }
}

/** 假宿主:生成结果固定,顺带记下被调了几次、调的是谁。 */
private class FakeChatHost(
    private val content: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "阿离")),
) : MiniPhoneHost {

    var composeCalls: Int = 0
        private set

    var lastCharacterId: String? = null
        private set

    var lastHint: String? = null
        private set

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? {
        composeCalls += 1
        lastCharacterId = characterId
        lastHint = hint
        return content
    }

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** 假设置:主动私信开关与上次时间的可变替身,写进来的值留在字段上供断言。 */
private class FakeChatStore(
    enabled: Boolean = false,
    lastProactiveAt: Long? = null,
) : MpMomentAutoStore {

    private val chatProactiveState = MutableStateFlow(enabled)

    private val momentState = MutableStateFlow(true)

    var markedAt: Long? = lastProactiveAt
        private set

    override val enabledFlow: Flow<Boolean> = momentState

    override suspend fun isEnabled(): Boolean = momentState.value

    override suspend fun setEnabled(enabled: Boolean) {
        momentState.value = enabled
    }

    override suspend fun lastGeneratedAt(): Long? = null

    override suspend fun markGenerated(atMillis: Long) = Unit

    override val chatProactiveEnabledFlow: Flow<Boolean> = chatProactiveState

    override suspend fun isChatProactiveEnabled(): Boolean = chatProactiveState.value

    override suspend fun setChatProactiveEnabled(enabled: Boolean) {
        chatProactiveState.value = enabled
    }

    override suspend fun lastProactiveChatAt(): Long? = markedAt

    override suspend fun markProactiveChat(atMillis: Long) {
        markedAt = atMillis
    }
}
