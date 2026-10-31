package io.zer0.muse.miniphone.data.moment.auto

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * 后台生成的一轮完整流程:判定 → 挑角色 → 宿主生成 → 落库 → 记时间。
 *
 * 库里跑真 Room(内存库),宿主与设置用替身:这样「真的写进去了」「失败时一条也没写」
 * 是断言而不是自证。判定本身在 [MpMomentAutoPlanTest] 里已经逐条钉过,
 * 这里关心的是「判定之后做了什么」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpMomentAutoRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: MiniPhoneDb
    private lateinit var repository: MpMomentsRepository

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        val imageDir = File(tempFolder.newFolder(), "images")
        repository = MpMomentsRepository(
            momentDao = db.momentDao(),
            likeDao = db.momentLikeDao(),
            commentDao = db.momentCommentDao(),
            imageStore = MpImageStore(directory = imageDir, openStream = { null }),
            now = { MOMENT_AT },
            newId = { MOMENT_ID },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun publishesOneAiMomentAndRecordsGenerationTime() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)
        val store = FakeAutoStore()

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpMomentAutoOutcome.Published(momentId = MOMENT_ID, characterId = CHARACTER_ID), outcome)
        assertEquals(1, host.composeCalls)
        assertEquals(1, db.momentDao().count())
        val saved = db.momentDao().getMoment(MOMENT_ID)
        assertNotNull(saved)
        assertEquals(MpMomentSource.AI, saved?.source)
        assertEquals(CHARACTER_ID, saved?.authorId)
        assertEquals(COMPOSED_TEXT, saved?.content)
        assertEquals(NOW, store.lastGeneratedAtMillis)
        // 时段提示跟着素材一起交给宿主:14 点那句仍在
        assertTrue(host.lastHint.orEmpty().contains("下午的碎片时间"))
        assertTrue("素材应包含最近发生了什么: ${host.lastHint}", host.lastHint.orEmpty().contains("还没有和用户聊过"))
    }

    @Test
    fun composeReturningNullInsertsNothingAndDoesNotRetry() = runBlocking {
        val host = FakeHost(content = null)
        val store = FakeAutoStore()

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpMomentAutoOutcome.Failed("compose_null"), outcome)
        // 一轮里只调一次:失败不重试,下个周期自然再来
        assertEquals(1, host.composeCalls)
        assertEquals(0, db.momentDao().count())
        // 没落库就不记时间戳,否则下一轮会被自己的防抖挡住
        assertNull(store.lastGeneratedAtMillis)
    }

    @Test
    fun blankContentIsNotStored() = runBlocking {
        val outcome = runner(host = FakeHost(content = "   "), store = FakeAutoStore()).run()

        assertEquals(MpMomentAutoOutcome.Failed("compose_null"), outcome)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun quietHoursSkipBeforeCallingHost() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeAutoStore(), now = NIGHT).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.QUIET_HOURS), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun thirtyMinutesAfterLastGenerationSkips() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)
        val store = FakeAutoStore(lastGeneratedAt = NOW - THIRTY_MINUTES_MS)

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.TOO_SOON), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun highRollSkipsAsProbability() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeAutoStore(), roll = 0.9).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.PROBABILITY), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun noCharacterSkips() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT, characters = emptyList())

        val outcome = runner(host = host, store = FakeAutoStore()).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.NO_CHARACTER), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun switchOffSkipsEverything() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeAutoStore(enabled = false)).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.DISABLED), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    private fun runner(host: MiniPhoneHost, store: MpMomentAutoStore, now: Long = NOW, roll: Double = 0.1): MpMomentAutoRunner =
        MpMomentAutoRunner(
            host = host,
            repository = repository,
            store = store,
            material = MpCharacterMaterial(
                threadDao = db.threadDao(),
                messageDao = db.messageDao(),
                momentDao = db.momentDao(),
                now = { now },
                zone = ZONE,
            ),
            now = { now },
            random = FixedRandom(roll),
            zone = ZONE,
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 白天 14:00:不在安静时段。 */
        val NOW: Long = LocalDateTime.of(2026, 5, 12, 14, 0).atZone(ZONE).toInstant().toEpochMilli()

        /** 夜里 23:00:安静时段。 */
        val NIGHT: Long = LocalDateTime.of(2026, 5, 12, 23, 0).atZone(ZONE).toInstant().toEpochMilli()

        const val THIRTY_MINUTES_MS: Long = 30L * 60L * 1000L

        const val CHARACTER_ID: String = "c1"

        const val MOMENT_ID: String = "moment-auto-1"

        const val MOMENT_AT: Long = 1_000L

        const val COMPOSED_TEXT: String = "下午的风有点大，顺手买了杯热奶茶。"
    }
}

/** 固定骰子:概率判定与挑角色都读它,让一轮流程完全可预期。 */
private class FixedRandom(
    private val value: Double,
) : Random() {
    override fun nextBits(bitCount: Int): Int = 0

    override fun nextDouble(): Double = value
}

/** 假宿主:角色名单与生成结果都是固定值,顺带记下被调了几次、hint 是什么。 */
private class FakeHost(
    private val content: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "阿离")),
) : MiniPhoneHost {

    var composeCalls: Int = 0
        private set

    var lastHint: String? = null
        private set

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "子奇"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? {
        composeCalls++
        lastHint = hint
        return content
    }

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** 假设置:开关与上次生成时间的可变替身,把写进来的值留在字段上供断言。 */
private class FakeAutoStore(
    enabled: Boolean = true,
    lastGeneratedAt: Long? = null,
) : MpMomentAutoStore {

    private val enabledState = MutableStateFlow(enabled)

    var lastGeneratedAtMillis: Long? = lastGeneratedAt
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
