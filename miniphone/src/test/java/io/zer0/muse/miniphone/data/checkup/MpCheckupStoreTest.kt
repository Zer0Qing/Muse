package io.zer0.muse.miniphone.data.checkup

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
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

/**
 * 查岗门面：立案、按强度取证、解读、结案。
 *
 * 最关键的一条是「拿不到结论就标未完成」—— 如果这里编一句看起来像结论的话，
 * 用户就再也分不清「查过了没发现」和「根本没查成」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpCheckupStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var host: SceneHost
    private lateinit var store: MpCheckupStore
    private var seq = 0

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        host = SceneHost()
        seq = 0
        store = MpCheckupStore(db, host, now = { 1_000L }, newId = { "id-${seq++}" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedMessages(count: Int) {
        db.threadDao().upsert(MpThreadEntity(id = "t1", characterId = "c1"))
        repeat(count) { index ->
            db.messageDao().insert(
                MpMessageEntity(
                    id = "m-$index",
                    threadId = "t1",
                    characterId = "c1",
                    sender = MpActorType.CHARACTER,
                    type = MpMessageType.TEXT,
                    content = "第 $index 句",
                    createdAt = index.toLong(),
                ),
            )
        }
    }

    @Test
    fun `立案后是进行中且取到消息证据`() = runBlocking {
        seedMessages(5)
        val caseId = store.openCase("c1", MpCheckupDirection.OUTGOING, MpCheckupIntensity.STANDARD)
        val case = db.checkupCaseDao().get(caseId)
        assertEquals(MpCheckupStore.STATUS_RUNNING, case?.status)
        assertEquals("outgoing", case?.direction)
        val evidence = store.observeEvidence(caseId).first()
        assertEquals(5, evidence.size)
        assertTrue(evidence.all { it.kind == MpCheckupStore.KIND_MESSAGE })
    }

    @Test
    fun `强度决定取证条数`() = runBlocking {
        seedMessages(20)
        val light = store.openCase("c1", MpCheckupDirection.OUTGOING, MpCheckupIntensity.LIGHT)
        val deep = store.openCase("c1", MpCheckupDirection.OUTGOING, MpCheckupIntensity.DEEP)
        assertEquals(3, store.observeEvidence(light).first().size)
        assertEquals(20, store.observeEvidence(deep).first().size)
    }

    @Test
    fun `没有记录也照样立案 让没查到这件事可见`() = runBlocking {
        val caseId = store.openCase("c1", MpCheckupDirection.OUTGOING, MpCheckupIntensity.STANDARD)
        assertEquals(0, store.observeEvidence(caseId).first().size)
        assertEquals(MpCheckupStore.STATUS_RUNNING, db.checkupCaseDao().get(caseId)?.status)
    }

    @Test
    fun `拿到结论就结案并写入摘要与逐条解读`() = runBlocking {
        seedMessages(2)
        host.scene = "他最近确实常出门。"
        val caseId = store.openCase("c1", MpCheckupDirection.OUTGOING, MpCheckupIntensity.LIGHT)
        store.interpret(caseId)
        val case = db.checkupCaseDao().get(caseId)
        assertEquals(MpCheckupStore.STATUS_DONE, case?.status)
        assertEquals("他最近确实常出门。", case?.summary)
        assertTrue(store.observeEvidence(caseId).first().all { it.verdict.isNotBlank() })
    }

    @Test
    fun `拿不到结论就标未完成 不编一句像结论的话`() = runBlocking {
        seedMessages(2)
        host.scene = null
        val caseId = store.openCase("c1", MpCheckupDirection.OUTGOING, MpCheckupIntensity.LIGHT)
        store.interpret(caseId)
        val case = db.checkupCaseDao().get(caseId)
        assertEquals(MpCheckupStore.STATUS_FAILED, case?.status)
        assertEquals("", case?.summary)
        assertTrue(store.observeEvidence(caseId).first().all { it.verdict.isBlank() })
    }

    @Test
    fun `删案卷级联清掉证据`() = runBlocking {
        seedMessages(2)
        val caseId = store.openCase("c1", MpCheckupDirection.OUTGOING, MpCheckupIntensity.LIGHT)
        store.deleteCase(caseId)
        assertEquals(0, store.observeEvidence(caseId).first().size)
        assertEquals(null, db.checkupCaseDao().get(caseId))
    }

    @Test
    fun `只取目标角色的消息 不把他人的话算进来`() = runBlocking {
        seedMessages(2)
        db.messageDao().insert(
            MpMessageEntity(
                id = "other",
                threadId = "t1",
                characterId = "c2",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "别人说的",
                createdAt = 99,
            ),
        )
        val caseId = store.openCase("c1", MpCheckupDirection.OUTGOING, MpCheckupIntensity.DEEP)
        val evidence = store.observeEvidence(caseId).first()
        assertTrue(evidence.none { it.excerpt == "别人说的" })
    }
}

/** 只实现查岗用得到的那一条生成桥；其余能力按「没有」处理。 */
private class SceneHost : MiniPhoneHost {
    var scene: String? = null

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(emptyList())

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = ""))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun composeScene(characterId: String, brief: String): String? = scene

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
